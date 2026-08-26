import XCTest

/// Drives the real app the way the operator does.
///
/// These need a live session on the device — the app is launched, not stubbed. Set
/// `DMRANDEVU_USER` and `DMRANDEVU_PASS` in the runner environment to let the tests log in, or
/// leave the app already signed in; without either they skip rather than fail.
///
/// Swiping forward past a customer queues their deletion, which is the app working as designed.
/// Tests that need to move forward swipe back afterwards where they can, which cancels it.
final class GalleryUITests: XCTestCase {

    /// The operator-facing strings this asserts on, spelled once.
    private enum Copy {
        static let downloadDone = "Galeriye kaydedildi"
        static let downloadFailed = "İndirme başarısız"
        static let photosDenied = "Fotoğraflar erişimi yok — Ayarlar'dan izin verin"
        static let captionTitle = "✨ Instagram Caption"
        static let faceBlurOn = "Yüz filtresi açık — dışa aktarılan videolarda yüzler gizlenecek"
        static let faceBlurOff = "Yüz filtresi kapalı"
        static let watermarkOn = "Filigran açık — hesap adı videonun üzerinde gezinecek"
        static let watermarkOff = "Filigran kapalı"
        static let plateBlurOn = "Plaka filtresi açık — dışa aktarılan videolarda plakalar gizlenecek"
        static let plateBlurOff = "Plaka filtresi kapalı"
        static let close = "Kapat"
    }

    private var app: XCUIApplication!

    override func setUp() async throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
        try await MainActor.run { try signInIfNeeded() }
        try await MainActor.run { try waitForGallery() }
    }

    // MARK: - The header

    /// The bug that squeezed the face filter off the row: a long customer handle took the whole
    /// header and pushed the toggles out.
    @MainActor
    func testHeaderShowsTheNameAndEveryToggle() throws {
        XCTAssertTrue(customer.exists, "no customer name")
        XCTAssertFalse(customerNameText.isEmpty, "customer name is blank")
        XCTAssertTrue(customerNameText.hasPrefix("@"), "customer name should read as a handle: \(customerNameText)")

        for identifier in ["toggleFaces", "togglePlates", "toggleWatermark"] {
            let toggle = onScreen(identifier)
            XCTAssertTrue(toggle.exists, "\(identifier) is missing from the header")
            XCTAssertTrue(toggle.frame.width > 0, "\(identifier) has been squeezed to nothing")
        }

        // Nothing in the header may sit in the notch.
        for identifier in ["customerName", "toggleFaces", "togglePlates", "toggleWatermark"] {
            XCTAssertGreaterThan(
                onScreen(identifier).frame.minY, 20,
                "\(identifier) is inside the safe area inset"
            )
        }

        // The name must not run underneath the toggles.
        let leftmostToggle = onScreen("toggleFaces").frame.minX
        XCTAssertLessThanOrEqual(
            customer.frame.maxX, leftmostToggle + 1,
            "the name overlaps the toggles instead of truncating"
        )
    }

    // MARK: - The reported bugs

    /// Pressing a control must never be read as a tap on the video.
    @MainActor
    func testWatermarkToggleTogglesAndDoesNotPause() throws {
        XCTAssertFalse(pausedIndicator.exists, "started paused")
        onScreen("toggleWatermark").tap()
        XCTAssertTrue(
            waitForAnyText([Copy.watermarkOn, Copy.watermarkOff]),
            "the watermark button did nothing — its tap went to the video"
        )
        XCTAssertFalse(pausedIndicator.exists, "the watermark button paused the video")
        onScreen("toggleWatermark").tap() // put it back
    }

    /// The face toggle was laid out underneath the notch, where it was invisible and unreachable.
    @MainActor
    func testFaceToggleIsReachableAndWorks() throws {
        let toggle = onScreen("toggleFaces")
        XCTAssertTrue(toggle.isHittable, "the face filter cannot be tapped")
        XCTAssertGreaterThan(
            toggle.frame.minY, 20,
            "the face filter is up in the notch, where it cannot be seen"
        )
        toggle.tap()
        // Both possibilities in one loop: the toast only lives three seconds, and waiting out a
        // full timeout on the wrong one first outlasts it.
        XCTAssertTrue(
            waitForAnyText([Copy.faceBlurOn, Copy.faceBlurOff]),
            "the face button did nothing — its tap went to the video"
        )
        XCTAssertFalse(pausedIndicator.exists, "the face button paused the video")
        onScreen("toggleFaces").tap() // put it back
    }

    @MainActor
    func testPlateToggleTogglesAndDoesNotPause() throws {
        onScreen("togglePlates").tap()
        XCTAssertTrue(
            waitForAnyText([Copy.plateBlurOn, Copy.plateBlurOff]),
            "the plate button did nothing — its tap went to the video"
        )
        XCTAssertFalse(pausedIndicator.exists, "the plate button paused the video")
        onScreen("togglePlates").tap() // put it back
    }

    /// Swiping up must move to the next customer.
    @MainActor
    func testSwipeUpMovesToTheNextCustomer() throws {
        let first = customerNameText
        video.swipeUp(velocity: .fast)
        XCTAssertTrue(
            waitForNameToChange(from: first),
            "swiping up did not move on from \(first)"
        )

        // Straight back, which is also the undo for the deletion the forward swipe queued.
        let second = customerNameText
        video.swipeDown(velocity: .fast)
        XCTAssertTrue(
            waitForNameToChange(from: second),
            "swiping back down did not return to the previous customer"
        )
        XCTAssertEqual(customerNameText, first, "swiping back landed somewhere else")
    }

    // MARK: - Playback

    @MainActor
    func testTapPausesAndTapAgainResumes() throws {
        video.tap()
        XCTAssertTrue(pausedIndicator.waitForExistence(timeout: 3), "a tap did not pause")
        video.tap()
        XCTAssertTrue(
            waitForDisappearance(of: pausedIndicator),
            "a second tap did not resume"
        )
    }

    @MainActor
    func testTapBringsUpTheScrubber() throws {
        video.tap()
        XCTAssertTrue(scrubber.waitForExistence(timeout: 3), "the scrubber never appeared")
        // Tapping paused the video, so put it back before the next test.
        video.tap()
    }

    @MainActor
    func testHoldingRunsTheVideoFast() throws {
        video.press(forDuration: 1.2)
        // The badge is gone by the time a press returns, so the check is that holding did not
        // instead land as a tap — which would have paused it.
        XCTAssertFalse(pausedIndicator.exists, "a hold was read as a tap and paused the video")
    }

    /// Horizontal swipes move between one customer's videos and must never change customer.
    @MainActor
    func testHorizontalSwipeStaysOnTheSameCustomer() throws {
        guard dots.exists else {
            throw XCTSkip("This customer has a single video; nothing to swipe between")
        }
        let before = customerNameText
        video.swipeLeft(velocity: .fast)
        Thread.sleep(forTimeInterval: 1.5)
        XCTAssertEqual(customerNameText, before, "a sideways swipe changed customer")
    }

    // MARK: - Export

    /// The whole point of the app: a video reaching the photo library.
    ///
    /// Whether the library actually accepts it depends on a permission this test cannot grant, so
    /// a refusal skips. What is checked either way is that the download and export ran to
    /// completion and said so.
    @MainActor
    func testDownloadRunsToCompletion() throws {
        let download = onScreen("actionDownload")
        XCTAssertTrue(download.exists, "no download button")
        download.tap()

        let saved = app.staticTexts[Copy.downloadDone]
        let refused = app.staticTexts[Copy.photosDenied]
        let failed = app.staticTexts[Copy.downloadFailed]

        let deadline = Date().addingTimeInterval(120)
        while Date() < deadline {
            if saved.exists { return }
            if refused.exists {
                throw XCTSkip("The photo library refused access, which this test cannot grant")
            }
            if failed.exists { return XCTFail("the download failed") }
            // The library only asks once the bytes are in hand, which is well after the tap — so
            // this has to be watched for throughout, not checked once up front.
            allowPhotosAccessIfAsked()
            Thread.sleep(forTimeInterval: 0.3)
        }
        XCTFail("the download never finished")
    }

    @MainActor
    func testCaptionSheetOpens() throws {
        onScreen("actionCaption").tap()
        XCTAssertTrue(
            app.staticTexts[Copy.captionTitle].waitForExistence(timeout: 10),
            "the caption sheet did not open"
        )
        app.buttons[Copy.close].tap()
    }

    // MARK: - Reaching the gallery

    @MainActor
    private func signInIfNeeded() throws {
        let password = app.secureTextFields["loginPassword"]
        guard password.waitForExistence(timeout: 8) else { return } // already signed in
        let environment = ProcessInfo.processInfo.environment
        guard let user = environment["DMRANDEVU_USER"],
              let secret = environment["DMRANDEVU_PASS"] else {
            throw XCTSkip("No stored session and no DMRANDEVU_USER / DMRANDEVU_PASS to log in with")
        }
        let username = app.textFields["loginUsername"]
        username.tap()
        username.typeText(user)
        password.tap()
        password.typeText(secret)
        app.buttons["loginSubmit"].tap()
    }

    @MainActor
    private func waitForGallery() throws {
        guard customer.waitForExistence(timeout: 45) else {
            throw XCTSkip("The gallery never loaded — no session, or the account has no videos")
        }
    }

    // MARK: - Handles

    /// The element with this identifier on the page currently on screen.
    ///
    /// The vertical pager keeps the neighbouring conversations composed, so every identifier
    /// matches two or three elements at once; the one that matters is the one inside the window.
    @MainActor
    private func onScreen(_ identifier: String) -> XCUIElement {
        let matches = app.descendants(matching: .any).matching(identifier: identifier)
        let screen = app.windows.firstMatch.frame
        for index in 0..<matches.count {
            let element = matches.element(boundBy: index)
            guard element.exists else { continue }
            let frame = element.frame
            guard frame.width > 0, frame.height > 0 else { continue }
            if frame.midY >= screen.minY, frame.midY <= screen.maxY { return element }
        }
        return matches.firstMatch
    }

    @MainActor private var customer: XCUIElement { onScreen("customerName") }
    @MainActor private var customerNameText: String { customer.label }
    @MainActor private var pausedIndicator: XCUIElement { onScreen("pausedIndicator") }
    @MainActor private var scrubber: XCUIElement { onScreen("scrubber") }
    @MainActor private var dots: XCUIElement { onScreen("mediaDots") }

    /// The middle of the screen: video, and nothing else on top of it.
    @MainActor private var video: XCUIElement {
        app.windows.firstMatch
    }

    @MainActor
    private func waitForNameToChange(from previous: String, timeout: TimeInterval = 10) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if customer.exists, customerNameText != previous { return true }
            Thread.sleep(forTimeInterval: 0.25)
        }
        return false
    }

    /// True as soon as any of `labels` is on screen.
    @MainActor
    private func waitForAnyText(_ labels: [String], timeout: TimeInterval = 5) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if labels.contains(where: { app.staticTexts[$0].exists }) { return true }
            Thread.sleep(forTimeInterval: 0.2)
        }
        return false
    }

    @MainActor
    private func waitForDisappearance(
        of element: XCUIElement,
        timeout: TimeInterval = 5
    ) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if !element.exists { return true }
            Thread.sleep(forTimeInterval: 0.2)
        }
        return false
    }

    /// Dismisses the photo library prompt if it happens to be up. Cheap enough to call in a loop:
    /// it never waits, it only looks.
    @MainActor
    private func allowPhotosAccessIfAsked() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Tümüne İzin Ver", "Allow Access to All Photos", "İzin Ver", "Allow"] {
            let button = springboard.buttons[label]
            if button.exists, button.isHittable {
                button.tap()
                return
            }
        }
    }
}

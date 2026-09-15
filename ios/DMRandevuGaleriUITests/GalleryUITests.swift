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

    // MARK: - The header and the filter rail

    /// The bug that squeezed the face filter off the row: a long customer handle took the whole
    /// header and pushed the toggles out.
    ///
    /// YENİDEN YAZILDI: filtreler başlıktan çıkıp sağ kenardaki dikey raya indi, yani "isim
    /// düğmeleri satırdan itiyor" arızası artık o eksende YOK. Ama asıl soru duruyor ve bu test
    /// onu tutuyor: her filtre ekranda ve dokunulabilir mi, yoksa bir şeyin altında mı kaldı.
    @MainActor
    func testHeaderShowsTheNameAndEveryToggle() throws {
        XCTAssertTrue(customer.exists, "no customer name")
        XCTAssertFalse(customerNameText.isEmpty, "customer name is blank")
        XCTAssertTrue(customerNameText.hasPrefix("@"), "customer name should read as a handle: \(customerNameText)")

        for identifier in ["toggleFaces", "togglePlates", "toggleWatermark"] {
            let toggle = onScreen(identifier)
            XCTAssertTrue(toggle.exists, "\(identifier) is missing from the rail")
            XCTAssertTrue(toggle.frame.width > 0, "\(identifier) has been squeezed to nothing")
            XCTAssertTrue(toggle.isHittable, "\(identifier) cannot be tapped where it now sits")
        }

        // Ne isim ne de ray güvenli alanın dışında kalabilir.
        for identifier in ["customerName", "toggleFaces", "togglePlates", "toggleWatermark"] {
            XCTAssertGreaterThan(
                onScreen(identifier).frame.minY, 20,
                "\(identifier) is inside the safe area inset"
            )
        }

        // RAY SAĞ KENARDA: Instagram'ın beğen/yorum sütunuyla aynı yerde olması istenen şey
        // buydu. Sola kayarsa hem istek karşılanmamış olur hem de karar damgasının üstüne biner.
        let screen = XCUIApplication().windows.firstMatch.frame
        for identifier in ["toggleFaces", "togglePlates", "toggleWatermark"] {
            let toggle = onScreen(identifier)
            XCTAssertGreaterThan(
                toggle.frame.midX, screen.midX,
                "\(identifier) is not on the right edge any more"
            )
        }

        // RAY ALT ÜÇTE BİRDE: kararın adını yazan damga sağ ÜSTTE 120pt'de duruyor ve parmak
        // kalkmadan okunabilmesi gerekiyor. Ray yukarı taşarsa damganın üstüne biner.
        XCTAssertGreaterThan(
            onScreen("toggleFaces").frame.minY, screen.height / 2,
            "the rail has climbed into the swipe stamp's half of the screen"
        )

        // İsim, sıradaki müşteri sayısının altına girmemeli: filtreler indikten sonra başlıkta
        // ona yol veren tek şey o sayı kaldı.
        if onScreen("remainingCount").exists {
            XCTAssertLessThanOrEqual(
                customer.frame.maxX, onScreen("remainingCount").frame.minX + 1,
                "the name overlaps the remaining count instead of truncating"
            )
        }
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
    ///
    /// Ray aşağı indikten sonra tehlike yön değiştirdi: artık çentiğin değil, alt eylem
    /// sırasının altında kalmak. İkisi de aynı arıza, o yüzden iki sınır birden tutuluyor.
    @MainActor
    func testFaceToggleIsReachableAndWorks() throws {
        let toggle = onScreen("toggleFaces")
        XCTAssertTrue(toggle.isHittable, "the face filter cannot be tapped")
        XCTAssertGreaterThan(
            toggle.frame.minY, 20,
            "the face filter is up in the notch, where it cannot be seen"
        )
        XCTAssertLessThan(
            toggle.frame.maxY,
            XCUIApplication().windows.firstMatch.frame.maxY - 80,
            "the face filter has slid down under the action row"
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

    /// Yukarı kaydırmak BİR SONRAKİ VİDEOYA geçiyor; müşterinin son videosundan
    /// sonra da bir sonraki müşteriye.
    ///
    /// BU TEST BİLEREK YENİDEN YAZILDI. Eski hâli "yukarı kaydırmak müşteriyi
    /// değiştirir" diyordu ve o cümle akış düzleşene kadar doğruydu: videolar
    /// yatay eksende geziliyordu. Yatay eksen artık KARAR (sağa at = ihbar,
    /// sola at = ihlal değil), videolar dikey eksene taşındı. Testi silmek,
    /// eksenlerin anlamını tutan tek yazılı kaydı silmek olurdu.
    @MainActor
    func testSwipeUpMovesToTheNextVideoOrCustomer() throws {
        let firstName = customerNameText
        let firstPosition = videoPosition.exists ? videoPosition.label : ""

        video.swipeUp(velocity: .fast)
        Thread.sleep(forTimeInterval: 1.5)

        let movedWithinCustomer = videoPosition.exists
            && videoPosition.label != firstPosition
            && customerNameText == firstName
        let movedToNextCustomer = customerNameText != firstName
        XCTAssertTrue(
            movedWithinCustomer || movedToNextCustomer,
            "yukarı kaydırmak ne bir sonraki videoya ne de bir sonraki müşteriye geçti"
        )

        // Geri dönmek: müşteri sınırındaysa kuyruğa girmiş silmenin de geri
        // alınması demek.
        video.swipeDown(velocity: .fast)
        Thread.sleep(forTimeInterval: 1.5)
        XCTAssertEqual(customerNameText, firstName, "geri kaydırmak başka bir yere düştü")
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

    /// Fast playback belongs to the press: the video really does run faster while the finger is
    /// down, and goes back to normal when it comes off.
    ///
    /// Measured from the playhead rather than the badge, because `press(forDuration:)` blocks
    /// until the finger lifts and XCUITest will not drive a press from another thread. How far the
    /// video got is an effect that outlives the press, and is the thing the operator cares about.
    @MainActor
    func testHoldingRunsTheVideoFast() throws {
        video.tap() // pause, and bring the scrubber up
        XCTAssertTrue(scrubber.waitForExistence(timeout: 3), "no scrubber to read")
        let start = try elapsedSeconds()

        video.tap() // play
        let began = Date()
        video.press(forDuration: 2.5)
        video.tap() // pause again
        let wallClock = Date().timeIntervalSince(began)

        XCTAssertTrue(scrubber.waitForExistence(timeout: 3), "the scrubber went away")
        let end = try elapsedSeconds()
        try XCTSkipIf(end < start, "the video looped mid-measurement")

        let advanced = end - start
        XCTAssertGreaterThan(
            advanced, wallClock * 1.8,
            "holding did not speed the video up — it advanced \(advanced)s in \(wallClock)s"
        )
        XCTAssertFalse(speedBadge.exists, "it kept running fast after the finger came off")
        video.tap() // leave it playing
    }

    /// Fast playback must end with the press. It used to *start* on release and stay on.
    @MainActor
    func testHoldingEndsWhenTheFingerLifts() throws {
        video.press(forDuration: 1.2)
        XCTAssertFalse(
            speedBadge.exists,
            "the video is still running fast after the finger came off"
        )
        XCTAssertFalse(pausedIndicator.exists, "a hold was read as a tap and paused the video")
    }

    /// Sola atmak KARAR veriyor ve geri alma çipi çıkıyor.
    ///
    /// BU TEST DE BİLEREK YENİDEN YAZILDI: eski hâli "yatay kaydırma müşteriyi
    /// değiştirmez" diyordu, yani yatay ekseni bir GEZİNME ekseni sayıyordu.
    /// Artık karar ekseni.
    ///
    /// İHBAR HESABI DEĞİLSE TERSİ SINANIYOR: orada yatay eksen tamamen atıl
    /// olmalı — ihbar hattının tanımadığı bir hesapta kaydırma, karşılığı
    /// olmayan bir kaydı karara bağlamaya çalışmak olurdu.
    @MainActor
    func testSwipeLeftDecidesAndUndoReturns() throws {
        guard ihbarChip.exists else {
            // İhbar hesabı değil: yatay eksen hiçbir şey yapmamalı.
            let before = customerNameText
            let position = videoPosition.exists ? videoPosition.label : ""
            video.swipeLeft(velocity: .fast)
            Thread.sleep(forTimeInterval: 1.5)
            XCTAssertEqual(customerNameText, before, "ihbar dışı hesapta kaydırma müşteriyi değiştirdi")
            if videoPosition.exists {
                XCTAssertEqual(videoPosition.label, position, "ihbar dışı hesapta kaydırma videoyu değiştirdi")
            }
            return
        }

        video.swipeLeft(velocity: .fast)
        // Geri alma çipi kararın TEK görünür izi: kart karar verilir verilmez
        // uçuyor ve akış ilerliyor.
        XCTAssertTrue(
            undoChip.waitForExistence(timeout: 3),
            "sola atış geri alma çipini göstermedi"
        )
        undoChip.tap()
        Thread.sleep(forTimeInterval: 1)
        XCTAssertFalse(undoChip.exists, "geri alma sonrası çip ekranda kaldı")
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

    /// Tapping İndir again while it is already working must do nothing at all.
    ///
    /// It used to pause the video. The button was `.disabled` while busy, and a disabled SwiftUI
    /// button does not swallow the tap — it goes through to the video behind and toggles
    /// tap-to-pause. A censored export runs for well over a minute, so this was most of the time
    /// the operator was looking at it.
    @MainActor
    func testTappingDownloadWhileItIsWorkingDoesNotPause() throws {
        XCTAssertFalse(pausedIndicator.exists, "started paused")

        let download = onScreen("actionDownload")
        download.tap()

        // Once it is busy, tap it again where the finger would land.
        Thread.sleep(forTimeInterval: 1.5)
        download.tap()
        Thread.sleep(forTimeInterval: 0.5)

        XCTAssertFalse(
            pausedIndicator.exists,
            "tapping the download button while it was working paused the video"
        )
        allowPhotosAccessIfAsked()
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
        dismissTourIfShown()
    }

    /// Kurulum başına bir kez çıkan akış tanıtımını kapatır.
    ///
    /// KAPATILMAZSA HİÇBİR TEST KOŞMAZ: tanıtım tam ekran ve altına hiçbir dokunuş geçirmiyor,
    /// yani taze kurulmuş bir uygulamada her testin ilk dokunuşu ona giderdi. Bir başlatma
    /// argümanıyla bastırmak yerine gerçekten kapatılıyor — operatörün yaptığı da bu, ve
    /// bastırılan bir yüzey testlerde bir daha hiç görünmezdi.
    @MainActor
    private func dismissTourIfShown() {
        let done = app.buttons["Anladım"]
        if done.waitForExistence(timeout: 2) { done.tap() }
    }

    /// Reels düğmesi videoyu Instagram'ın Reels bestecisine devrediyor mu.
    ///
    /// ELLE SINANAMIYORDU: devir uygulamanın DIŞINDA bitiyor, yani ekrandaki hiçbir şey
    /// "oldu" demiyor. Ölçülebilen tek şey uygulamanın arka plana düşmesi — Instagram öne
    /// geldiği an bu olur ve olmazsa devir hiç gerçekleşmemiş demektir.
    ///
    /// UZUN ZAMAN AŞIMI BİLİNÇLİ: düğme önce videoyu dışa aktarıyor (filtreler açıksa
    /// dakikalar), sonra caption üretiyor (sunucu tarafında 90 sn okuma zaman aşımı) ve
    /// devri ancak ondan sonra yapıyor. Kısa bir bekleme, çalışan bir akışı hatalı gösterirdi.
    ///
    /// NE BIRAKIYOR: telefonun galerisine bir video ve panoda bir caption. İhbar kararı
    /// vermiyor, konuşma silmiyor — bu yüzden gerçek hesapta koşturmak güvenli.
    @MainActor
    func testReelsButtonHandsOffToInstagram() throws {
        let reels = onScreen("actionReels")
        XCTAssertTrue(reels.waitForExistence(timeout: 10), "Reels button is not on screen")
        XCTAssertTrue(reels.isHittable, "Reels button cannot be tapped")
        reels.tap()

        let backgrounded = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "state == %d", XCUIApplication.State.runningBackground.rawValue),
            object: app
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [backgrounded], timeout: 240), .completed,
            "the app never went to the background — Instagram was not handed the video"
        )

        // PANOYU BURADAN ÖLÇEMİYORUZ ve denendi: test koşucusu ayrı bir uygulama ve öne
        // gelmediği sürece iOS genel panoyu ona hiç vermiyor ("Pasteboard is not available
        // at this time"). Dönen boş liste panonun boş olduğunu DEĞİL, sorunun
        // cevaplanmadığını gösteriyor — bu ayrımı kaybetmemek için ölçüm kaldırıldı.
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
    @MainActor private var speedBadge: XCUIElement { onScreen("speedBadge") }
    @MainActor private var videoPosition: XCUIElement { onScreen("videoPosition") }
    @MainActor private var ihbarChip: XCUIElement { onScreen("ihbarStatusChip") }
    @MainActor private var undoChip: XCUIElement { onScreen("undoChip") }

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
    /// The scrubber's elapsed readout, as seconds.
    @MainActor
    private func elapsedSeconds() throws -> TimeInterval {
        let label = onScreen("elapsed").label
        let parts = label.split(separator: ":").compactMap { Int($0) }
        guard parts.count == 2 else {
            throw XCTSkip("Could not read the playhead from \(label)")
        }
        return TimeInterval(parts[0] * 60 + parts[1])
    }

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

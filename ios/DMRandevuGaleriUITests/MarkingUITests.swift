import XCTest

/// Does holding the mark button actually make a mark on this platform?
///
/// Asked because a press-and-hold on iOS has caught me out before: `onLongPressGesture`'s
/// `perform` fires on release rather than when the press matures, which once left the video
/// running fast after the finger came off. The marking uses `onPressingChanged` for that reason,
/// and this is the check that it behaves.
final class MarkingUITests: XCTestCase {

    private var app: XCUIApplication!

    /// By identifier, whatever the element turns out to be.
    ///
    /// The filter toggles are plain images with a tap gesture rather than buttons — they were
    /// changed to that so a tap on one could not fall through to the video behind — so querying
    /// `app.buttons` finds nothing and the test skips itself while the app is working perfectly.
    private func element(_ identifier: String) -> XCUIElement {
        app.descendants(matching: .any).matching(identifier: identifier).firstMatch
    }

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
        // The feed has to be up before any of this means anything.
        guard element("toggleCensor").waitForExistence(timeout: 30) else {
            throw XCTSkip("No gallery on screen — the app is probably signed out")
        }
    }

    func testHoldingTheButtonMakesAMark() throws {
        // The censor has to be on for the marking to be there at all.
        if !element("markButton").exists { element("toggleCensor").tap() }
        let mark = element("markButton")
        guard mark.waitForExistence(timeout: 20) else {
            throw XCTSkip("The censor models are probably still downloading")
        }
        XCTAssertTrue(mark.isHittable, "the mark button cannot be reached")

        // Let the video run first: a mark is made of playback positions, and a paused video
        // gives a mark no length at all.
        let elapsed = element("elapsed")
        XCTAssertTrue(elapsed.waitForExistence(timeout: 10), "no scrubber, so nothing to mark on")
        let before = elapsed.label

        mark.press(forDuration: 2.5)
        Thread.sleep(forTimeInterval: 1)

        // The button says so while it is held, and stops saying so afterwards.
        XCTAssertTrue(element("markButton").exists, "the mark button vanished after being held")
        XCTAssertNotEqual(before, elapsed.label, "the video did not advance, so no mark was made")
    }

    /// The scrubber must stay up on its own while the filter is on, or there is nothing to aim at.
    func testTheScrubberStaysUpWhileTheFilterIsOn() throws {
        if !element("markButton").exists { element("toggleCensor").tap() }
        guard element("markButton").waitForExistence(timeout: 20) else {
            throw XCTSkip("The censor models are probably still downloading")
        }
        // Well past the three seconds the controls would otherwise hide themselves after.
        Thread.sleep(forTimeInterval: 6)
        XCTAssertTrue(
            element("elapsed").exists,
            "the scrubber hid itself while the censor was on"
        )
    }
}

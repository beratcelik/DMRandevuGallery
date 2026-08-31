import XCTest
@testable import DMRandevuGaleri

final class ManualMarksTests: XCTestCase {

    private var marks: ManualMarks!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        // Its own suite, so the tests never touch what the app remembers.
        defaults = UserDefaults(suiteName: "marks-tests-\(UUID().uuidString)")
        marks = ManualMarks(defaults: defaults)
    }

    private func window(_ fromMs: Int64, _ toMs: Int64) -> CensorWindow {
        CensorWindow(startUs: fromMs * 1_000, endUs: toMs * 1_000)
    }

    func testRemembersWhatWasMarked() {
        marks.add(conversationKey: "a", mediaIndex: 0, window: window(1_000, 2_000))
        XCTAssertEqual(marks.forMedia(conversationKey: "a", mediaIndex: 0), [window(1_000, 2_000)])
    }

    func testMarksBelongToOneVideoNotTheConversation() {
        marks.add(conversationKey: "a", mediaIndex: 0, window: window(1_000, 2_000))
        XCTAssertTrue(marks.forMedia(conversationKey: "a", mediaIndex: 1).isEmpty)
        XCTAssertTrue(marks.forMedia(conversationKey: "b", mediaIndex: 0).isEmpty)
    }

    /// Two presses over the same word are one beep, not a stutter.
    func testOverlappingMarksBecomeOne() {
        marks.add(conversationKey: "a", mediaIndex: 0, window: window(1_000, 2_000))
        marks.add(conversationKey: "a", mediaIndex: 0, window: window(1_800, 2_600))
        XCTAssertEqual(marks.forMedia(conversationKey: "a", mediaIndex: 0), [window(1_000, 2_600)])
    }

    func testMarksFarApartStayApart() {
        marks.add(conversationKey: "a", mediaIndex: 0, window: window(1_000, 2_000))
        marks.add(conversationKey: "a", mediaIndex: 0, window: window(5_000, 6_000))
        XCTAssertEqual(marks.forMedia(conversationKey: "a", mediaIndex: 0).count, 2)
    }

    func testTheyComeBackInOrderHoweverTheyWereMade() {
        marks.add(conversationKey: "a", mediaIndex: 0, window: window(5_000, 6_000))
        marks.add(conversationKey: "a", mediaIndex: 0, window: window(1_000, 2_000))
        XCTAssertEqual(
            marks.forMedia(conversationKey: "a", mediaIndex: 0),
            [window(1_000, 2_000), window(5_000, 6_000)]
        )
    }

    func testAMarkCanBeTakenOffByTouchingIt() {
        marks.add(conversationKey: "a", mediaIndex: 0, window: window(1_000, 2_000))
        marks.add(conversationKey: "a", mediaIndex: 0, window: window(5_000, 6_000))
        marks.removeAt(conversationKey: "a", mediaIndex: 0, atUs: 1_500_000)
        XCTAssertEqual(marks.forMedia(conversationKey: "a", mediaIndex: 0), [window(5_000, 6_000)])
    }

    func testTouchingEmptySpaceRemovesNothing() {
        marks.add(conversationKey: "a", mediaIndex: 0, window: window(1_000, 2_000))
        marks.removeAt(conversationKey: "a", mediaIndex: 0, atUs: 9_000_000)
        XCTAssertEqual(marks.forMedia(conversationKey: "a", mediaIndex: 0).count, 1)
    }

    /// A press and an instant release is a mis-tap, not a mark.
    func testAMarkWithNoLengthIsRefused() {
        marks.add(
            conversationKey: "a", mediaIndex: 0,
            window: CensorWindow(startUs: 1_000_000, endUs: 1_000_000)
        )
        marks.add(
            conversationKey: "a", mediaIndex: 0,
            window: CensorWindow(startUs: 2_000_000, endUs: 1_000_000)
        )
        XCTAssertTrue(marks.forMedia(conversationKey: "a", mediaIndex: 0).isEmpty)
    }

    func testClearingTakesThemAll() {
        marks.add(conversationKey: "a", mediaIndex: 0, window: window(1_000, 2_000))
        marks.add(conversationKey: "a", mediaIndex: 0, window: window(5_000, 6_000))
        marks.clear(conversationKey: "a", mediaIndex: 0)
        XCTAssertTrue(marks.forMedia(conversationKey: "a", mediaIndex: 0).isEmpty)
    }

    func testNothingMarkedReadsBackAsNothing() {
        XCTAssertTrue(marks.forMedia(conversationKey: "never-touched", mediaIndex: 3).isEmpty)
    }
}

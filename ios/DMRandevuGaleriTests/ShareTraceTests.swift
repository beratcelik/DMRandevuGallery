import XCTest
@testable import DMRandevuGaleri

final class ShareTraceTests: XCTestCase {

    func testAFullLengthVideoPassesClean() {
        XCTAssertEqual(ShareTrace.suspicion(durationMS: 63_600, expectedMS: 63_620), [])
    }

    func testAVideoAtInstagramsClipLengthIsFlaggedEvenWithNothingToCompare() {
        XCTAssertEqual(ShareTrace.suspicion(durationMS: 15_190, expectedMS: nil), ["~15s"])
    }

    func testAVideoThatLostLengthSinceTheLastStepIsFlagged() {
        XCTAssertEqual(ShareTrace.suspicion(durationMS: 15_000, expectedMS: 63_000), ["~15s", "shorter"])
        XCTAssertEqual(ShareTrace.suspicion(durationMS: 30_000, expectedMS: 63_000), ["shorter"])
    }

    func testContainerRoundingBetweenStepsIsNotALoss() {
        XCTAssertEqual(ShareTrace.suspicion(durationMS: 33_100, expectedMS: 33_408), [])
    }

    func testAFileWhoseLengthCannotBeReadIsFlagged() {
        XCTAssertEqual(ShareTrace.suspicion(durationMS: nil, expectedMS: 20_000), ["unreadable"])
    }
}

import XCTest
@testable import DMRandevuGaleri

final class CensorWindowsTests: XCTestCase {

    private let minute: Int64 = 60_000_000

    private func word(_ text: String, _ fromMs: Int64, _ toMs: Int64) -> TimedWord {
        TimedWord(text: text, startUs: fromMs * 1_000, endUs: toMs * 1_000)
    }

    func testPadsTheWordOnBothSides() {
        let windows = CensorWindows.build(
            words: [word("siktir", 1_000, 1_500)], hits: [0], durationUs: minute
        )
        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].startUs, 880_000)
        // The pad at the front, the pad plus the reach at the back: the first pass runs early, so
        // the far edge has to allow for it.
        XCTAssertEqual(windows[0].endUs, 1_500_000 + CensorWindows.shiftAllowance + 120_000)
    }

    func testMergesWordsCloseEnoughThatTwoBeepsWouldStutter() {
        // Real timings: "Amına" 30.68-31.17, "koydum" 31.17-31.78 — one beep, not two.
        let words = [word("Amına", 30_680, 31_170), word("koydum", 31_170, 31_780)]
        let windows = CensorWindows.build(words: words, hits: [0, 1], durationUs: minute)
        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].startUs, 30_560_000)
    }

    func testLeavesWordsFarApartAsSeparateBeeps() {
        let words = [word("siktir", 1_000, 1_200), word("orospu", 9_000, 9_400)]
        XCTAssertEqual(
            CensorWindows.build(words: words, hits: [0, 1], durationUs: minute).count, 2
        )
    }

    func testPaddingCannotRunOffEitherEnd() {
        let words = [word("siktir", 0, 200), word("amk", 9_900, 10_000)]
        let windows = CensorWindows.build(
            words: words, hits: [0, 1], durationUs: 10_000_000
        )
        XCTAssertEqual(windows.first?.startUs, 0)
        XCTAssertEqual(windows.last?.endUs, 10_000_000)
    }

    func testNoHitsMeansNoBeeps() {
        XCTAssertTrue(
            CensorWindows.build(words: [word("merhaba", 0, 500)], hits: [], durationUs: minute)
                .isEmpty
        )
    }

    func testHitsAreOrderedEvenWhenTheIndicesAreNot() {
        let words = [word("a", 5_000, 5_200), word("b", 1_000, 1_200), word("c", 3_000, 3_200)]
        let windows = CensorWindows.build(words: words, hits: [0, 2, 1], durationUs: minute)
        XCTAssertEqual(windows, windows.sorted { $0.startUs < $1.startUs })
    }

    /// The window runs past the word's reported end, because that is where the word is.
    func testTheWindowReachesPastTheReportedEnd() {
        let windows = CensorWindows.build(
            words: [word("sikeyim", 1_000, 1_300)], hits: [0], durationUs: minute, padUs: 0
        )
        XCTAssertEqual(windows[0].endUs, 1_300_000 + CensorWindows.shiftAllowance)
    }

    /// The reach is a flat allowance, not "up to the next word": that word's timing is shifted
    /// early by the same error, so bounding by it moves with the fault instead of correcting it.
    func testANearNextWordDoesNotShortenTheReach() {
        let near = [word("sikeyim", 1_000, 1_300), word("seni", 1_300, 1_450)]
        let far = [word("sikeyim", 1_000, 1_300), word("sonra", 9_000, 9_400)]
        XCTAssertEqual(
            CensorWindows.build(words: near, hits: [0], durationUs: minute, padUs: 0)[0].endUs,
            CensorWindows.build(words: far, hits: [0], durationUs: minute, padUs: 0)[0].endUs
        )
    }

    /// A placed word needs far less slack than a guessed one.
    func testAMeasuredWindowIsMuchTighter() {
        let words = [word("sikeyim", 1_000, 1_300)]
        let loose = CensorWindows.build(words: words, hits: [0], durationUs: minute)
        let tight = CensorWindows.build(
            words: words, hits: [0], durationUs: minute,
            shiftAllowanceUs: CensorWindows.residualAllowance
        )
        XCTAssertLessThan(tight[0].durationUs, loose[0].durationUs)
    }
}

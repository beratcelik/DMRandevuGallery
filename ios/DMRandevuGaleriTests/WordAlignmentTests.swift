import XCTest
@testable import DMRandevuGaleri

final class WordAlignmentTests: XCTestCase {

    func testIdenticalTranscriptsPairStraightThrough() {
        let words = ["sen", "ben", "geldim"]
        let pairs = WordAlignment.align(words, words)
        XCTAssertEqual(pairs.count, 3)
        for (index, pair) in pairs.enumerated() {
            XCTAssertEqual(pair.detectionIndex, index)
            XCTAssertEqual(pair.timingIndex, index)
        }
    }

    /// The point of the whole thing: a word swapped for a near-homophone still has to pair up, or
    /// the swearing never receives a timing.
    func testASwappedWordStillPairsWithWhatReplacedIt() {
        let detection = ["sen", "ben", "sikeceğim", "bekle"]
        let timing = ["sen", "ben", "çıkacağım", "bekle"]
        let pairs = WordAlignment.align(detection, timing)
        XCTAssertEqual(pairs.first { $0.detectionIndex == 2 }?.timingIndex, 2)
    }

    /// A dropped word must not slide everything after it out of step.
    func testADroppedWordDoesNotShiftTheRest() {
        let detection = ["bir", "iki", "üç", "dört"]
        let timing = ["bir", "üç", "dört"]
        let pairs = WordAlignment.align(detection, timing)
        XCTAssertEqual(pairs.first { $0.detectionIndex == 3 }?.timingIndex, 2)
    }

    func testEmptyInputPairsNothing() {
        XCTAssertTrue(WordAlignment.align([], ["a"]).isEmpty)
        XCTAssertTrue(WordAlignment.align(["a"], []).isEmpty)
    }

    func testPairsComeBackInOrder() {
        let pairs = WordAlignment.align(["a", "b", "c"], ["a", "x", "b", "c"])
        XCTAssertEqual(pairs.map(\.detectionIndex), pairs.map(\.detectionIndex).sorted())
        XCTAssertEqual(pairs.map(\.timingIndex), pairs.map(\.timingIndex).sorted())
    }

    /// End to end over the real transcripts: the untimed pass accuses a word, alignment gives it
    /// a time, and the window lands where the swearing actually is.
    func testAVerdictFromOnePassBecomesAWindowOnTheOther() {
        let detection = "Sen ben almadığımı sikeceğim sen bekle bekle"
            .split(separator: " ").map(String.init)
        let timing = [
            TimedWord(text: "Sen", startUs: 42_500_000, endUs: 42_780_000),
            TimedWord(text: "ben", startUs: 42_780_000, endUs: 43_050_000),
            TimedWord(text: "aradığımı", startUs: 43_050_000, endUs: 44_030_000),
            TimedWord(text: "çıkacağım", startUs: 44_030_000, endUs: 44_510_000),
            TimedWord(text: "sen", startUs: 44_510_000, endUs: 44_710_000),
            TimedWord(text: "bekle", startUs: 44_710_000, endUs: 44_970_000),
            TimedWord(text: "bekle", startUs: 44_970_000, endUs: 45_290_000)
        ]
        let lexicon = ProfanityLexicon()
        let hits = Set(
            WordAlignment.align(detection, timing.map(\.text))
                .filter { lexicon.isProfane(detection[$0.detectionIndex]) }
                .map(\.timingIndex)
        )
        XCTAssertEqual(hits, [3])

        let windows = CensorWindows.build(words: timing, hits: hits, durationUs: 60_000_000)
        XCTAssertEqual(windows.count, 1)
        XCTAssertEqual(windows[0].startUs, 43_910_000)
    }
}

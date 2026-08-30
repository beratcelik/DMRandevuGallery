import XCTest
@testable import DMRandevuGaleri

/// The same traps the Android list is held to, because it is the same list.
final class ProfanityLexiconTests: XCTestCase {

    private let lexicon = ProfanityLexicon()

    func testCatchesTheOrdinaryForms() {
        for word in ["siktir", "Siktir", "amk", "orospu", "piç", "yarrak", "pezevenk"] {
            XCTAssertTrue(lexicon.isProfane(word), word)
        }
    }

    func testSuffixesDoNotHideAWord() {
        // Turkish glues endings on relentlessly; a prefix entry has to survive that.
        for word in ["siktiğim", "siktirgit", "orospunun", "amcığını"] {
            XCTAssertTrue(lexicon.isProfane(word), word)
        }
    }

    /// The dotless-i trap, which is the reason normalize() pins the locale.
    func testSqueezeIsNotTheOtherWord() {
        XCTAssertFalse(lexicon.isProfane("sık"))
        XCTAssertFalse(lexicon.isProfane("sıkıldım"))
        XCTAssertFalse(lexicon.isProfane("SIK"), "default lowercasing maps SIK onto sik")
        XCTAssertTrue(lexicon.isProfane("SİK"))
    }

    func testCarryingSomethingIsNotAnInsult() {
        XCTAssertFalse(lexicon.isProfane("götür"))
        XCTAssertFalse(lexicon.isProfane("götürdü"))
        XCTAssertFalse(lexicon.isProfane("götürüyorum"))
        XCTAssertTrue(lexicon.isProfane("göt"))
    }

    func testInnocentWordsThatStartTheSameWay() {
        for word in ["ama", "aman", "amca", "ambulans", "Amerika", "siklon", "sikke"] {
            XCTAssertFalse(lexicon.isProfane(word), word)
        }
    }

    /// Whisper sometimes writes the word out masked, and a masked swear is still a swear.
    func testMaskedSpellingsStillCount() {
        XCTAssertTrue(lexicon.isProfane("s*ktir"))
        XCTAssertTrue(lexicon.isProfane("or*spu"))
    }

    func testInsultsAreOffUnlessAskedFor() {
        XCTAssertFalse(lexicon.isProfane("manyak"))
        XCTAssertTrue(lexicon.isProfane("manyak", tiers: [.profanity, .insult]))
    }

    /// "koydum" is an ordinary verb on its own and half of the commonest curse after "amına".
    func testTheTwoWordCurse() {
        let words = ["Kafanı", "kırarım", "Amına", "koydum", "bunu", "seni"]
        XCTAssertEqual(lexicon.hits(words), [2, 3])

        // The innocent half, alone and in an ordinary sentence.
        XCTAssertFalse(lexicon.isProfane("koydum"))
        XCTAssertTrue(lexicon.hits(["kitabı", "koydum", "masaya"]).isEmpty)
    }

    func testPunctuationDoesNotHideAWord() {
        XCTAssertTrue(lexicon.isProfane("siktir!"))
        XCTAssertTrue(lexicon.isProfane("\"orospu\","))
    }
}

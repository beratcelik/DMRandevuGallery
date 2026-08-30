import Foundation

/// A word the recognizer heard, and when it heard it.
struct TimedWord: Equatable {
    var text: String
    var startUs: Int64
    var endUs: Int64
}

/// Which Turkish words get beeped.
///
/// A plain list rather than anything clever. Fuzzy matching on this language is a trap: the
/// recognizer's own mistakes are near-words, and matching loosely turns every one of them into a
/// beep over innocent speech. Everything here is an exact form or a deliberate prefix.
struct ProfanityLexicon {

    /// Outright profanity is always beeped. Insults are real but beeping them censors ordinary
    /// shouting, so they are off unless the operator asks.
    enum Tier {
        case profanity
        case insult
    }

    enum Mode {
        case exact
        /// Matches the root and anything suffixed onto it, which Turkish does relentlessly.
        case prefix
    }

    struct Entry {
        var root: String
        var mode: Mode
        var tier: Tier = .profanity
        /// Innocent words that begin with the same letters. "götür-" is the whole reason.
        var exceptions: [String] = []
    }

    /// Two words that are only profane together. "amına" and "koydum" are both ordinary on their
    /// own; side by side they are the commonest curse in the language.
    struct Phrase {
        var first: String
        var second: String
    }

    private let entries: [Entry]

    init(entries: [Entry] = ProfanityLexicon.defaultEntries) {
        self.entries = entries
    }

    /// Whether `word` as the recognizer wrote it should be beeped.
    func isProfane(_ word: String, tiers: Set<Tier> = [.profanity]) -> Bool {
        let normalized = Self.normalize(word)
        if normalized.isEmpty { return false }
        return entries.contains { entry in
            guard tiers.contains(entry.tier) else { return false }
            switch entry.mode {
            case .exact:
                return Self.matches(normalized, entry.root)
            case .prefix:
                return Self.startsWith(normalized, entry.root)
                    && !entry.exceptions.contains { Self.startsWith(normalized, $0) }
            }
        }
    }

    /// The indices in `words` that have to be beeped, single words and phrases alike.
    func hits(_ words: [String], tiers: Set<Tier> = [.profanity]) -> Set<Int> {
        var hits = Set<Int>()
        let normalized = words.map(Self.normalize)
        for index in words.indices where isProfane(words[index], tiers: tiers) {
            hits.insert(index)
        }
        guard words.count > 1 else { return hits }
        for index in 0..<(words.count - 1) {
            for phrase in Self.phrases {
                if Self.startsWith(normalized[index], phrase.first),
                   Self.startsWith(normalized[index + 1], phrase.second) {
                    hits.insert(index)
                    hits.insert(index + 1)
                }
            }
        }
        return hits
    }

    /// Lower-cased the Turkish way, stripped of everything but letters, digits and `*`.
    ///
    /// The locale is not a detail. Turkish has two i's, and the default lowercasing maps "SIK"
    /// (squeeze) onto "sik" (the profanity) — so an innocent shout in capitals would be beeped.
    static func normalize(_ word: String) -> String {
        let lowered = word.lowercased(with: Locale(identifier: "tr_TR"))
        return String(lowered.filter { $0.isLetter || $0.isNumber || $0 == "*" })
    }

    /// Equality where `*` in `word` stands for any single character.
    ///
    /// Whisper sometimes writes profanity out masked — "s*ktir" — and a masked swear is still a
    /// swear that has to be beeped.
    private static func matches(_ word: String, _ root: String) -> Bool {
        let w = Array(word)
        let r = Array(root)
        guard w.count == r.count else { return false }
        return zip(w, r).allSatisfy { $0 == "*" || $0 == $1 }
    }

    private static func startsWith(_ word: String, _ root: String) -> Bool {
        let w = Array(word)
        let r = Array(root)
        guard w.count >= r.count else { return false }
        return zip(w.prefix(r.count), r).allSatisfy { $0 == "*" || $0 == $1 }
    }

    static let phrases: [Phrase] = [
        Phrase(first: "amina", second: "koy"), Phrase(first: "amına", second: "koy"),
        Phrase(first: "amina", second: "kod"), Phrase(first: "amına", second: "kod"),
        Phrase(first: "anani", second: "sik"), Phrase(first: "ananı", second: "sik"),
        Phrase(first: "ananin", second: "am"), Phrase(first: "ananın", second: "am"),
        Phrase(first: "avradini", second: "sik"), Phrase(first: "avradını", second: "sik"),
        Phrase(first: "orospu", second: "çocu"), Phrase(first: "orospu", second: "cocu"),
        Phrase(first: "orospu", second: "evla"),
        Phrase(first: "it", second: "oğlu")
    ]

    /// The list itself. Kept in one place so it can be read and argued with; the operator sees
    /// the effect of every line the moment a video is exported.
    static let defaultEntries: [Entry] = [
        // "am" alone is unusable as a prefix — ama, aman, amca, ambulans, Amerika all start with
        // it. Only the forms that cannot be anything else.
        Entry(root: "amk", mode: .exact), Entry(root: "amq", mode: .exact),
        Entry(root: "aq", mode: .exact),
        Entry(root: "amina", mode: .prefix), Entry(root: "amına", mode: .prefix),
        Entry(root: "amcik", mode: .prefix), Entry(root: "amcık", mode: .prefix),
        Entry(root: "amcigi", mode: .prefix), Entry(root: "amcığı", mode: .prefix),

        // sik-: the dotted i is the profanity, the dotless ı ("sık") is not, and normalize() is
        // what keeps them apart. "siklon" (cyclone) and "sikke" (coin) are innocent.
        Entry(root: "sik", mode: .prefix, exceptions: ["siklon", "sikke", "sikloid"]),
        Entry(root: "siktir", mode: .prefix),

        // göt-: "götür-" (to carry) is the whole reason exceptions exist.
        Entry(root: "göt", mode: .prefix, exceptions: ["götür"]),
        Entry(root: "got", mode: .prefix, exceptions: ["gotur", "gotham", "gotik"]),

        Entry(root: "orospu", mode: .prefix), Entry(root: "kahpe", mode: .prefix),
        Entry(root: "piç", mode: .prefix), Entry(root: "pic", mode: .exact),
        Entry(root: "yarrak", mode: .prefix), Entry(root: "yarak", mode: .prefix),
        Entry(root: "yarra", mode: .prefix),
        Entry(root: "pezevenk", mode: .prefix), Entry(root: "gavat", mode: .prefix),
        Entry(root: "kaltak", mode: .prefix), Entry(root: "sürtük", mode: .prefix),
        Entry(root: "surtuk", mode: .prefix),
        Entry(root: "puşt", mode: .prefix), Entry(root: "pust", mode: .exact),
        Entry(root: "yavşak", mode: .prefix), Entry(root: "yavsak", mode: .prefix),
        Entry(root: "ibne", mode: .prefix), Entry(root: "ibno", mode: .prefix),
        Entry(root: "avrat", mode: .prefix),

        // koy-/kod- as the vulgar verb. The innocent senses of "koymak" are far too common to
        // prefix-match, so only the forms that are vulgar on their own.
        Entry(root: "koyim", mode: .exact), Entry(root: "koyum", mode: .exact),
        Entry(root: "koyayim", mode: .exact), Entry(root: "koyayım", mode: .exact),
        Entry(root: "kodumun", mode: .prefix), Entry(root: "koduğumun", mode: .prefix),
        Entry(root: "kodugumun", mode: .prefix), Entry(root: "koyduğumun", mode: .prefix),
        Entry(root: "koydugumun", mode: .prefix),

        // Insults: real, but beeping them censors ordinary shouting. Off unless asked for.
        Entry(root: "şerefsiz", mode: .prefix, tier: .insult),
        Entry(root: "serefsiz", mode: .prefix, tier: .insult),
        Entry(root: "namussuz", mode: .prefix, tier: .insult),
        Entry(root: "haysiyetsiz", mode: .prefix, tier: .insult),
        Entry(root: "manyak", mode: .prefix, tier: .insult),
        Entry(root: "salak", mode: .prefix, tier: .insult),
        Entry(root: "aptal", mode: .prefix, tier: .insult),
        Entry(root: "gerizekali", mode: .prefix, tier: .insult),
        Entry(root: "gerizekalı", mode: .prefix, tier: .insult),
        Entry(root: "dangalak", mode: .prefix, tier: .insult),
        Entry(root: "angut", mode: .prefix, tier: .insult),
        Entry(root: "hayvan", mode: .prefix, tier: .insult),
        Entry(root: "eşşek", mode: .prefix, tier: .insult),
        Entry(root: "essek", mode: .prefix, tier: .insult)
    ]
}

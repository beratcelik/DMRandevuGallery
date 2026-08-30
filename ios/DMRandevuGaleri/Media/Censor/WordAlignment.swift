import Foundation

/// Lines up two transcripts of the same audio, word for word.
///
/// The recognizer will not give good text and good timings in the same pass. Asked for timings it
/// quietly swaps the swearing for something innocent — the same second of audio that transcribes
/// as "sikeceğim" with timestamps off comes back as "çıkacağım" with them on — and asked for text
/// it answers with one thirty-second block, which is no use for placing a beep.
///
/// So both passes run, and this puts them side by side: the pass that found the swearing says
/// *what*, the pass that carries timings says *when*. Needleman-Wunsch, because the two
/// transcripts disagree by insertions and deletions as well as swaps, and a positional guess
/// would slide out of step at the first dropped word and beep the wrong second of audio.
enum WordAlignment {

    /// One word from each transcript, judged to be the same word.
    struct Pair: Equatable {
        var detectionIndex: Int
        var timingIndex: Int
    }

    /// Pairs of indices into `detection` and `timing`, in order.
    ///
    /// Words neither transcript agrees on are simply left unpaired: an unpaired detection word
    /// carries no timing, so it cannot be beeped, and an unpaired timing word was never accused
    /// of anything.
    static func align(_ detection: [String], _ timing: [String]) -> [Pair] {
        let a = detection.map(ProfanityLexicon.normalize)
        let b = timing.map(ProfanityLexicon.normalize)
        if a.isEmpty || b.isEmpty { return [] }

        var score = Array(repeating: Array(repeating: 0, count: b.count + 1), count: a.count + 1)
        for i in 1...a.count { score[i][0] = i * gap }
        for j in 1...b.count { score[0][j] = j * gap }
        for i in 1...a.count {
            for j in 1...b.count {
                score[i][j] = max(
                    score[i - 1][j - 1] + similarity(a[i - 1], b[j - 1]),
                    score[i - 1][j] + gap,
                    score[i][j - 1] + gap
                )
            }
        }

        var pairs: [Pair] = []
        var i = a.count
        var j = b.count
        while i > 0 && j > 0 {
            if score[i][j] == score[i - 1][j - 1] + similarity(a[i - 1], b[j - 1]) {
                pairs.append(Pair(detectionIndex: i - 1, timingIndex: j - 1))
                i -= 1
                j -= 1
            } else if score[i][j] == score[i - 1][j] + gap {
                i -= 1
            } else {
                j -= 1
            }
        }
        return pairs.reversed()
    }

    /// How alike two words are.
    ///
    /// A swapped word still has to pair up with the word it replaced, or the swearing never
    /// receives a timing. The two are usually near-homophones the recognizer chose between —
    /// "sikeceğim" against "çıkacağım" — so a shared run of letters at the front earns partial
    /// credit, and only words with nothing in common are pushed apart.
    private static func similarity(_ a: String, _ b: String) -> Int {
        if a == b { return match }
        let shared = zip(a, b).prefix { $0 == $1 }.count
        let shorter = min(a.count, b.count)
        return shared >= max(2, shorter / 2) ? near : mismatch
    }

    private static let match = 2
    private static let near = 1
    private static let mismatch = -1
    private static let gap = -1
}

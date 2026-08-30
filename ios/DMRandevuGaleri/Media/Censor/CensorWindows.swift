import Foundation

/// A stretch of audio to beep.
struct CensorWindow: Equatable {
    var startUs: Int64
    var endUs: Int64

    var durationUs: Int64 { endUs - startUs }
}

/// Turns "these words are swearing" into "beep from here to here".
enum CensorWindows {

    /// `hits` are indices into `words`. `durationUs` bounds the last window so padding cannot run
    /// off the end of the audio.
    static func build(
        words: [TimedWord],
        hits: Set<Int>,
        durationUs: Int64,
        padUs: Int64 = padDefault,
        mergeGapUs: Int64 = mergeGapDefault,
        shiftAllowanceUs: Int64 = shiftAllowance
    ) -> [CensorWindow] {
        if hits.isEmpty { return [] }

        let spans: [(Int64, Int64)] = hits
            .filter { words.indices.contains($0) }
            .map { index -> (Int64, Int64) in
                let word = words[index]
                // Runs past where the word reportedly ends, by however much is still unaccounted
                // for after the second pass has had its say.
                //
                // The first recognition pass runs early: measured against the same clip cut by
                // hand, it places "Amına koydu mu" at 30.00-30.97 s where the words really are at
                // 30.68-31.78 s. Ending the beep where the word reportedly ends stops in the
                // middle of the swearing. When the second pass has placed the words properly the
                // caller passes a small allowance instead; when it could not, this covers being
                // most of a second out, because too much beep is a nuisance and too little leaves
                // the swearing audible.
                let end = word.endUs + shiftAllowanceUs
                return (max(0, word.startUs - padUs), min(durationUs, end + padUs))
            }
            .filter { $0.1 > $0.0 }
            .sorted { $0.0 < $1.0 }

        var merged: [CensorWindow] = []
        for (start, end) in spans {
            if let last = merged.last, start - last.endUs < mergeGapUs {
                merged[merged.count - 1] = CensorWindow(
                    startUs: last.startUs,
                    endUs: max(last.endUs, end)
                )
            } else {
                merged.append(CensorWindow(startUs: start, endUs: end))
            }
        }
        return merged
    }

    /// What the window must cover when the second pass could not place the words.
    ///
    /// Sized from the measured error — 680 to 810 ms on the operator's own clip — with enough
    /// margin that the end of a phrase, where the drift is largest, is still covered.
    static let shiftAllowance: Int64 = 900_000

    /// And what is left once the words *have* been placed: the residual of that measurement,
    /// plus a little for the word boundary.
    static let residualAllowance: Int64 = 350_000

    /// How far either side of the word the beep reaches, for the leading consonant that starts
    /// before the recognizer says the word does.
    static let padDefault: Int64 = 120_000

    /// Two windows closer than this become one, rather than a beep-gap-beep stutter.
    static let mergeGapDefault: Int64 = 300_000
}

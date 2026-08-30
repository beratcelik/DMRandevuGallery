import Foundation

/// Turns what the recognizer returns into timed words.
///
/// Its own type rather than a private method because the timing of these words is what decides
/// where a beep lands, and a test that reimplements the assembly to check it proves nothing about
/// what actually runs.
enum WordAssembly {

    /// A leading space is how whisper marks the start of a word; the rest are its middle. That is
    /// how Turkish comes back — "MÜZİK" arrives as M, Ü, Z, İ, K — so without this the lexicon
    /// would be matching single letters.
    static func words(from segments: [WhisperContext.Segment]) -> [TimedWord] {
        var words: [TimedWord] = []
        var text = ""
        var startMs: Int64 = 0
        var endMs: Int64 = 0

        func flush() {
            let finished = text.trimmingCharacters(in: .whitespaces)
            if !finished.isEmpty {
                words.append(
                    TimedWord(
                        text: finished,
                        startUs: startMs * 1_000,
                        endUs: max(endMs, startMs + minWordMs) * 1_000
                    )
                )
            }
            text = ""
        }

        for segment in segments {
            if segment.text.trimmingCharacters(in: .whitespaces).isEmpty { continue }
            if segment.text.hasPrefix(" ") || text.isEmpty {
                flush()
                startMs = segment.startMs
            }
            text += segment.text.trimmingCharacters(in: .whitespaces)
            endMs = segment.endMs
        }
        flush()
        return words
    }

    /// Floor on a word's length, so two tokens reported together still beep something.
    static let minWordMs: Int64 = 120
}

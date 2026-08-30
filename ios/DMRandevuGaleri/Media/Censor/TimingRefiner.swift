import Foundation
import OSLog

/// Asks the recognizer again, about a few seconds at a time, to find out when the swearing really
/// happens.
///
/// Recognising the whole clip gives the right words at the wrong times: whisper works in
/// thirty-second windows and a word early in a window is dragged towards the window's start. On
/// the operator's clip that put "Amına koydu mu" at 30.00 s when it is at 30.68 s, and the beep
/// had to be stretched by a second to be sure of covering it.
///
/// A few seconds of audio is a single window, and a word in the middle of it has no boundary to be
/// pulled to. Measured on Android with the same clip and model: an eight-second snippet places the
/// word within 20 ms. So the first pass says *what* and roughly where, and this says *when*.
struct TimingRefiner {

    /// A stretch of swearing, as precisely as it can be placed.
    struct Refined {
        var startUs: Int64
        var endUs: Int64
    }

    let lexicon: ProfanityLexicon
    let transcribe: (_ samples: [Float]) async throws -> [WhisperContext.Segment]

    private static let log = Logger(subsystem: "com.dmrandevu.gallery", category: "censor")

    /// Logged and, in a debug build, printed too.
    ///
    /// The device console is not reachable from a test run, and these lines are the only way to
    /// tell a refinement that was rejected from one that never ran.
    private static func trace(_ message: String) {
        log.info("\(message, privacy: .public)")
        #if DEBUG
        print("[censor] \(message)")
        #endif
    }

    /// Re-times each run of consecutive hit words. Returns nil for a run the second pass could not
    /// confirm — the caller keeps the rough timing and the wide window that goes with it.
    func refine(
        audio: AudioTrackDecoder.DecodedAudio,
        words: [TimedWord],
        hits: Set<Int>,
        tiers: Set<ProfanityLexicon.Tier>
    ) async throws -> [(range: ClosedRange<Int>, refined: Refined?)] {
        var result: [(ClosedRange<Int>, Refined?)] = []
        for run in Self.runs(of: hits) {
            let refined = try await refineOne(
                audio: audio,
                roughStartUs: words[run.lowerBound].startUs,
                roughEndUs: words[run.upperBound].endUs,
                tiers: tiers
            )
            result.append((run, refined))
        }
        return result
    }

    private func refineOne(
        audio: AudioTrackDecoder.DecodedAudio,
        roughStartUs: Int64,
        roughEndUs: Int64,
        tiers: Set<ProfanityLexicon.Tier>
    ) async throws -> Refined? {
        // Reaches further forward than back: the first pass reports early, so the word is after
        // where it was said to be, not before. And never a short snippet — below about six seconds
        // whisper's timestamps stop spreading out and pile onto the last instant of the audio.
        var from = max(0, roughStartUs - Self.leadUs)
        var to = min(audio.durationUs, roughEndUs + Self.trailUs)
        if to - from < Self.minSnippetUs {
            to = min(audio.durationUs, from + Self.minSnippetUs)
            from = max(0, to - Self.minSnippetUs)
        }
        guard to - from >= Self.minSnippetUs else { return nil }

        let snippet = try cut(audio, fromUs: from, toUs: to)
        let heard = WordAssembly.words(from: try await transcribe(snippet))
        Self.trace(
            "snippet \(from / 1000)-\(to / 1000)ms (rough \(roughStartUs / 1000)-"
                + "\(roughEndUs / 1000)): "
                + heard.map { "\($0.text)@\((from + $0.startUs) / 1000)" }.joined(separator: " ")
        )
        guard !heard.isEmpty else { return nil }

        let found = lexicon.hits(heard.map(\.text), tiers: tiers)
        guard !found.isEmpty else {
            Self.trace("the second pass heard no swearing in the snippet")
            return nil
        }

        let start = found.map { heard[$0].startUs }.min()!
        // Carried to the end of the word after the last one flagged. The second pass splits words
        // where the lexicon does not: "koydum" comes back as "koydu" and "mu?", and only the first
        // half is profane, so stopping at it stops halfway through the swearing.
        let lastHit = found.max()!
        let spoken = heard[lastHit].endUs
        let end = lastHit + 1 < heard.count
            ? min(heard[lastHit + 1].endUs, spoken + Self.tailReachUs)
            : spoken

        // Whether the second pass actually placed anything is checked rather than assumed. Its
        // timestamps do not always spread across the snippet: they can pile onto the first or last
        // instant of it, and a "refinement" that returns the whole snippet is worse than the rough
        // timing it replaced, because it would beep six seconds.
        let span = to - from
        let rough = roughEndUs - roughStartUs
        let atStart = start <= Self.saturationMarginUs
        let atEnd = end >= span - Self.saturationMarginUs
        let tooLong = (end - start) > rough * Int64(Self.implausibleFactor) + Self.implausibleSlackUs
        if atStart || atEnd || tooLong {
            Self.trace(
                "second pass placed the words at \(start / 1000)-\(end / 1000)ms of a "
                    + "\(span / 1000)ms snippet (atStart \(atStart), atEnd \(atEnd), "
                    + "tooLong \(tooLong)); not usable"
            )
            return nil
        }

        Self.trace("refined to \((from + start) / 1000)-\((from + end) / 1000)ms")
        return Refined(startUs: from + start, endUs: from + end)
    }

    /// Consecutive hit indices belong to one phrase and are re-timed together.
    private static func runs(of hits: Set<Int>) -> [ClosedRange<Int>] {
        let sorted = hits.sorted()
        var runs: [ClosedRange<Int>] = []
        var first = -1
        var last = -1
        for index in sorted {
            if first < 0 {
                first = index
                last = index
            } else if index == last + 1 {
                last = index
            } else {
                runs.append(first...last)
                first = index
                last = index
            }
        }
        if first >= 0 { runs.append(first...last) }
        return runs
    }

    /// 16 kHz mono floats for one stretch of the decoded track.
    private func cut(
        _ audio: AudioTrackDecoder.DecodedAudio,
        fromUs: Int64,
        toUs: Int64
    ) throws -> [Float] {
        let channels = audio.channelCount
        let from = Int(fromUs * Int64(audio.sampleRate) / 1_000_000)
        let to = min(Int(audio.frameCount), Int(toUs * Int64(audio.sampleRate) / 1_000_000))
        let frames = max(0, to - from)
        var slice = [Int16](repeating: 0, count: frames * channels)
        for i in 0..<slice.count {
            let at = from * channels + i
            if at < audio.samples.count { slice[i] = audio.samples[at] }
        }
        return try PcmOps.forRecognition(
            slice, channelCount: channels, sampleRate: audio.sampleRate
        )
    }

    /// How far back the snippet starts from where the first pass put the word.
    ///
    /// Deliberately short. The word is *after* where the first pass put it, never before, and
    /// every extra second of speech at the front is another second whisper has to place before it
    /// reaches the part that matters — which is when the timestamps give up and pile onto one end.
    static let leadUs: Int64 = 2_000_000

    /// And well past it, because the first pass reports early.
    static let trailUs: Int64 = 6_000_000

    /// Below about six seconds the timestamps saturate rather than spread.
    static let minSnippetUs: Int64 = 6_000_000

    /// How far past the last flagged word the beep may run to catch the rest of it. Small,
    /// because this is applied to a timing that has already been measured properly.
    static let tailReachUs: Int64 = 700_000

    /// A word this close to either edge of the snippet is stuck there, not placed there.
    static let saturationMarginUs: Int64 = 150_000

    static let implausibleFactor = 2
    static let implausibleSlackUs: Int64 = 1_000_000
}

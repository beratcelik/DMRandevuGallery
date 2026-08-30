import Foundation
import OSLog

/// Finds the swearing in a decoded audio track, and says when each one happens.
///
/// It takes several passes, for two reasons measured rather than guessed:
///
/// - **The recognizer will not give good words and good times at the same time.** With timestamps
///   suppressed it transcribes swearing faithfully but reports one thirty-second block, which
///   cannot place a beep. With them on it emits a word at a time, but quietly swaps the swearing
///   for an innocent near-homophone — the same second of audio came back as "sikeceğim" one way
///   and "çıkacağım" the other.
/// - **Slowing the audio down changes what it hears.** That "sikeceğim" only appears at 0.75×
///   speed; every real-time pass wrote the harmless word instead.
///
/// So the detection passes hunt for words, the timing pass holds the clock, `WordAlignment`
/// carries each verdict from one to the other, and `TimingRefiner` then places it properly.
actor SpeechRecognizer {

    struct RecognitionFailedError: Error {
        var message: String
    }

    /// What the timing pass heard, which of those words have to be beeped, and whether a second
    /// pass confirmed where they are.
    struct Result {
        var words: [TimedWord]
        var hits: Set<Int>
        var refined: Bool
    }

    private let models: CensorModels
    private let lexicon: ProfanityLexicon
    private var loaded: WhisperContext?
    private var loadedFrom: URL?

    private static let log = Logger(subsystem: "com.dmrandevu.gallery", category: "censor")

    init(models: CensorModels, lexicon: ProfanityLexicon = ProfanityLexicon()) {
        self.models = models
        self.lexicon = lexicon
    }

    /// `audio` is the whole decoded track. `onProgress` reports 0…1 across every pass.
    func findProfanity(
        audio: AudioTrackDecoder.DecodedAudio,
        tiers: Set<ProfanityLexicon.Tier> = [.profanity],
        onProgress: @escaping (Double) -> Void
    ) async throws -> Result {
        var done = 0.0
        var expected = Double(Self.basePasses.count)
        func step() -> Double {
            done += 1
            return min(1, done / expected)
        }

        // The timing pass first: everything else is measured against its word list.
        let timingPass = Self.basePasses.first { $0.carriesTiming }!
        let started = Date()
        let words = WordAssembly.words(from: try await transcribe(audio, pass: timingPass))
        Self.log.info("timing pass: \(words.count) words in \(Int(-started.timeIntervalSinceNow * 1000))ms")
        onProgress(step())

        if words.isEmpty { return Result(words: [], hits: [], refined: false) }

        var hits = lexicon.hits(words.map(\.text), tiers: tiers)
        let timingText = words.map(\.text)

        // The larger model is three times slower and is only worth its minutes in the case it was
        // measured to help: base occasionally goes deaf on a clip and returns nothing but
        // "[MÜZİK ÇALIYOR]" where small transcribes it in full. When base has clearly heard the
        // speech, small adds minutes to find the same words — and on the one clip with real
        // swearing it was small that sanitised it, not base.
        let deaf = words.count < Self.deafThreshold
        var remaining = Self.basePasses.filter { !$0.carriesTiming }
        if deaf {
            remaining += Self.smallPasses
            expected += Double(Self.smallPasses.count)
            Self.log.info("only \(words.count) words from base; escalating to the larger model")
        }

        for pass in remaining {
            let heard = try await transcribe(audio, pass: pass)
                .flatMap { $0.text.split(whereSeparator: \.isWhitespace).map(String.init) }
            for pair in WordAlignment.align(heard, timingText)
            where lexicon.isProfane(heard[pair.detectionIndex], tiers: tiers) {
                hits.insert(pair.timingIndex)
            }
            onProgress(step())
        }

        if hits.isEmpty { return Result(words: words, hits: hits, refined: false) }

        // The words are right but the times are not; a short second look fixes that. Done here,
        // while the model this needs is still the one that is loaded.
        let refiner = TimingRefiner(lexicon: lexicon) { [weak self] samples in
            guard let self else { return [] }
            return try await self.transcribeSnippet(samples, model: timingPass.model)
        }
        var retimed = words
        var everyRunRefined = true
        for (run, refined) in try await refiner.refine(
            audio: audio, words: words, hits: hits, tiers: tiers
        ) {
            guard let refined else {
                everyRunRefined = false
                continue
            }
            for index in run {
                retimed[index].startUs = min(
                    max(retimed[index].startUs, refined.startUs), refined.endUs
                )
                retimed[index].endUs = min(
                    max(retimed[index].endUs, refined.startUs), refined.endUs
                )
            }
            retimed[run.lowerBound].startUs = refined.startUs
            retimed[run.upperBound].endUs = refined.endUs
        }
        return Result(words: retimed, hits: hits, refined: everyRunRefined)
    }

    private func transcribe(
        _ audio: AudioTrackDecoder.DecodedAudio,
        pass: Pass
    ) async throws -> [WhisperContext.Segment] {
        let samples = try PcmOps.forRecognition(
            audio.samples,
            channelCount: audio.channelCount,
            sampleRate: audio.sampleRate,
            speed: pass.speed
        )
        return try await transcribeSnippet(
            samples, model: pass.model, noTimestamps: !pass.carriesTiming
        )
    }

    private func transcribeSnippet(
        _ samples: [Float],
        model: CensorModels.Model,
        noTimestamps: Bool = false
    ) async throws -> [WhisperContext.Segment] {
        let context = try await context(for: model)
        do {
            return try await context.transcribe(samples: samples, noTimestamps: noTimestamps)
        } catch let error as WhisperContext.TranscriptionFailedError {
            throw RecognitionFailedError(message: error.message)
        }
    }

    /// Models are loaded one at a time and the previous one dropped.
    ///
    /// Base and small together are a quarter of a gigabyte of weights; holding both while a video
    /// decode is also in memory is how this gets killed. The passes are ordered so each model is
    /// used for everything it is needed for before the next is loaded.
    private func context(for model: CensorModels.Model) async throws -> WhisperContext {
        let url = models.file(for: model)
        if let loaded, loadedFrom == url { return loaded }
        if let loaded { await loaded.close() }
        loaded = nil
        guard models.isInstalled(model) else {
            throw RecognitionFailedError(message: "\(model.fileName) is not on the phone")
        }
        do {
            let context = try WhisperContext.load(model: url)
            loaded = context
            loadedFrom = url
            return context
        } catch {
            throw RecognitionFailedError(message: "Could not load \(model.fileName)")
        }
    }

    func close() async {
        if let loaded { await loaded.close() }
        loaded = nil
        loadedFrom = nil
    }

    private struct Pass {
        var model: CensorModels.Model
        var speed: Float
        /// Exactly one pass carries timings; the others only contribute words.
        var carriesTiming: Bool
    }

    /// Below this many words, base is taken to have missed the speech rather than to have heard a
    /// quiet clip, and the larger model is brought in.
    private static let deafThreshold = 8

    /// Base carries the clock: it is the quicker of the two and the one measured to transcribe
    /// swearing most faithfully — on the operator's own clip, small wrote "Ama ne kodumu" for the
    /// phrase base heard correctly.
    private static let basePasses: [Pass] = [
        Pass(model: .whisperBase, speed: 1, carriesTiming: true),
        Pass(model: .whisperBase, speed: 1, carriesTiming: false),
        Pass(model: .whisperBase, speed: 0.75, carriesTiming: false)
    ]

    /// Run only when base came back with almost nothing.
    private static let smallPasses: [Pass] = [
        Pass(model: .whisperSmall, speed: 1, carriesTiming: false),
        Pass(model: .whisperSmall, speed: 0.75, carriesTiming: false)
    ]
}

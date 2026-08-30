import AVFoundation
import Foundation

/// Everything the export needs to know about censoring one video: where the swearing is, and the
/// audio to put there instead.
struct CensorPlan {
    var sampleRate: Int
    var channelCount: Int
    /// Frames in the source audio, as decoded.
    var sourceFrameCount: Int64
    var windows: [CensorWindow]
    var patches: [PcmPatch]

    var isEmpty: Bool { patches.isEmpty }

    static let nothing = CensorPlan(
        sampleRate: 0, channelCount: 0, sourceFrameCount: 0, windows: [], patches: []
    )
}

/// Works out what to censor in a video, and renders the audio that will replace it.
///
/// The stages are deliberately sequential and each is closed before the next begins. Two speech
/// models and a separation model together are close to a third of a gigabyte of weights, and
/// holding them alongside a decoded audio track is how this gets killed on a phone.
///
/// Nothing here falls back to leaving the audio alone. Every failure throws, because the quiet
/// alternative is an export with the swearing still in it.
struct AudioCensor {

    struct CensorFailedError: Error {
        var message: String
        var underlying: Error?
    }

    let models: CensorModels

    /// Decodes `input`, finds the swearing, and renders the replacement audio.
    ///
    /// Returns an empty plan when there is nothing to do — no audio track, or no swearing — which
    /// the caller treats as "leave this video alone" rather than as a failure.
    func analyze(
        input: URL,
        tiers: Set<ProfanityLexicon.Tier>,
        onProgress: @escaping (Int) -> Void
    ) async throws -> CensorPlan {
        do {
            return try await analyzeOrThrow(input: input, tiers: tiers, onProgress: onProgress)
        } catch let error as CensorFailedError {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw CensorFailedError(message: "Censoring the audio failed", underlying: error)
        }
    }

    private func analyzeOrThrow(
        input: URL,
        tiers: Set<ProfanityLexicon.Tier>,
        onProgress: @escaping (Int) -> Void
    ) async throws -> CensorPlan {
        guard models.allInstalled else {
            throw CensorFailedError(
                message: "The censor models are not on the phone yet", underlying: nil
            )
        }

        guard let audio = try await AudioTrackDecoder().decode(url: input, onProgress: { fraction in
            onProgress(Self.band(Self.decodeFrom, Self.decodeTo, fraction))
        }) else { return .nothing }
        if audio.frameCount == 0 { return .nothing }

        let recognizer = SpeechRecognizer(models: models)
        let found = try await recognizer.findProfanity(audio: audio, tiers: tiers) { fraction in
            onProgress(Self.band(Self.recogniseFrom, Self.recogniseTo, fraction))
        }
        await recognizer.close()

        if found.hits.isEmpty { return .nothing }

        // A second look at a few seconds around each hit places it to within a frame or two, so
        // the beep can be tight. Where that could not be confirmed the rough timing stands, and
        // with it a window wide enough to cover being a second out.
        let windows = CensorWindows.build(
            words: found.words,
            hits: found.hits,
            durationUs: audio.durationUs,
            shiftAllowanceUs: found.refined
                ? CensorWindows.residualAllowance
                : CensorWindows.shiftAllowance
        )
        if windows.isEmpty { return .nothing }

        let separator = try VoiceSeparator()
        let patches = try BeepPatcher.render(
            audio: audio, windows: windows, separator: separator
        ) { fraction in
            onProgress(Self.band(Self.separateFrom, Self.separateTo, fraction))
        }

        onProgress(Self.separateTo)
        return CensorPlan(
            sampleRate: audio.sampleRate,
            channelCount: audio.channelCount,
            sourceFrameCount: audio.frameCount,
            windows: windows,
            patches: patches
        )
    }

    private static func band(_ from: Int, _ to: Int, _ fraction: Double) -> Int {
        from + min(max(0, Int(Double(to - from) * fraction)), to - from)
    }

    // Recognition dominates: several passes over the whole track against one decode and a few
    // seconds of separation.
    private static let decodeFrom = 0
    private static let decodeTo = 8
    private static let recogniseFrom = 8
    private static let recogniseTo = 88
    private static let separateFrom = 88
    private static let separateTo = 100
}

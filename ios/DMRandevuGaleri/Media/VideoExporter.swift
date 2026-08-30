import AVFoundation
import CoreImage
import Foundation

/// Re-encodes a video with whatever ``ExportOptions`` asks for: faces mosaicked, licence plates
/// mosaicked, the account handle drifting across the picture, or any combination.
///
/// Nothing here ever falls back to handing over the untouched video when something was asked for —
/// a silent unprotected export is exactly the failure these options exist to prevent, so a failed
/// pass throws instead.
final class VideoExporter {

    /// Finds the swearing and renders the audio that replaces it.
    let audioCensor: AudioCensor

    init(audioCensor: AudioCensor) {
        self.audioCensor = audioCensor
    }

    enum Result {
        /// `url` is the re-encoded video. `blurred` is what the scan actually covered, or nil when
        /// no blurring was asked for; it is worth handing back rather than re-running, since a
        /// second scan would not agree with the first in every detail.
        case exported(url: URL, blurred: BlurTimeline?, censored: [CensorWindow]? = nil)

        /// Nothing to change, so the input was left alone — the caller delivers it as-is.
        case nothingToDo
    }

    struct ExportFailedError: LocalizedError {
        let message: String
        let underlying: Error?

        var errorDescription: String? {
            underlying.map { "\(message): \($0.localizedDescription)" } ?? message
        }
    }

    /// Shared by every plate scan: a Core Image context is expensive to build and safe to reuse.
    private let context: CIContext = {
        if let device = MTLCreateSystemDefaultDevice() {
            return CIContext(mtlDevice: device)
        }
        return CIContext()
    }()

    /// Applies `options` to `input`, writing to `output`. `onProgress` reports 0…100. Cancelling
    /// the calling task stops the export and removes `output`.
    ///
    /// Every failure surfaces as ``ExportFailedError`` so callers have one thing to catch and no
    /// way to mistake a broken export for a finished one.
    func export(
        input: URL,
        output: URL,
        options: ExportOptions,
        onProgress: @escaping (Int) -> Void
    ) async throws -> Result {
        if options.changesNothing { return .nothingToDo }

        var finders: [RegionFinder] = []
        if options.blurFaces { finders.append(FaceFinder()) }
        if options.blurPlates {
            guard let plates = PlateFinder(fast: options.fastPlates, context: context) else {
                throw ExportFailedError(message: "Plate detector missing", underlying: nil)
            }
            finders.append(plates)
        }

        // The two analysis passes share the run-up to the encode. Each takes the whole of it when
        // it is the only one asked for, so switching the censor off leaves the old numbers alone.
        let scanShare = finders.isEmpty
            ? 0
            : (options.censorAudio ? Self.splitShare : Self.scanShare)
        let censorFloor = options.censorAudio
            ? (finders.isEmpty ? Self.scanShare : Self.scanShare + Self.splitShare)
            : scanShare

        var blurred: BlurTimeline?
        if !finders.isEmpty {
            blurred = try await wrapping("Scanning for faces and plates failed") {
                try await RegionScanner().scan(url: input, finders: finders) { fraction in
                    onProgress(Int(fraction * Double(scanShare)))
                }
            }
        }

        var plan: CensorPlan?
        if options.censorAudio {
            var tiers: Set<ProfanityLexicon.Tier> = [.profanity]
            if options.censorInsults { tiers.insert(.insult) }
            plan = try await wrapping("Censoring the audio failed") {
                try await audioCensor.analyze(input: input, tiers: tiers) { percent in
                    onProgress(scanShare + percent * (censorFloor - scanShare) / 100)
                }
            }
        }

        // An empty timeline means nothing to cover; running the pass anyway would re-encode the
        // whole video to change nothing.
        let coverage = (blurred?.isEmpty == false) ? blurred : nil
        let censoring = (plan?.isEmpty == false) ? plan : nil
        if coverage == nil && options.watermarkHandle == nil && censoring == nil {
            return .nothingToDo
        }

        let source = AVURLAsset(url: input)
        let composition = try await wrapping("Building the filter pass failed") {
            try await VideoFilterPipeline.composition(
                for: source,
                blur: coverage,
                watermark: options.watermarkHandle
            )
        }

        // With the audio replaced the export runs over a composition rather than the file, since
        // this platform gives no way to rewrite samples on their way through.
        var asset: AVAsset = source
        var censoredAudio: URL?
        if let censoring {
            let audioURL = output.deletingLastPathComponent()
                .appendingPathComponent("censored-audio.caf")
            censoredAudio = audioURL
            asset = try await wrapping("Censoring the audio failed") {
                try await CensoredAudioTrack.write(plan: censoring, from: input, to: audioURL)
                return try await Self.compose(video: source, audio: audioURL)
            }
        }
        defer { censoredAudio.map { try? FileManager.default.removeItem(at: $0) } }

        try await wrapping("Export failed") {
            try await run(
                asset: asset,
                composition: composition,
                to: output,
                floor: censorFloor,
                onProgress: onProgress
            )
        }
        return .exported(url: output, blurred: blurred, censored: plan?.windows)
    }

    /// The original picture with the censored audio in place of its own.
    private static func compose(video: AVURLAsset, audio: URL) async throws -> AVAsset {
        let composition = AVMutableComposition()
        let range = CMTimeRange(start: .zero, duration: try await video.load(.duration))

        if let track = try await video.loadTracks(withMediaType: .video).first,
           let into = composition.addMutableTrack(
               withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid
           ) {
            try into.insertTimeRange(range, of: track, at: .zero)
            // Carried across explicitly: without it a portrait clip composes as landscape.
            into.preferredTransform = try await track.load(.preferredTransform)
        }

        let censored = AVURLAsset(url: audio)
        if let track = try await censored.loadTracks(withMediaType: .audio).first,
           let into = composition.addMutableTrack(
               withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid
           ) {
            let audioRange = CMTimeRange(
                start: .zero,
                duration: min(range.duration, try await censored.load(.duration))
            )
            try into.insertTimeRange(audioRange, of: track, at: .zero)
        }
        return composition
    }

    /// Runs the encode. Audio is re-encoded rather than copied across, which the export presets
    /// give no way around; at these presets that is inaudible, and Instagram re-encodes again on
    /// upload regardless.
    private func run(
        asset: AVAsset,
        // Nil when only the audio is being censored: the picture then passes through untouched
        // and there is no filter pass to install.
        composition: AVMutableVideoComposition?,
        to output: URL,
        floor: Int,
        onProgress: @escaping (Int) -> Void
    ) async throws {
        // The session refuses to write over anything, including a file a cancelled run left.
        try? FileManager.default.removeItem(at: output)

        guard let session = AVAssetExportSession(
            asset: asset,
            presetName: AVAssetExportPresetHighestQuality
        ) else {
            throw ExportFailedError(message: "No encoder for this video", underlying: nil)
        }
        session.videoComposition = composition

        let monitor = Task {
            for await state in session.states(updateInterval: Self.progressInterval) {
                guard case .exporting(let progress) = state else { continue }
                let share = Double(100 - floor) * progress.fractionCompleted
                onProgress(floor + Int(share))
            }
        }
        defer { monitor.cancel() }

        do {
            try await session.export(to: output, as: .mp4)
        } catch {
            try? FileManager.default.removeItem(at: output)
            throw error
        }

        let size = (try? FileManager.default.attributesOfItem(atPath: output.path)[.size] as? Int) ?? 0
        guard FileManager.default.fileExists(atPath: output.path), size > 0 else {
            throw ExportFailedError(message: "Export produced no file", underlying: nil)
        }
        onProgress(100)
    }

    /// Turns anything that is not a cancellation into the one error type callers handle.
    private func wrapping<T>(_ message: String, _ work: () async throws -> T) async throws -> T {
        do {
            return try await work()
        } catch is CancellationError {
            throw CancellationError()
        } catch let failure as ExportFailedError {
            throw failure
        } catch {
            throw ExportFailedError(message: message, underlying: error)
        }
    }

    /// Share of the progress bar the scan gets; the export takes the rest.
    private static let scanShare = 35

    /// What each analysis pass gets when the picture and the audio are both being looked at.
    private static let splitShare = 20
    private static let progressInterval: TimeInterval = 0.5
}

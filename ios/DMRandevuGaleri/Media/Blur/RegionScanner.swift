// AVAssetTrack and friends predate Sendable and are safe to hand to the decode queue, which is
// the only place they are touched.
@preconcurrency import AVFoundation
import CoreImage
import CoreVideo
import Foundation
import ImageIO

/// First half of the blur: walks the video and asks each ``RegionFinder`` what to cover, roughly
/// every ``BlurTimeline/samplePeriodMS``, producing the timeline the renderer later reads.
///
/// Finders share the decode. Faces and plates want the same frames, and decoding the video twice
/// to ask two questions about it would double the wait for nothing.
///
/// The video is read **straight through, once**. The obvious implementation — seek to each sample
/// instant and grab that frame — costs an exact seek per sample, and an exact seek has to decode
/// from the preceding keyframe every time; measured on Android with a 47 s clip that was 39 s of
/// the 65 s pass. Reading sequentially and simply dropping the frames between samples turns that
/// into one linear decode, which the hardware does in a couple of seconds.
final class RegionScanner: Sendable {

    struct NoVideoTrackError: Error {}

    /// Where the work happens. `copyNextSampleBuffer` blocks, and a whole video's worth of that
    /// has no business sitting on one of Swift concurrency's few cooperative threads.
    private let queue = DispatchQueue(label: "com.dmrandevu.gallery.scan", qos: .userInitiated)

    /// Scans `url` with every finder and returns one timeline covering all of them.
    /// `onProgress` is called with 0…1.
    func scan(
        url: URL,
        finders: [RegionFinder],
        onProgress: @escaping (Double) -> Void
    ) async throws -> BlurTimeline {
        let asset = AVURLAsset(url: url)
        let tracks = try await asset.loadTracks(withMediaType: .video)
        guard let track = tracks.first else { throw NoVideoTrackError() }

        let duration = try await asset.load(.duration)
        guard let durationUS = duration.microseconds, durationUS > 0 else { return .empty() }

        let transform = try await track.load(.preferredTransform)
        let orientation = Self.orientation(from: transform)

        return try await withCheckedThrowingContinuation { continuation in
            queue.async {
                do {
                    let timeline = try self.read(
                        asset: asset,
                        track: track,
                        orientation: orientation,
                        durationUS: durationUS,
                        finders: finders,
                        onProgress: onProgress
                    )
                    continuation.resume(returning: timeline)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private func read(
        asset: AVURLAsset,
        track: AVAssetTrack,
        orientation: CGImagePropertyOrientation,
        durationUS: Int64,
        finders: [RegionFinder],
        onProgress: (Double) -> Void
    ) throws -> BlurTimeline {
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(
            track: track,
            // BGRA rather than the decoder's native YUV: both Vision and Core Image take it
            // directly, and the conversion happens in the same hardware pass as the decode.
            outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        )
        // Nothing outlives the loop iteration that reads it, so there is no reason to copy.
        output.alwaysCopiesSampleData = false
        reader.add(output)
        guard reader.startReading() else {
            throw reader.error ?? NoVideoTrackError()
        }
        defer { reader.cancelReading() }

        // One builder per finder: chaining a face box onto a plate track because they happened to
        // overlap would drag one region's blur onto the other's path.
        let builders = finders.map { _ in BlurTimeline.Builder(videoDurationUS: durationUS) }

        // Decode once at the finest rate anyone asked for, and give each finder its own frames
        // out of that.
        let periodsUS = finders.map { $0.samplePeriodMS * 1_000 }
        var nextDueUS = [Int64](repeating: 0, count: finders.count)
        let stepUS = periodsUS.min() ?? BlurTimeline.samplePeriodMS * 1_000
        var nextSampleUS: Int64 = 0

        while let sample = output.copyNextSampleBuffer() {
            if Task.isCancelled { throw CancellationError() }

            guard let presentationUS = CMSampleBufferGetPresentationTimeStamp(sample).microseconds,
                  presentationUS >= nextSampleUS,
                  let buffer = CMSampleBufferGetImageBuffer(sample) else { continue }
            nextSampleUS = presentationUS + stepUS

            // Only valid until the reader takes the buffer back, so detection has to finish first.
            let frame = ScannedFrame(pixelBuffer: buffer, orientation: orientation)
            for (index, finder) in finders.enumerated() {
                guard presentationUS >= nextDueUS[index] else { continue }
                nextDueUS[index] = presentationUS + periodsUS[index]
                // Deliberately not swallowed: a finder that cannot look at a frame has not
                // established that the frame is clear, and treating that as "nothing to cover"
                // is how an unprotected video gets out.
                let regions = try finder.regions(in: frame)
                builders[index].addSample(timeUS: presentationUS, regions: regions)
            }
            onProgress(min(max(Double(presentationUS) / Double(durationUS), 0), 1))
        }

        if reader.status == .failed, let error = reader.error { throw error }
        return .of(builders.map { $0.build() })
    }

    /// The container's rotation, as the orientation tag Vision and Core Image both speak.
    static func orientation(from transform: CGAffineTransform) -> CGImagePropertyOrientation {
        switch (transform.a, transform.b, transform.c, transform.d) {
        case (0, 1, -1, 0): return .right   // 90° clockwise
        case (0, -1, 1, 0): return .left    // 90° anticlockwise
        case (-1, 0, 0, -1): return .down   // upside down
        default: return .up
        }
    }
}

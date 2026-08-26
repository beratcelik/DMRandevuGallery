import AVFoundation
import CoreImage
import Foundation

/// The one place a frame is filtered, used by the export and by the player alike.
///
/// Deliberately shared rather than reimplemented per surface: a preview that is a
/// re-implementation is a preview that can quietly stop matching what actually gets written to
/// the file.
enum VideoFilterPipeline {

    /// A composition that applies `blur` and `watermark` to every frame of `asset`, or nil when
    /// neither was asked for.
    ///
    /// The composition works for playback and for export without change — an `AVPlayerItem` and an
    /// `AVAssetExportSession` both take one.
    static func composition(
        for asset: AVAsset,
        blur: BlurTimeline?,
        watermark: String?
    ) async throws -> AVMutableVideoComposition? {
        let mosaic = try blur.flatMap { timeline -> MosaicRenderer? in
            timeline.isEmpty ? nil : try MosaicRenderer(timeline: timeline)
        }
        let mark = watermark.map(WanderingWatermark.init(handle:))
        guard mosaic != nil || mark != nil else { return nil }

        let composition = try await AVMutableVideoComposition.videoComposition(
            with: asset
        ) { request in
            // `sourceImage` already has the container's rotation applied, so a portrait clip
            // arrives portrait and the whole pipeline works in the upright frame — the same space
            // the detectors reported their boxes in.
            let timeUS = request.compositionTime.microseconds ?? 0
            var image = request.sourceImage
            if let mosaic { image = mosaic.apply(to: image, at: timeUS) }
            if let mark { image = mark.apply(to: image, at: timeUS) }
            request.finish(with: image, context: nil)
        }

        // The kernel is plain SDR maths and cannot read HDR input meaningfully. Pinning the
        // working space to Rec. 709 tone-maps anything wider on the way in, which is a no-op for
        // the SDR videos Instagram actually delivers.
        composition.colorPrimaries = AVVideoColorPrimaries_ITU_R_709_2
        composition.colorTransferFunction = AVVideoTransferFunction_ITU_R_709_2
        composition.colorYCbCrMatrix = AVVideoYCbCrMatrix_ITU_R_709_2
        return composition
    }

    /// A composition for playback whose watermark can be switched on and off without rebuilding
    /// it. See ``WatermarkSwitch`` for why that matters.
    static func previewComposition(
        for asset: AVAsset,
        watermark: WatermarkSwitch
    ) async throws -> AVMutableVideoComposition {
        let composition = try await AVMutableVideoComposition.videoComposition(
            with: asset
        ) { request in
            guard let mark = watermark.watermark() else {
                request.finish(with: request.sourceImage, context: nil)
                return
            }
            let timeUS = request.compositionTime.microseconds ?? 0
            request.finish(with: mark.apply(to: request.sourceImage, at: timeUS), context: nil)
        }
        composition.colorPrimaries = AVVideoColorPrimaries_ITU_R_709_2
        composition.colorTransferFunction = AVVideoTransferFunction_ITU_R_709_2
        composition.colorYCbCrMatrix = AVVideoYCbCrMatrix_ITU_R_709_2
        return composition
    }
}

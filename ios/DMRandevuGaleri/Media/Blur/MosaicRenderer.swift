import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation

/// Pixelates whatever the ``BlurTimeline`` says is a face or a plate at the frame being drawn, and
/// leaves the rest of the picture untouched.
///
/// One Metal kernel does the work, invoked once per region with that region's rectangle as its
/// destination — so a frame with two plates shades two small patches, not two full frames.
///
/// Unchecked rather than plainly `Sendable` only because `CIKernel` has no such annotation. Every
/// stored property here is a `let` and nothing is mutated after `init`, which matters: the video
/// composition calls ``apply(to:at:)`` from several threads at once.
final class MosaicRenderer: @unchecked Sendable {

    struct KernelMissingError: Error {}

    private let kernel: CIKernel
    private let timeline: BlurTimeline

    init(timeline: BlurTimeline) throws {
        guard let url = Bundle.main.url(forResource: "default", withExtension: "metallib"),
              let data = try? Data(contentsOf: url) else {
            throw KernelMissingError()
        }
        kernel = try CIKernel(functionName: "mosaicPatch", fromMetalLibraryData: data)
        self.timeline = timeline
    }

    /// `image` with everything the timeline covers at `timeUS` mosaicked out.
    func apply(to image: CIImage, at timeUS: Int64) -> CIImage {
        let frame = image.extent
        guard frame.width > 0, frame.height > 0 else { return image }

        guard let regions = timeline.regions(at: timeUS) else {
            // More regions than the renderer takes. Everything goes rather than any of them being
            // dropped, which is the safe direction to be wrong in.
            return wholeFrame(image)
        }

        var result = image
        for region in regions {
            guard let patch = self.patch(over: image, region: region, frame: frame) else { continue }
            result = patch.composited(over: result)
        }
        return result
    }

    /// One region's worth of mosaic, as a small premultiplied image sitting where the region is.
    private func patch(over image: CIImage, region: BlurTimeline.Placed, frame: CGRect) -> CIImage? {
        let halfWidth = region.box.width / 2 * frame.width
        let halfHeight = region.box.height / 2 * frame.height
        guard halfWidth > 0, halfHeight > 0 else { return nil }

        // The timeline speaks the detectors' y-down image space; Core Image samples bottom-up.
        // This is the one place the two conventions meet — if the blur ever comes out vertically
        // mirrored, this line is what to remove.
        let centre = CGPoint(
            x: frame.minX + region.box.midX * frame.width,
            y: frame.minY + (1 - region.box.midY) * frame.height
        )

        // Seven cells across the region's longer side. Taking the longer side rather than each
        // axis separately keeps the cells square, which Core Image's snapping needs, and never
        // ends up finer than the Android shader's per-axis grid.
        let cell = max(max(halfWidth, halfHeight) * 2 / Self.mosaicCells, Self.minimumCell)

        let rect = CGRect(
            x: centre.x - halfWidth,
            y: centre.y - halfHeight,
            width: halfWidth * 2,
            height: halfHeight * 2
        ).intersection(frame)
        guard !rect.isNull, rect.width >= 1, rect.height >= 1 else { return nil }

        return kernel.apply(
            extent: rect,
            // Snapping to the cell grid reaches up to a cell outside the pixel being shaded.
            roiCallback: { _, destination in destination.insetBy(dx: -cell, dy: -cell) },
            arguments: [
                image,
                CIVector(x: centre.x, y: centre.y),
                CIVector(x: halfWidth, y: halfHeight),
                CIVector(x: cell, y: cell),
                region.shape == .rectangle ? 1.0 : 0.0
            ]
        )
    }

    /// The fallback for a frame with more regions than the renderer takes.
    private func wholeFrame(_ image: CIImage) -> CIImage {
        let filter = CIFilter.pixellate()
        filter.inputImage = image
        filter.center = CGPoint(x: image.extent.midX, y: image.extent.midY)
        filter.scale = Float(max(image.extent.width / Self.wholeFrameCells, Self.minimumCell))
        return (filter.outputImage ?? image).cropped(to: image.extent)
    }

    /// Mosaic cells across a region. Coarse enough that nothing survives.
    private static let mosaicCells: CGFloat = 7

    /// Cells across the frame when there are too many regions to handle individually.
    private static let wholeFrameCells: CGFloat = 24

    /// Below this a "mosaic" is just the picture again.
    private static let minimumCell: CGFloat = 6
}

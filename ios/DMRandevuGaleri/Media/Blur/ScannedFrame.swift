import CoreImage
import CoreVideo
import Foundation
import ImageIO

/// One decoded frame, offered in whichever form a ``RegionFinder`` needs.
///
/// Only valid until the reader hands its buffer back, so a finder must be done with it before it
/// returns.
final class ScannedFrame {

    /// The decoder's own buffer, still in storage orientation. Vision takes this plus
    /// ``orientation`` and answers in the upright space, so nothing is copied for the face pass.
    let pixelBuffer: CVPixelBuffer

    /// How the container says the frame should be turned to be the right way up.
    let orientation: CGImagePropertyOrientation

    init(pixelBuffer: CVPixelBuffer, orientation: CGImagePropertyOrientation) {
        self.pixelBuffer = pixelBuffer
        self.orientation = orientation
    }

    /// The frame the right way up, for a detector that has to be handed a picture rather than a
    /// buffer and an orientation.
    ///
    /// Built at most once per frame however many finders ask for it. Core Image is lazy, so this
    /// is only a recipe until something renders it.
    var upright: CIImage {
        if let cached = cachedUpright { return cached }
        let image = CIImage(cvPixelBuffer: pixelBuffer).oriented(orientation)
        cachedUpright = image
        return image
    }

    private var cachedUpright: CIImage?
}

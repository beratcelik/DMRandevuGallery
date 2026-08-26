import CoreGraphics
import Foundation
import Vision

/// Finds faces.
///
/// Vision's own detector replaces ML Kit here. It is the same class of thing — a bundled,
/// on-device face detector — and it needs no model file, so the plate model is the only weight
/// the app carries.
final class FaceFinder: RegionFinder {

    let samplePeriodMS = BlurTimeline.samplePeriodMS

    func regions(in frame: ScannedFrame) throws -> [BlurTimeline.Region] {
        let request = VNDetectFaceRectanglesRequest()
        // Revision 3 is the one that finds faces turned away from the camera. On real dashcam
        // footage the older revision walks straight past them, and a missed face is the one
        // failure this feature cannot have.
        request.revision = VNDetectFaceRectanglesRequestRevision3

        // Native size, and the buffer itself: a head is far bigger than the detector's floor
        // already, so there is nothing to gain from handing it a bigger picture. Vision is told
        // the orientation and answers in the upright space the timeline works in.
        let handler = VNImageRequestHandler(
            cvPixelBuffer: frame.pixelBuffer,
            orientation: frame.orientation,
            options: [:]
        )
        try handler.perform([request])

        return (request.results ?? []).map { face in
            BlurTimeline.Region(box: flippedToTopLeft(face.boundingBox), shape: .ellipse)
        }
    }
}

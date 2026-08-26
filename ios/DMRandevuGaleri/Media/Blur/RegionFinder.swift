import CoreGraphics
import CoreVideo
import Foundation
import ImageIO

/// Something that points at the parts of a frame that should not leave the phone readable.
///
/// `Sendable` because ``RegionScanner`` hands finders to its own decode queue. A finder may keep
/// scratch state, but the scanner drives them one frame at a time from that single queue, so the
/// requirement is only that a finder never be used from two places at once.
protocol RegionFinder: AnyObject, Sendable {

    /// How often this finder wants to be shown a frame. Costed per finder because they are not
    /// equally reliable: a face is found in most frames it appears in, a small plate in a minority
    /// of them, so the plate pass needs more chances to make up the difference.
    var samplePeriodMS: Int64 { get }

    /// Regions to cover in `frame`, normalized to the upright frame and y-down.
    func regions(in frame: ScannedFrame) throws -> [BlurTimeline.Region]
}

/// Vision reports boxes normalized with the origin at the **bottom** left, which is the opposite
/// of the space ``BlurTimeline`` works in. Flipping here rather than at the far end keeps every
/// timeline in one convention no matter which finder filled it.
func flippedToTopLeft(_ box: CGRect) -> CGRect {
    CGRect(x: box.minX, y: 1 - box.maxY, width: box.width, height: box.height)
}

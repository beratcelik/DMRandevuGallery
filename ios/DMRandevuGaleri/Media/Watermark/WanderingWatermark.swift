import CoreImage
import Foundation
import UIKit

/// Burns the account handle into the video on a slow, never-quite-repeating path around the frame.
///
/// A corner watermark is one crop away from gone. This one visits the whole frame over a few
/// minutes, so there is no safe crop, while drifting slowly enough to read and to ignore. The path
/// is two sine waves whose periods do not divide into each other, which wanders without ever
/// jumping — a genuinely random position each frame would strobe and be unreadable.
///
/// Unchecked rather than plainly `Sendable` only because `CIImage` has no such annotation. The one
/// stored property is a `let` holding an immutable image, which matters: the video composition
/// calls ``apply(to:at:)`` from several threads at once.
final class WanderingWatermark: @unchecked Sendable {

    private let label: CIImage

    init(handle: String) {
        label = Self.render(handle: handle)
    }

    /// `image` with the handle drifting over it at `timeUS`.
    func apply(to image: CIImage, at timeUS: Int64) -> CIImage {
        let frame = image.extent
        let size = label.extent.size
        guard frame.width > 0, frame.height > 0, size.width > 0, size.height > 0 else {
            return image
        }

        let scale = min(Self.widthFraction * frame.width / size.width, 1)
        let width = size.width * scale
        let height = size.height * scale

        // How much of the frame the label covers, as a fraction of the half-frame the path is
        // measured in. Subtracting it keeps the whole label on screen.
        let reachX = max(1 - width / frame.width - Self.margin, 0)
        let reachY = max(1 - height / frame.height - Self.margin, 0)

        let seconds = Double(timeUS) / 1_000_000
        let offsetX = CGFloat(sin(seconds * Self.tau / Self.periodXSeconds)) * reachX
        let offsetY = CGFloat(sin(seconds * Self.tau / Self.periodYSeconds + Self.phase)) * reachY

        let centreX = frame.midX + offsetX * frame.width / 2
        let centreY = frame.midY + offsetY * frame.height / 2

        let placed = label
            .transformed(by: CGAffineTransform(scaleX: scale, y: scale))
            .transformed(
                by: CGAffineTransform(
                    translationX: centreX - width / 2,
                    y: centreY - height / 2
                )
            )
        return placed.composited(over: image)
    }

    /// The label, drawn once. Rendered large and scaled down per frame rather than redrawn, so a
    /// video's worth of frames costs one text layout.
    private static func render(handle: String) -> CIImage {
        var text = handle
        if text.hasPrefix("@") { text.removeFirst() }
        text = "@" + text

        let font = UIFont.systemFont(ofSize: pointSize, weight: .semibold)
        // The alpha Android applies to the whole overlay is baked in here instead: it is a
        // constant, and a premultiplied image cannot have its alpha scaled afterwards without
        // also scaling the colour.
        let attributes: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: UIColor(white: 1, alpha: alpha)
        ]
        let measured = (text as NSString).size(withAttributes: attributes)
        let canvas = CGSize(
            width: (measured.width + padding * 2).rounded(.up),
            height: (measured.height + padding).rounded(.up)
        )

        let format = UIGraphicsImageRendererFormat.preferred()
        format.opaque = false
        format.scale = 1
        let drawn = UIGraphicsImageRenderer(size: canvas, format: format).image { _ in
            // A translucent band behind the glyphs: white alone disappears against a bright sky.
            UIColor(white: 0, alpha: backdropAlpha * alpha).setFill()
            UIBezierPath(
                roundedRect: CGRect(origin: .zero, size: canvas),
                cornerRadius: canvas.height * 0.18
            ).fill()
            (text as NSString).draw(
                at: CGPoint(x: padding, y: padding / 2),
                withAttributes: attributes
            )
        }

        return CIImage(cgImage: drawn.cgImage!)
    }

    /// Share of the frame width the label spans. Big enough to read after Instagram's re-encode.
    private static let widthFraction: CGFloat = 0.34

    private static let alpha: CGFloat = 0.62

    /// The backdrop's own alpha, before the overlay's is applied on top of it.
    private static let backdropAlpha: CGFloat = 0.45

    /// Keeps the label off the very edge, where players and crops eat into the frame.
    private static let margin: CGFloat = 0.04

    /// Drawn at this size and scaled down, so the glyphs stay crisp on a 4K frame.
    private static let pointSize: CGFloat = 96
    private static let padding: CGFloat = 22

    // Coprime periods, so horizontal and vertical drift stay out of step and the path does not
    // settle into a short loop. Slow enough to sit still under the eye — a full sweep across the
    // frame takes about a quarter of a minute — while a clip of any length still sees the label
    // move well away from wherever it started.
    private static let periodXSeconds: Double = 31
    private static let periodYSeconds: Double = 23
    private static let phase: Double = 1.3
    private static let tau: Double = 2 * .pi
}

import CoreGraphics
import CoreImage
import CoreML
import CoreVideo
import Foundation

/// Finds licence plates with a detector trained to spot them, rather than by trying to read them.
///
/// The model is `morsetechlab/yolov11-license-plate-detection` (nano, single class) — the same
/// weights the Android app ships as ONNX and the same ones the Trafy camera app runs, converted
/// to Core ML from the identical `.pt`. It is **AGPL-3.0**, which travels with anything it is
/// shipped in.
///
/// Reading the plate with a text recogniser was tried first on Android and could only find a plate
/// it could also read: on a 356×638 clip whose plates are about 45 px wide, that meant a couple of
/// readings across the whole video, because the characters were well under the size a recogniser
/// needs. A detector has no such floor — a plate does not have to be legible to be recognisably a
/// plate.
///
/// Unchecked `Sendable`: the model and the scratch buffer are not, and the buffer really is
/// written to on every frame. It is safe because ``RegionScanner`` runs its finders one frame at a
/// time on a single queue — two scans at once would each need their own finder.
final class PlateFinder: RegionFinder, @unchecked Sendable {

    /// What the model was exported at, and where it finds the most.
    static let defaultInputSize = 640

    /// The quicker setting. On the Android reference clip it scanned in 47 s against 78 s and
    /// covered 120 sampled moments against 179 — about 40% off the wait for a third of the plates.
    static let fastInputSize = 416

    /// The size to run at, given whether the quicker setting is wanted.
    static func inputSize(fast: Bool) -> Int { fast ? fastInputSize : defaultInputSize }

    /// Below the usual quarter, because the model was trained on international plates and is less
    /// sure of Turkish ones — the value the Trafy camera app settled on for the same model and the
    /// same footage.
    private static let confidence: Float = 0.15
    private static let maxOverlap: CGFloat = 0.45

    /// Ultralytics pads its letterbox with mid grey; the model has only ever seen that.
    private static let padding = CIColor(red: 116 / 255, green: 116 / 255, blue: 116 / 255)

    let samplePeriodMS = BlurTimeline.samplePeriodMS

    private let model: MLModel
    private let inputName: String
    private let inputSize: Int
    private let context: CIContext

    /// Reused between frames: a 640-square buffer is 1.6 MB and there is one per sample.
    private let scratch: CVPixelBuffer

    /// Everything the model needs, or nil when the bundle is missing it — the caller turns that
    /// into a failed export rather than a quietly unprotected one.
    init?(fast: Bool, context: CIContext) {
        let size = Self.inputSize(fast: fast)
        let name = "PlateDetector\(size)"
        guard let url = Bundle.main.url(forResource: name, withExtension: "mlmodelc") else {
            return nil
        }

        let configuration = MLModelConfiguration()
        // Let Core ML pick: this model runs happily on the Neural Engine, which is most of the
        // reason the plate pass is bearable on a phone at all.
        configuration.computeUnits = .all
        guard let model = try? MLModel(contentsOf: url, configuration: configuration),
              let inputName = model.modelDescription.inputDescriptionsByName.keys.first,
              let scratch = Self.makeBuffer(size: size) else {
            return nil
        }

        self.model = model
        self.inputName = inputName
        self.inputSize = size
        self.context = context
        self.scratch = scratch
    }

    func regions(in frame: ScannedFrame) throws -> [BlurTimeline.Region] {
        let letterbox = Letterbox(source: frame.upright.extent.size, size: inputSize)
        draw(frame.upright, with: letterbox)

        let input = try MLDictionaryFeatureProvider(
            dictionary: [inputName: MLFeatureValue(pixelBuffer: scratch)]
        )
        let prediction = try model.prediction(from: input)
        guard let name = prediction.featureNames.first,
              let output = prediction.featureValue(for: name)?.multiArrayValue else {
            return []
        }

        return suppressOverlaps(decode(output)).map {
            BlurTimeline.Region(box: letterbox.toFrame($0), shape: .rectangle)
        }
    }

    // MARK: - Feeding the model

    /// Scales the frame into the square the model wants, padding rather than stretching.
    private struct Letterbox {
        let scale: CGFloat
        let width: CGFloat
        let height: CGFloat
        let left: CGFloat
        let top: CGFloat

        init(source: CGSize, size: Int) {
            let square = CGFloat(size)
            scale = min(square / source.width, square / source.height)
            width = (source.width * scale).rounded(.down)
            height = (source.height * scale).rounded(.down)
            left = ((square - width) / 2).rounded(.down)
            top = ((square - height) / 2).rounded(.down)
        }

        /// A box in model pixels, back to a fraction of the original frame.
        func toFrame(_ box: CGRect) -> CGRect {
            let minX = min(max((box.minX - left) / width, 0), 1)
            let minY = min(max((box.minY - top) / height, 0), 1)
            let maxX = min(max((box.maxX - left) / width, 0), 1)
            let maxY = min(max((box.maxY - top) / height, 0), 1)
            return CGRect(x: minX, y: minY, width: max(maxX - minX, 0), height: max(maxY - minY, 0))
        }
    }

    /// Renders the upright frame into the model's buffer, centred on a field of Ultralytics grey.
    ///
    /// Core Image works bottom-up, but the letterbox is centred on both axes, so the same inset
    /// serves for top and bottom and nothing has to be flipped here. The flip that does matter is
    /// the one `CIContext` applies on the way into the buffer, which lands the picture the right
    /// way up — the same way up the model's box coordinates come back in.
    private func draw(_ image: CIImage, with letterbox: Letterbox) {
        let square = CGRect(x: 0, y: 0, width: CGFloat(inputSize), height: CGFloat(inputSize))
        let scaled = image
            // A CIImage from a decoder buffer need not start at the origin.
            .transformed(by: CGAffineTransform(translationX: -image.extent.minX, y: -image.extent.minY))
            .transformed(by: CGAffineTransform(scaleX: letterbox.scale, y: letterbox.scale))
            .transformed(by: CGAffineTransform(translationX: letterbox.left, y: letterbox.top))

        let padded = scaled.composited(over: CIImage(color: Self.padding).cropped(to: square))
        context.render(
            padded,
            to: scratch,
            bounds: square,
            colorSpace: CGColorSpace(name: CGColorSpace.sRGB)
        )
    }

    private static func makeBuffer(size: Int) -> CVPixelBuffer? {
        var buffer: CVPixelBuffer?
        let attributes: [CFString: Any] = [
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary
        ]
        let status = CVPixelBufferCreate(
            kCFAllocatorDefault,
            size,
            size,
            kCVPixelFormatType_32BGRA,
            attributes as CFDictionary,
            &buffer
        )
        return status == kCVReturnSuccess ? buffer : nil
    }

    // MARK: - Reading the model

    /// Reads the `[1, 5, anchors]` block the model produces: four box numbers and one score per
    /// anchor, the box given as centre and size in model pixels.
    private func decode(_ output: MLMultiArray) -> [CGRect] {
        guard output.dataType == .float32, output.shape.count == 3 else { return [] }
        let anchors = output.shape[2].intValue
        let channelStride = output.strides[1].intValue
        let anchorStride = output.strides[2].intValue
        let values = output.dataPointer.bindMemory(to: Float.self, capacity: output.count)

        var found: [CGRect] = []
        for anchor in 0..<anchors {
            let base = anchor * anchorStride
            guard values[base + 4 * channelStride] >= Self.confidence else { continue }
            let centreX = CGFloat(values[base])
            let centreY = CGFloat(values[base + channelStride])
            let width = CGFloat(values[base + 2 * channelStride])
            let height = CGFloat(values[base + 3 * channelStride])
            found.append(
                CGRect(
                    x: centreX - width / 2,
                    y: centreY - height / 2,
                    width: width,
                    height: height
                )
            )
        }
        return found
    }

    /// One plate wins one box: anchors near it all fire, and only the strongest is kept.
    private func suppressOverlaps(_ boxes: [CGRect]) -> [CGRect] {
        var kept: [CGRect] = []
        for box in boxes.sorted(by: { $0.width * $0.height > $1.width * $1.height }) {
            if kept.allSatisfy({ overlap($0, box) <= Self.maxOverlap }) { kept.append(box) }
            if kept.count == BlurTimeline.maxRegions { break }
        }
        return kept
    }

    private func overlap(_ a: CGRect, _ b: CGRect) -> CGFloat {
        let intersection = a.intersection(b)
        guard !intersection.isNull, intersection.width > 0, intersection.height > 0 else { return 0 }
        let shared = intersection.width * intersection.height
        return shared / (a.width * a.height + b.width * b.height - shared)
    }
}

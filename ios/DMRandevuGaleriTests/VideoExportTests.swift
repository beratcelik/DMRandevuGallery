import AVFoundation
import CoreImage
import Vision
import XCTest

@testable import DMRandevuGaleri

/// What the export actually does to a real video: are the faces gone, are the plates flattened,
/// and does an untouched export stay untouched.
///
/// These need a clip to work on. Point `DMRANDEVU_SAMPLE_VIDEO` at one, or drop `sample.mp4` in
/// the host app's Documents directory on the device; without it every test here skips rather than
/// failing, the same way the Android instrumented tests do.
final class VideoExportTests: XCTestCase {

    private let exporter = VideoExporter(
        // These exercise the picture, not the audio; the censor is never switched on.
        audioCensor: AudioCensor(models: CensorModels())
    )

    private lazy var sample: URL? = {
        if let path = ProcessInfo.processInfo.environment["DMRANDEVU_SAMPLE_VIDEO"],
           FileManager.default.fileExists(atPath: path) {
            return URL(fileURLWithPath: path)
        }
        let documents = FileManager.default
            .urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("sample.mp4")
        return FileManager.default.fileExists(atPath: documents.path) ? documents : nil
    }()

    // MARK: - Faces

    /// The detector should struggle to find in the export what it found easily in the original.
    func testFaceBlurLeavesAlmostNothingToDetect() async throws {
        #if targetEnvironment(simulator)
        // Vision answers "could not create inference context" for face detection in the
        // simulator. The plate model and the whole export path run fine there; only this one
        // needs real hardware.
        throw XCTSkip("Vision's face detector needs a device")
        #else
        let input = try sampleVideo()
        let output = try scratch("faces.mp4")

        let result = try await exporter.export(
            input: input,
            output: output,
            options: ExportOptions(blurFaces: true)
        ) { _ in }

        guard case .exported(let url, let blurred, _) = result, blurred?.isEmpty == false else {
            throw XCTSkip("No faces in the sample — use a clip with a visible face")
        }

        let before = try await faceCount(in: input)
        let after = try await faceCount(in: url)
        XCTAssertGreaterThan(before, 0, "the sample has to have faces for this to mean anything")
        XCTAssertLessThanOrEqual(
            Double(after),
            Double(before) * Self.maxFacesLeft,
            "\(after) of \(before) sampled faces survived the mosaic"
        )
        #endif
    }

    // MARK: - Plates

    /// Deliberately *not* "the detector no longer finds a plate in the export" — it still does,
    /// and should. A mosaicked plate is still plate-shaped and still sitting where plates sit;
    /// what has gone is the number. So this asks a question the export can answer on its own:
    /// inside each box the detector pointed at, has the picture gone flat? A mosaic cell is a
    /// single colour, so the fine detail plate characters are made of cannot survive one.
    func testPlateBlurFlattensThePlatesItFound() async throws {
        let input = try sampleVideo()
        let output = try scratch("plates.mp4")

        let result = try await exporter.export(
            input: input,
            output: output,
            options: ExportOptions(blurPlates: true, fastPlates: false)
        ) { _ in }

        guard case .exported(let url, let covered, _) = result, let covered, !covered.isEmpty else {
            throw XCTSkip("No plates in the sample — use a clip with a visible plate")
        }

        let before = try await detail(in: input, covered: covered)
        let after = try await detail(in: url, covered: covered)
        guard !before.isEmpty else {
            throw XCTSkip("No frame with exactly one plate to measure")
        }

        let was = before.sorted()[before.count / 2]
        let now = after.sorted()[after.count / 2]
        XCTAssertLessThan(
            now,
            was * Self.maxDetailKept,
            "plate regions kept their detail (\(was) -> \(now)) — the mosaic is missing them"
        )
    }

    // MARK: - Watermark

    func testWatermarkKeepsTheVideoIntact() async throws {
        let input = try sampleVideo()
        let output = try scratch("watermark.mp4")

        let result = try await exporter.export(
            input: input,
            output: output,
            options: ExportOptions(watermarkHandle: "trafik_cezasi")
        ) { _ in }

        guard case .exported(let url, _, _) = result else {
            return XCTFail("A watermark always changes the picture, so this must re-encode")
        }

        let original = AVURLAsset(url: input)
        let marked = AVURLAsset(url: url)
        let originalSize = try await size(of: original)
        let markedSize = try await size(of: marked)

        XCTAssertEqual(markedSize.width, originalSize.width, "frame width preserved")
        XCTAssertEqual(markedSize.height, originalSize.height, "frame height preserved")
        // Hoisted out of the assertions: XCTAssert's arguments are autoclosures, which cannot
        // await.
        let markedDuration = CMTimeGetSeconds(try await marked.load(.duration))
        let originalDuration = CMTimeGetSeconds(try await original.load(.duration))
        XCTAssertEqual(markedDuration, originalDuration, accuracy: 0.2, "duration preserved")

        let hasAudio = try await !marked.loadTracks(withMediaType: .audio).isEmpty
        let hadAudio = try await !original.loadTracks(withMediaType: .audio).isEmpty
        XCTAssertEqual(hasAudio, hadAudio, "the audio track survives the video-only pass")
    }

    /// Nothing asked for means nothing done — no re-encode, and the caller delivers the original.
    func testNoOptionsMeansNoReEncode() async throws {
        let input = try sampleVideo()
        let output = try scratch("untouched.mp4")

        let result = try await exporter.export(
            input: input,
            output: output,
            options: .none
        ) { _ in }

        guard case .nothingToDo = result else {
            return XCTFail("An empty option set must not re-encode anything")
        }
        XCTAssertFalse(
            FileManager.default.fileExists(atPath: output.path),
            "and must not write a file either"
        )
    }

    // MARK: - Measuring

    /// Faces the detector finds across the clip, sampled at a fixed rate.
    private func faceCount(in url: URL) async throws -> Int {
        var found = 0
        try await forEachSampledFrame(in: url) { image, _ in
            let request = VNDetectFaceRectanglesRequest()
            request.revision = VNDetectFaceRectanglesRequestRevision3
            try? VNImageRequestHandler(ciImage: image).perform([request])
            found += request.results?.count ?? 0
        }
        return found
    }

    /// Detail inside each plate box the export covered, frame by frame.
    private func detail(in url: URL, covered: BlurTimeline) async throws -> [Double] {
        let context = CIContext()
        var measured: [Double] = []
        try await forEachSampledFrame(in: url) { image, timeUS in
            guard let regions = covered.regions(at: timeUS), regions.count == 1,
                  let region = regions.first else { return }
            let frame = image.extent
            // Well inside the mosaic, away from its softened rim.
            let halfWidth = region.box.width / 2 * frame.width * Self.core
            let halfHeight = region.box.height / 2 * frame.height * Self.core
            let centreY = (1 - region.box.midY) * frame.height
            let rect = CGRect(
                x: region.box.midX * frame.width - halfWidth,
                y: centreY - halfHeight,
                width: halfWidth * 2,
                height: halfHeight * 2
            ).intersection(frame).integral
            guard rect.width >= 3, rect.height >= 1 else { return }
            if let step = meanNeighbourStep(image, rect, context) { measured.append(step) }
        }
        return measured
    }

    /// Mean brightness step between neighbouring pixels — what a mosaic destroys.
    private func meanNeighbourStep(_ image: CIImage, _ rect: CGRect, _ context: CIContext) -> Double? {
        let width = Int(rect.width)
        let height = Int(rect.height)
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        pixels.withUnsafeMutableBytes { buffer in
            context.render(
                image,
                toBitmap: buffer.baseAddress!,
                rowBytes: width * 4,
                bounds: rect,
                format: .RGBA8,
                colorSpace: CGColorSpace(name: CGColorSpace.sRGB)
            )
        }

        var total = 0.0
        var count = 0
        for row in 0..<height {
            for column in 0..<(width - 1) {
                let here = (row * width + column) * 4
                let next = here + 4
                total += abs(luma(pixels, here) - luma(pixels, next))
                count += 1
            }
        }
        return count == 0 ? nil : total / Double(count)
    }

    private func luma(_ pixels: [UInt8], _ offset: Int) -> Double {
        0.299 * Double(pixels[offset])
            + 0.587 * Double(pixels[offset + 1])
            + 0.114 * Double(pixels[offset + 2])
    }

    /// Walks the video at ``sampleMS``, handing each frame over upright.
    private func forEachSampledFrame(
        in url: URL,
        _ body: (CIImage, Int64) throws -> Void
    ) async throws {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .video).first else { return }
        let orientation = RegionScanner.orientation(from: try await track.load(.preferredTransform))

        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
        )
        reader.add(output)
        reader.startReading()
        defer { reader.cancelReading() }

        var nextUS: Int64 = 0
        while let buffer = output.copyNextSampleBuffer() {
            guard let timeUS = CMSampleBufferGetPresentationTimeStamp(buffer).microseconds,
                  timeUS >= nextUS,
                  let pixels = CMSampleBufferGetImageBuffer(buffer) else { continue }
            nextUS = timeUS + Self.sampleMS * 1_000
            try body(CIImage(cvPixelBuffer: pixels).oriented(orientation), timeUS)
        }
    }

    private func size(of asset: AVURLAsset) async throws -> CGSize {
        guard let track = try await asset.loadTracks(withMediaType: .video).first else {
            return .zero
        }
        let natural = try await track.load(.naturalSize)
        let transform = try await track.load(.preferredTransform)
        let oriented = natural.applying(transform)
        return CGSize(width: abs(oriented.width), height: abs(oriented.height))
    }

    // MARK: - Fixtures

    private func sampleVideo() throws -> URL {
        try XCTUnwrap(sample, "no sample video")
    }

    private func scratch(_ name: String) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: url)
        return url
    }

    override func setUpWithError() throws {
        try XCTSkipIf(sample == nil, "Set DMRANDEVU_SAMPLE_VIDEO to a clip to run these")
    }

    private static let sampleMS: Int64 = 200

    /// How many of the originally-detected faces may still be findable. Not zero: the detector
    /// occasionally fires on a mosaicked blob, and the two files do not decode to identical
    /// moments.
    private static let maxFacesLeft = 0.15

    /// Well inside the mosaic, away from its softened rim.
    private static let core: CGFloat = 0.5

    /// How much of the plate's own detail may survive. Not zero: a small plate gets only a few
    /// mosaic cells across it, and their edges are themselves detail, as is what the encoder puts
    /// back. What must go is the black-on-white contrast of the characters.
    private static let maxDetailKept = 0.7
}

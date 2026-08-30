import AVFoundation
import XCTest
@testable import DMRandevuGaleri

/// The whole censor path over a real clip, on the phone that has to run it.
///
/// The clips are the operator's own, dropped into the host app's Documents directory rather than
/// committed — they are customers' videos. `censor_test.mp4` contains "Amına koydum" at about
/// 30.7 s, the phrase the desktop spike measured everything else against; `censor_clean.mp4` is a
/// clip with plenty of speech and none of it profane, which is the harder of the two negatives —
/// a silent clip would pass without the lexicon ever being consulted.
final class CensorAudioExportTests: XCTestCase {

    private let models = CensorModels()

    private func clip(_ name: String) -> URL? {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(name)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    /// The phrase the spike located, verified by cutting the audio at those times and hearing
    /// exactly it.
    private let knownFromUs: Int64 = 30_680_000
    private let knownToUs: Int64 = 31_780_000

    func testBeepsTheSwearingAndLeavesTheRestAlone() async throws {
        guard let input = clip("censor_test.mp4") else {
            throw XCTSkip("No censor_test.mp4 in Documents")
        }
        guard models.allInstalled else { throw XCTSkip("The speech models are not installed") }

        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("censored.mp4")
        try? FileManager.default.removeItem(at: output)

        let started = Date()
        let exporter = VideoExporter(audioCensor: AudioCensor(models: models))
        let result = try await exporter.export(
            input: input, output: output, options: ExportOptions(censorAudio: true)
        ) { _ in }
        print("censor export took \(Int(-started.timeIntervalSinceNow * 1000))ms")

        guard case .exported(let url, _, let censored) = result else {
            return XCTFail("nothing was censored: \(result)")
        }
        let windows = censored ?? []
        print("windows: " + windows.map { "\($0.startUs / 1000)-\($0.endUs / 1000)ms" }.joined(separator: ", "))
        XCTAssertFalse(windows.isEmpty, "no censor windows")

        // Containment, not overlap: a window sitting 700 ms early still overlaps the phrase, and
        // that is exactly the bug the Android build shipped and had to be told about.
        let covering = windows.filter { $0.startUs <= knownFromUs && $0.endUs >= knownToUs }
        // Guarded, not asserted: XCTAssert carries on, and indexing this next would take the
        // whole test process down with an out-of-range crash instead of reporting the failure.
        guard let covered = covering.first else {
            return XCTFail(
                "no window covers \(knownFromUs / 1000)-\(knownToUs / 1000)ms; got "
                    + windows.map { "\($0.startUs / 1000)-\($0.endUs / 1000)" }
                        .joined(separator: ", ")
            )
        }
        // And not by being enormous. The phrase is 1.1 s; the Android build lands at about 1.35 s.
        XCTAssertTrue(
            covering.contains { $0.endUs - $0.startUs < 1_600_000 },
            "the covering window is too wide: "
                + covering.map { "\(($0.endUs - $0.startUs) / 1000)ms" }.joined(separator: ", ")
        )

        try await assertVideoIntact(input: input, output: url)
        try await assertBeepInside(output: url, window: covered)
    }

    func testAClipWithNoSwearingIsLeftCompletelyAlone() async throws {
        guard let input = clip("censor_clean.mp4") else {
            throw XCTSkip("No censor_clean.mp4 in Documents")
        }
        guard models.allInstalled else { throw XCTSkip("The speech models are not installed") }

        let output = FileManager.default.temporaryDirectory
            .appendingPathComponent("clean.mp4")
        try? FileManager.default.removeItem(at: output)

        let exporter = VideoExporter(audioCensor: AudioCensor(models: models))
        let result = try await exporter.export(
            input: input, output: output, options: ExportOptions(censorAudio: true)
        ) { _ in }

        // Nothing to censor and no picture filter, so the original is handed over untouched
        // rather than re-encoded to change nothing.
        guard case .nothingToDo = result else {
            return XCTFail("a clean clip was re-encoded: \(result)")
        }
    }

    /// The picture and the track layout have to survive the audio being rebuilt.
    private func assertVideoIntact(input: URL, output: URL) async throws {
        let before = try await probe(input)
        let after = try await probe(output)
        XCTAssertEqual(before.width, after.width, "width")
        XCTAssertEqual(before.height, after.height, "height")
        XCTAssertTrue(after.hasAudio, "audio track missing")
        XCTAssertEqual(
            Double(before.durationUs), Double(after.durationUs), accuracy: 500_000,
            "duration moved"
        )
        // A portrait clip composed as landscape is the failure the preferred transform guards.
        XCTAssertEqual(before.portrait, after.portrait, "orientation flipped")
    }

    /// Listens for the beep where it should be, and for its absence where it should not.
    private func assertBeepInside(output: URL, window: CensorWindow) async throws {
        guard let audio = try await AudioTrackDecoder().decode(url: output) else {
            return XCTFail("the exported file has no audio")
        }
        let inside = share(audio, fromUs: window.startUs, toUs: window.endUs)
        // Well clear of the window, and of the crossfades at its edges.
        let outsideFrom = min(window.endUs + 2_000_000, audio.durationUs - 1_000_000)
        let outside = share(audio, fromUs: outsideFrom, toUs: outsideFrom + 800_000)

        print("1 kHz share inside=\(inside) outside=\(outside)")
        XCTAssertGreaterThan(inside, 0.2, "no beep inside the window")
        XCTAssertGreaterThan(inside, outside * 10, "the beep leaked outside the window")
    }

    /// How much of the energy in a stretch sits at 1 kHz, by Goertzel — one bin is all this needs.
    private func share(
        _ audio: AudioTrackDecoder.DecodedAudio,
        fromUs: Int64,
        toUs: Int64
    ) -> Double {
        let channels = audio.channelCount
        let from = Int(fromUs * Int64(audio.sampleRate) / 1_000_000)
        let to = Int(toUs * Int64(audio.sampleRate) / 1_000_000)
        guard to > from else { return 0 }

        let k = 2 * cos(2 * Double.pi * PcmOps.beepHz / Double(audio.sampleRate))
        var s1 = 0.0
        var s2 = 0.0
        var total = 0.0
        var counted = 0
        for i in 0..<(to - from) {
            let index = (from + i) * channels
            if index >= audio.samples.count { break }
            let sample = Double(audio.samples[index]) / 32768
            let s0 = sample + k * s1 - s2
            s2 = s1
            s1 = s0
            total += sample * sample
            counted += 1
        }
        guard counted > 0, total > 0 else { return 0 }
        let power = s1 * s1 + s2 * s2 - k * s1 * s2
        return (power / Double(counted)) / (total / Double(counted))
    }

    private struct Probe {
        var width: Int
        var height: Int
        var durationUs: Int64
        var hasAudio: Bool
        var portrait: Bool
    }

    private func probe(_ url: URL) async throws -> Probe {
        let asset = AVURLAsset(url: url)
        let duration = try await asset.load(.duration).microseconds ?? 0
        let hasAudio = try await !asset.loadTracks(withMediaType: .audio).isEmpty
        guard let track = try await asset.loadTracks(withMediaType: .video).first else {
            return Probe(width: 0, height: 0, durationUs: duration, hasAudio: hasAudio, portrait: false)
        }
        let size = try await track.load(.naturalSize)
        let transform = try await track.load(.preferredTransform)
        let presented = size.applying(transform)
        return Probe(
            width: Int(abs(presented.width)),
            height: Int(abs(presented.height)),
            durationUs: duration,
            hasAudio: hasAudio,
            portrait: abs(presented.height) >= abs(presented.width)
        )
    }
}

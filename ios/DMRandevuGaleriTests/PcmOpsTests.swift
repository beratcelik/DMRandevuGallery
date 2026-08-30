import AVFoundation
import XCTest
@testable import DMRandevuGaleri

final class PcmOpsTests: XCTestCase {

    private let rate = 44_100

    func testDownmixAveragesTheChannels() {
        let stereo: [Int16] = [100, 300, -200, 0, 1000, 2000]
        XCTAssertEqual(PcmOps.downmixToMono(stereo, channelCount: 2), [200, -100, 1500])
    }

    func testDownmixLeavesMonoAlone() {
        let mono: [Int16] = [1, 2, 3]
        XCTAssertEqual(PcmOps.downmixToMono(mono, channelCount: 1), mono)
    }

    func testDeinterleaveAndInterleaveRoundTrip() {
        let stereo: [Int16] = [1000, -1000, 2000, -2000, 3000, -3000]
        let channels = PcmOps.deinterleave(stereo, channelCount: 2)
        XCTAssertEqual(channels.count, 2)
        XCTAssertEqual(channels[0].count, 3)
        let back = PcmOps.interleave(channels)
        for i in stereo.indices {
            XCTAssertEqual(Int(stereo[i]), Int(back[i]), accuracy: 1)
        }
    }

    /// Adding a beep on top of loud background overshoots; wrapping would be a loud crack.
    func testInterleaveClampsInsteadOfWrapping() {
        let out = PcmOps.interleave([[4], [-4]])
        XCTAssertEqual(out[0], 32767)
        XCTAssertEqual(out[1], -32768)
    }

    func testTheBeepIsAOneKilohertzTone() {
        let frames = rate / 2
        var channels: [[Float]] = [[Float](repeating: 0, count: frames)]
        PcmOps.mixBeepInto(&channels, startFrame: 0, endFrame: frames, sampleRate: rate)

        // Count zero crossings over the steady middle, away from the fades.
        let from = frames / 4
        let to = frames * 3 / 4
        var crossings = 0
        for i in (from + 1)..<to where channels[0][i - 1] < 0 && channels[0][i] >= 0 {
            crossings += 1
        }
        let measured = Double(crossings) / (Double(to - from) / Double(rate))
        XCTAssertEqual(measured, PcmOps.beepHz, accuracy: 20)
    }

    func testTheBeepEasesInAndOutInsteadOfClicking() {
        let frames = rate / 2
        var channels: [[Float]] = [[Float](repeating: 0, count: frames)]
        PcmOps.mixBeepInto(&channels, startFrame: 0, endFrame: frames, sampleRate: rate)

        XCTAssertLessThan(abs(channels[0][0]), 0.01)
        XCTAssertLessThan(abs(channels[0][frames - 1]), 0.01)

        // No sample-to-sample jump big enough to hear as a click.
        let biggest = (1..<frames).map { abs(channels[0][$0] - channels[0][$0 - 1]) }.max() ?? 0
        XCTAssertLessThan(biggest, 0.1)
    }

    func testTheBeepIsAddedToTheBackgroundRatherThanReplacingIt() {
        let frames = 4_410
        let background = [Float](repeating: 0.1, count: frames)
        var channels = [background]
        PcmOps.mixBeepInto(&channels, startFrame: 0, endFrame: frames, sampleRate: rate)

        // Over a stretch rather than one sample: the tone crosses zero regularly and a single
        // sample can legitimately sit on a crossing.
        let swing = ((frames / 4)..<(frames / 2))
            .map { abs(channels[0][$0] - background[$0]) }.max() ?? 0
        XCTAssertGreaterThan(swing, 0.1)

        // Averaged over whole cycles the tone sums to nothing, leaving the background.
        let mean = channels[0].reduce(0, +) / Float(frames)
        XCTAssertEqual(mean, 0.1, accuracy: 0.01)
    }

    func testTheBeepOnlyTouchesTheFramesItWasGiven() {
        var channels: [[Float]] = [[Float](repeating: 0, count: 1_000)]
        PcmOps.mixBeepInto(&channels, startFrame: 400, endFrame: 600, sampleRate: rate)
        for i in 0..<400 { XCTAssertEqual(channels[0][i], 0) }
        for i in 600..<1_000 { XCTAssertEqual(channels[0][i], 0) }
        XCTAssertTrue(channels[0][400..<600].contains { abs($0) > 0.01 })
    }

    func testCrossfadeMeetsTheOriginalExactlyAtTheEdges() {
        let frames = 1_000
        let original = [[Float](repeating: 0.5, count: frames)]
        var patched = [[Float](repeating: -0.5, count: frames)]
        PcmOps.crossfadeEdges(original: original, patched: &patched, frames: 100)

        XCTAssertEqual(patched[0][0], 0.5, accuracy: 1e-6)
        XCTAssertEqual(patched[0][frames - 1], 0.5, accuracy: 1e-6)
        XCTAssertEqual(patched[0][frames / 2], -0.5, accuracy: 1e-6)
    }

    func testCrossfadeWiderThanThePatchDoesNotRunOffEitherEnd() {
        let original = [[Float](repeating: 1, count: 10)]
        var patched = [[Float](repeating: 0, count: 10)]
        PcmOps.crossfadeEdges(original: original, patched: &patched, frames: 500)
        XCTAssertEqual(patched[0].count, 10)
    }

    // MARK: - The platform-backed conversions

    func testResamplingKeepsTheToneAndTheLength() throws {
        let seconds = 1.0
        let source = (0..<Int(Double(rate) * seconds)).map { i in
            Int16(sin(2 * Double.pi * 440 * Double(i) / Double(rate)) * 8000)
        }
        let out = try PcmOps.resample(source, channelCount: 1, from: rate, to: 16_000)

        XCTAssertEqual(Double(out.count), 16_000 * seconds, accuracy: 16_000 * 0.02)
        // The tone survives: count zero crossings of a 440 Hz sine over the middle.
        let from = out.count / 4
        let to = out.count * 3 / 4
        var crossings = 0
        for i in (from + 1)..<to where out[i - 1] < 0 && out[i] >= 0 { crossings += 1 }
        let measured = Double(crossings) / (Double(to - from) / 16_000)
        XCTAssertEqual(measured, 440, accuracy: 15)
    }

    func testResamplingToTheSameRateIsAPassthrough() throws {
        let source: [Int16] = [1, -2, 3, -4]
        XCTAssertEqual(try PcmOps.resample(source, channelCount: 1, from: rate, to: rate), source)
    }

    /// The tempo change is what makes the recognizer hear swearing it otherwise writes around, so
    /// it has to actually stretch the audio — and leave the pitch where it was.
    func testSlowingDownStretchesTimeButNotPitch() throws {
        let seconds = 2.0
        let hz = 440.0
        let source = (0..<Int(Double(rate) * seconds)).map { i in
            Int16(sin(2 * Double.pi * hz * Double(i) / Double(rate)) * 8000)
        }
        let out = try PcmOps.changeTempo(source, sampleRate: rate, speed: 0.75)

        // A third longer, give or take the engine's tail.
        XCTAssertEqual(
            Double(out.count), Double(source.count) / 0.75, accuracy: Double(rate) * 0.3
        )

        // Same note: zero crossings per second are unchanged.
        let from = out.count / 4
        let to = out.count * 3 / 4
        var crossings = 0
        for i in (from + 1)..<to where out[i - 1] < 0 && out[i] >= 0 { crossings += 1 }
        let measured = Double(crossings) / (Double(to - from) / Double(rate))
        XCTAssertEqual(measured, hz, accuracy: 25)
    }

    func testTempoOfOneIsAPassthrough() throws {
        let source: [Int16] = [5, -6, 7, -8]
        XCTAssertEqual(try PcmOps.changeTempo(source, sampleRate: rate, speed: 1), source)
    }

    func testTheRecognitionFeedIsMonoAtSixteenKilohertz() throws {
        let seconds = 1.0
        let frames = Int(Double(rate) * seconds)
        var stereo = [Int16](repeating: 0, count: frames * 2)
        for i in 0..<frames {
            let v = Int16(sin(2 * Double.pi * 300 * Double(i) / Double(rate)) * 6000)
            stereo[i * 2] = v
            stereo[i * 2 + 1] = v
        }
        let out = try PcmOps.forRecognition(stereo, channelCount: 2, sampleRate: rate)
        XCTAssertEqual(Double(out.count), 16_000 * seconds, accuracy: 16_000 * 0.05)
        XCTAssertTrue(out.contains { abs($0) > 0.05 }, "the signal survived the conversion")
    }
}

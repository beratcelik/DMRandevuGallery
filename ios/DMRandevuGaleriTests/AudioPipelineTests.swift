import AVFoundation
import XCTest
@testable import DMRandevuGaleri

/// Does the audio that reaches the recognizer still line up with the video it came from?
///
/// Written because the second pass placed a word 1350 ms early on this platform and by exactly
/// the same amount inside a short snippet — a uniform shift, which is what audio going missing
/// from the front of the buffer looks like, not a model being imprecise.
final class AudioPipelineTests: XCTestCase {

    private func clip(_ name: String) -> URL? {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(name)
        return FileManager.default.fileExists(atPath: url.path) ? url : nil
    }

    func testTheDecodedAudioIsAsLongAsTheVideo() async throws {
        guard let input = clip("censor_test.mp4") else { throw XCTSkip("No censor_test.mp4") }

        let asset = AVURLAsset(url: input)
        let assetUs = try await asset.load(.duration).microseconds ?? 0
        guard let audio = try await AudioTrackDecoder().decode(url: input) else {
            return XCTFail("no audio track")
        }
        print("[pipeline] asset \(assetUs / 1000)ms, decoded \(audio.durationUs / 1000)ms, "
            + "\(audio.frameCount) frames @ \(audio.sampleRate)Hz x\(audio.channelCount)")

        XCTAssertEqual(
            Double(audio.durationUs), Double(assetUs), accuracy: 100_000,
            "the decode lost or gained more than 100ms against the asset"
        )
    }

    func testTheRecognitionFeedKeepsItsLength() async throws {
        guard let input = clip("censor_test.mp4") else { throw XCTSkip("No censor_test.mp4") }
        guard let audio = try await AudioTrackDecoder().decode(url: input) else {
            return XCTFail("no audio track")
        }

        let samples = try PcmOps.forRecognition(
            audio.samples, channelCount: audio.channelCount, sampleRate: audio.sampleRate
        )
        let expected = Int(audio.durationUs * Int64(PcmOps.asrSampleRate) / 1_000_000)
        let ratio = Double(samples.count) / Double(expected)
        print("[pipeline] recognition feed \(samples.count) samples, expected \(expected), "
            + "ratio \(String(format: "%.5f", ratio)), "
            + "difference \(Int((Double(samples.count - expected) / 16.0)))ms")

        XCTAssertEqual(ratio, 1.0, accuracy: 0.005, "the recognition feed changed length")
    }

    /// Where the sound actually starts, in the decoded buffer and in the recognition feed.
    ///
    /// A uniform timing shift shows up here as the two disagreeing: if the feed's first loud
    /// sample is earlier than the decode's, something was trimmed off the front.
    func testTheFeedStartsWhereTheDecodeDoes() async throws {
        guard let input = clip("censor_test.mp4") else { throw XCTSkip("No censor_test.mp4") }
        guard let audio = try await AudioTrackDecoder().decode(url: input) else {
            return XCTFail("no audio track")
        }

        let mono = PcmOps.downmixToMono(audio.samples, channelCount: audio.channelCount)
        let decodedOnsetMs = onsetMs(PcmOps.toFloat(mono), rate: audio.sampleRate)
        let feed = try PcmOps.forRecognition(
            audio.samples, channelCount: audio.channelCount, sampleRate: audio.sampleRate
        )
        let feedOnsetMs = onsetMs(feed, rate: PcmOps.asrSampleRate)
        print("[pipeline] first sound: decode \(decodedOnsetMs)ms, feed \(feedOnsetMs)ms")

        XCTAssertEqual(
            Double(feedOnsetMs), Double(decodedOnsetMs), accuracy: 60,
            "the recognition feed does not start where the decoded audio does"
        )
    }

    /// First moment the signal rises well clear of the noise floor.
    private func onsetMs(_ samples: [Float], rate: Int) -> Int {
        let window = rate / 100 // 10 ms
        guard window > 0, samples.count > window else { return 0 }
        var peak: Float = 0
        for value in samples { peak = max(peak, abs(value)) }
        let threshold = max(0.02, peak * 0.1)
        var index = 0
        while index + window <= samples.count {
            var loudest: Float = 0
            for i in index..<(index + window) { loudest = max(loudest, abs(samples[i])) }
            if loudest >= threshold { return index * 1000 / rate }
            index += window
        }
        return 0
    }
}

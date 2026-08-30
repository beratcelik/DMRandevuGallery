import AVFoundation
import Foundation

/// Writes the censored audio out as a file the export can use as its audio track.
///
/// This is where iOS and Android part company. media3 lets a processor sit in the export and
/// rewrite the samples as they go past; `AVAssetExportSession` has no such hook — an `AVAudioMix`
/// can ramp a track's volume and nothing more, which can duck the swearing but cannot put a beep
/// over it while the music keeps playing. So the patched audio is rendered to its own file and
/// composed with the original video instead.
enum CensoredAudioTrack {

    struct WriteFailedError: Error {
        var message: String
    }

    /// Applies `plan`'s patches to the audio of `input` and writes the result beside it.
    ///
    /// Everything outside a patch is copied through sample for sample; only the censored stretches
    /// are replaced.
    static func write(plan: CensorPlan, from input: URL, to output: URL) async throws {
        guard let decoded = try await AudioTrackDecoder().decode(url: input) else {
            throw WriteFailedError(message: "The video has no audio to censor")
        }
        // Checked rather than assumed. Every offset in the plan is a frame number at a particular
        // rate and channel count; if this decode disagrees, those numbers point at the wrong
        // moments and the beeps land over innocent words while the swearing plays.
        guard decoded.sampleRate == plan.sampleRate,
              decoded.channelCount == plan.channelCount
        else {
            throw WriteFailedError(
                message: "Audio came back as \(decoded.channelCount)ch at \(decoded.sampleRate)Hz,"
                    + " the plan expected \(plan.channelCount)ch at \(plan.sampleRate)Hz"
            )
        }
        let drift = abs(decoded.frameCount - plan.sourceFrameCount)
        guard drift <= Int64(plan.sampleRate) * Self.toleranceMs / 1000 else {
            throw WriteFailedError(
                message: "Audio was \(decoded.frameCount) frames, "
                    + "the plan expected \(plan.sourceFrameCount)"
            )
        }

        var samples = decoded.samples
        let channels = plan.channelCount
        for patch in plan.patches {
            for frame in patch.startFrame..<min(patch.endFrame, decoded.frameCount) {
                let into = Int(frame) * channels
                let from = Int(frame - patch.startFrame) * channels
                for channel in 0..<channels where from + channel < patch.samples.count {
                    samples[into + channel] = patch.samples[from + channel]
                }
            }
        }

        try write(samples: samples, rate: plan.sampleRate, channels: channels, to: output)
    }

    private static func write(
        samples: [Int16],
        rate: Int,
        channels: Int,
        to output: URL
    ) throws {
        try? FileManager.default.removeItem(at: output)
        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Double(rate),
            channels: AVAudioChannelCount(channels),
            interleaved: true
        ) else {
            throw WriteFailedError(message: "No format for \(channels)ch at \(rate)Hz")
        }

        // Written as uncompressed CAF and re-encoded once by the export, rather than encoded here
        // and again there.
        let file = try AVAudioFile(
            forWriting: output,
            settings: format.settings,
            commonFormat: .pcmFormatInt16,
            interleaved: true
        )
        let frames = samples.count / channels
        var written = 0
        let chunk = 1 << 16
        while written < frames {
            let count = min(chunk, frames - written)
            guard let buffer = AVAudioPCMBuffer(
                pcmFormat: format, frameCapacity: AVAudioFrameCount(count)
            ), let target = buffer.int16ChannelData else {
                throw WriteFailedError(message: "Could not allocate the write buffer")
            }
            buffer.frameLength = AVAudioFrameCount(count)
            samples.withUnsafeBufferPointer { source in
                target[0].update(
                    from: source.baseAddress!.advanced(by: written * channels),
                    count: count * channels
                )
            }
            try file.write(from: buffer)
            written += count
        }
    }

    /// Encoder priming can shift two decodes by a frame or two; past this is a real disagreement.
    private static let toleranceMs: Int64 = 250
}

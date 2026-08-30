import Foundation

/// A stretch of replacement audio, at the source's own rate and channel count.
struct PcmPatch {
    var startFrame: Int64
    var samples: [Int16]
    var channelCount: Int

    var frameCount: Int64 { Int64(samples.count) / Int64(channelCount) }
    var endFrame: Int64 { startFrame + frameCount }
}

/// Renders the short stretches of audio that will replace the swearing.
///
/// Only the censor windows are touched, and only they are separated — the expensive part runs on a
/// second or two of audio per swear word rather than the whole clip. Separating a 35 second video
/// end to end took 84 seconds of desktop CPU when this was measured; this way it is proportional
/// to how much swearing there is.
enum BeepPatcher {

    /// Builds one patch per window. `onProgress` reports 0…1 across all of them.
    static func render(
        audio: AudioTrackDecoder.DecodedAudio,
        windows: [CensorWindow],
        separator: VoiceSeparator,
        onProgress: (Double) -> Void = { _ in }
    ) throws -> [PcmPatch] {
        guard !windows.isEmpty else { return [] }
        var patches: [PcmPatch] = []
        for (index, window) in windows.enumerated() {
            try Task.checkCancellation()
            patches.append(try render(audio: audio, window: window, separator: separator))
            onProgress(Double(index + 1) / Double(windows.count))
        }
        return patches
    }

    private static func render(
        audio: AudioTrackDecoder.DecodedAudio,
        window: CensorWindow,
        separator: VoiceSeparator
    ) throws -> PcmPatch {
        let rate = audio.sampleRate
        let channels = audio.channelCount

        // The patch covers the window plus a crossfade at each end, so it can be eased into the
        // untouched audio around it rather than butted against it.
        let fadeFrames = Int64(rate * PcmOps.crossfadeMs / 1000)
        let windowStart = window.startUs * Int64(rate) / 1_000_000
        let windowEnd = window.endUs * Int64(rate) / 1_000_000
        let patchStart = max(0, windowStart - fadeFrames)
        let patchEnd = min(audio.frameCount, windowEnd + fadeFrames)
        let patchFrames = Int(patchEnd - patchStart)
        guard patchFrames > 0 else {
            return PcmPatch(startFrame: patchStart, samples: [], channelCount: channels)
        }

        let original = slice(audio, from: patchStart, frames: patchFrames)
        var background = try separateBackground(
            original, rate: rate, channels: channels, separator: separator
        )

        PcmOps.mixBeepInto(
            &background,
            startFrame: Int(windowStart - patchStart),
            endFrame: Int(windowEnd - patchStart),
            sampleRate: rate
        )
        PcmOps.crossfadeEdges(original: original, patched: &background, frames: Int(fadeFrames))

        return PcmPatch(
            startFrame: patchStart,
            samples: PcmOps.interleave(background),
            channelCount: channels
        )
    }

    /// Takes the voice out, leaving the background.
    ///
    /// The slice is padded out to whole separation blocks with the audio that really surrounds it,
    /// and the padding is discarded afterwards — feeding the model silence at the block edges
    /// makes it hear the join.
    private static func separateBackground(
        _ original: [[Float]],
        rate: Int,
        channels: Int,
        separator: VoiceSeparator
    ) throws -> [[Float]] {
        let frames = original[0].count
        let atModelRate = try resampleChannels(
            original, from: rate, to: PcmOps.separationSampleRate
        )
        let stereo = toStereo(atModelRate)
        let modelFrames = stereo[0].count

        var separated = [[Float]](repeating: [Float](repeating: 0, count: modelFrames), count: 2)
        var offset = 0
        while offset < modelFrames {
            try Task.checkCancellation()
            // Each block is read from the real signal, centred so the trimmed edges fall outside
            // the part being kept.
            let blockStart = offset - Stft.trim
            let block = (0..<2).map { channel in
                (0..<Stft.chunk).map { i -> Float in
                    let at = blockStart + i
                    return at >= 0 && at < modelFrames ? stereo[channel][at] : 0
                }
            }
            let voice = try separator.vocals(in: block)
            let take = min(Stft.usable, modelFrames - offset)
            for channel in 0..<2 {
                for i in 0..<take {
                    separated[channel][offset + i] =
                        stereo[channel][offset + i] - voice[channel][Stft.trim + i]
                }
            }
            offset += Stft.usable
        }

        let back = try resampleChannels(separated, from: PcmOps.separationSampleRate, to: rate)
        // Resampling rarely lands on exactly the frame count it started from; the patch has to be
        // the length the caller expects.
        return (0..<channels).map { channel in
            let source = back[min(channel, back.count - 1)]
            return (0..<frames).map { $0 < source.count ? source[$0] : 0 }
        }
    }

    private static func slice(
        _ audio: AudioTrackDecoder.DecodedAudio,
        from startFrame: Int64,
        frames: Int
    ) -> [[Float]] {
        let channels = audio.channelCount
        return (0..<channels).map { channel in
            (0..<frames).map { i -> Float in
                let index = Int((startFrame + Int64(i)) * Int64(channels)) + channel
                return index >= 0 && index < audio.samples.count
                    ? Float(audio.samples[index]) / 32768
                    : 0
            }
        }
    }

    /// A mono track is fed to both sides; the model has never seen anything else.
    private static func toStereo(_ channels: [[Float]]) -> [[Float]] {
        switch channels.count {
        case 2: return channels
        case 1: return [channels[0], channels[0]]
        default: return [channels[0], channels[1]]
        }
    }

    private static func resampleChannels(
        _ channels: [[Float]],
        from fromRate: Int,
        to toRate: Int
    ) throws -> [[Float]] {
        if fromRate == toRate { return channels }
        return try channels.map { channel in
            let shorts = channel.map { Int16(max(-32768, min(32767, ($0 * 32768).rounded()))) }
            let resampled = try PcmOps.resample(
                shorts, channelCount: 1, from: fromRate, to: toRate
            )
            return resampled.map { Float($0) / 32768 }
        }
    }
}

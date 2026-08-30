import AVFoundation

/// Decodes a video's audio track to PCM, all of it, in one linear pass.
///
/// The whole track is held in memory on purpose. Recognition needs to see the audio end to end
/// before it can say where the swearing is, and the censoring pass then has to reach back into the
/// middle of it — so there is nothing to stream. A minute of 48 kHz stereo is about 11 MB, and
/// these clips are Instagram DMs rather than films.
struct AudioTrackDecoder {

    /// Interleaved 16-bit samples, exactly as the decoder produced them.
    ///
    /// `frameCount` counts frames, not samples: one frame is one sample per channel, which is the
    /// unit everything downstream indexes by.
    struct DecodedAudio {
        var samples: [Int16]
        var sampleRate: Int
        var channelCount: Int

        var frameCount: Int64 { Int64(samples.count) / Int64(channelCount) }
        var durationUs: Int64 { frameCount * 1_000_000 / Int64(sampleRate) }
    }

    struct UnsupportedAudioError: Error {
        var message: String
    }

    /// Decodes the audio of `url`, or returns nil when it has no audio track — a silent video has
    /// nothing to censor and must not be treated as a failure.
    func decode(url: URL, onProgress: @escaping (Double) -> Void = { _ in }) async throws
        -> DecodedAudio?
    {
        let asset = AVURLAsset(url: url)
        guard let track = try await asset.loadTracks(withMediaType: .audio).first else {
            return nil
        }

        let format = try await track.load(.formatDescriptions).first
        let basic = format.flatMap { CMAudioFormatDescriptionGetStreamBasicDescription($0)?.pointee }
        let sampleRate = Int(basic?.mSampleRate ?? 44_100)
        let channelCount = Int(basic?.mChannelsPerFrame ?? 2)
        guard sampleRate > 0, channelCount > 0 else {
            throw UnsupportedAudioError(
                message: "Audio track declared \(channelCount) channels at \(sampleRate) Hz"
            )
        }

        let reader = try AVAssetReader(asset: asset)
        // Interleaved signed 16-bit, at the track's own rate and channel count. Everything
        // downstream indexes 16-bit frames, and a float or resampled decode read as this would be
        // noise — it would beep confidently in the wrong places.
        let output = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false,
                AVSampleRateKey: sampleRate,
                AVNumberOfChannelsKey: channelCount
            ]
        )
        guard reader.canAdd(output) else {
            throw UnsupportedAudioError(message: "Cannot read this audio track")
        }
        reader.add(output)
        guard reader.startReading() else {
            throw UnsupportedAudioError(
                message: reader.error?.localizedDescription ?? "Could not start reading"
            )
        }

        let durationUs = try await asset.load(.duration).microseconds ?? 0
        var samples: [Int16] = []
        samples.reserveCapacity(sampleRate * channelCount * 30)

        while let buffer = output.copyNextSampleBuffer() {
            try Task.checkCancellation()
            if let block = CMSampleBufferGetDataBuffer(buffer) {
                let length = CMBlockBufferGetDataLength(block)
                var bytes = [UInt8](repeating: 0, count: length)
                CMBlockBufferCopyDataBytes(
                    block, atOffset: 0, dataLength: length, destination: &bytes
                )
                bytes.withUnsafeBytes { raw in
                    samples.append(contentsOf: raw.bindMemory(to: Int16.self))
                }
            }
            if durationUs > 0 {
                let at = CMSampleBufferGetPresentationTimeStamp(buffer).microseconds ?? 0
                onProgress(min(1, max(0, Double(at) / Double(durationUs))))
            }
            CMSampleBufferInvalidate(buffer)
        }

        if reader.status == .failed {
            throw UnsupportedAudioError(
                message: reader.error?.localizedDescription ?? "Audio decode failed"
            )
        }

        // Trimmed to whole frames: a truncated final frame would shift every channel after it.
        let whole = samples.count - samples.count % channelCount
        onProgress(1)
        return DecodedAudio(
            samples: Array(samples.prefix(whole)),
            sampleRate: sampleRate,
            channelCount: channelCount
        )
    }
}

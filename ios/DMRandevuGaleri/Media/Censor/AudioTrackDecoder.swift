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

        let reader = try AVAssetReader(asset: asset)
        // Interleaved signed 16-bit, and deliberately nothing about rate or channels.
        //
        // Naming them meant naming what the *compressed* format description reports, and for AAC
        // with spectral band replication that is the core rate — half the real one. Asking for it
        // made the reader resample a 44.1 kHz track down to 22.05, throwing away the top octave
        // before the separation model, which works at 44.1, ever saw it. Left unsaid, the decoder
        // hands back the track's own rate and the format below reads what actually arrived.
        let output = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
                AVLinearPCMIsNonInterleaved: false
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
        var sampleRate = 0
        var channelCount = 0

        while let buffer = output.copyNextSampleBuffer() {
            try Task.checkCancellation()
            // Read from the buffers themselves, once, rather than from the compressed track.
            if sampleRate == 0,
               let described = CMSampleBufferGetFormatDescription(buffer),
               let basic = CMAudioFormatDescriptionGetStreamBasicDescription(described)?.pointee {
                sampleRate = Int(basic.mSampleRate)
                channelCount = Int(basic.mChannelsPerFrame)
                samples.reserveCapacity(sampleRate * max(1, channelCount) * 30)
            }
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

        guard sampleRate > 0, channelCount > 0 else {
            throw UnsupportedAudioError(
                message: "The decoder never said what rate or how many channels it was producing"
            )
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

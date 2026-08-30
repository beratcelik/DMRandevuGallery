import AVFoundation
import Accelerate

/// The sample arithmetic the censor pass needs: rate and tempo changes on the way into
/// recognition, and the beep and crossfades on the way out.
enum PcmOps {

    /// What the recognizer expects: one channel at 16 kHz.
    static let asrSampleRate = 16_000

    /// What the separation model was trained on.
    static let separationSampleRate = 44_100

    struct ConversionError: Error {
        var message: String
    }

    /// Averages every channel into one.
    ///
    /// Averaged rather than left-channel-only: speech panned to one side would otherwise arrive
    /// at the recognizer quiet or missing.
    static func downmixToMono(_ samples: [Int16], channelCount: Int) -> [Int16] {
        if channelCount == 1 { return samples }
        let frames = samples.count / channelCount
        var mono = [Int16](repeating: 0, count: frames)
        for frame in 0..<frames {
            var sum = 0
            let base = frame * channelCount
            for channel in 0..<channelCount { sum += Int(samples[base + channel]) }
            mono[frame] = Int16(clamping: sum / channelCount)
        }
        return mono
    }

    /// Splits interleaved samples into one array per channel, as the separation model wants.
    static func deinterleave(_ samples: [Int16], channelCount: Int) -> [[Float]] {
        let frames = samples.count / channelCount
        return (0..<channelCount).map { channel in
            (0..<frames).map { Float(samples[$0 * channelCount + channel]) / 32768 }
        }
    }

    static func interleave(_ channels: [[Float]]) -> [Int16] {
        let channelCount = channels.count
        let frames = channels[0].count
        var out = [Int16](repeating: 0, count: frames * channelCount)
        for frame in 0..<frames {
            for channel in 0..<channelCount {
                out[frame * channelCount + channel] = clampToInt16(channels[channel][frame] * 32768)
            }
        }
        return out
    }

    static func toFloat(_ samples: [Int16]) -> [Float] {
        samples.map { Float($0) / 32768 }
    }

    /// Mono, 16 kHz, floats — one call, because every recognizer pass starts this way.
    static func forRecognition(
        _ samples: [Int16],
        channelCount: Int,
        sampleRate: Int,
        speed: Float = 1
    ) throws -> [Float] {
        let mono = downmixToMono(samples, channelCount: channelCount)
        let slowed = try changeTempo(mono, sampleRate: sampleRate, speed: speed)
        return toFloat(try resample(slowed, channelCount: 1, from: sampleRate, to: asrSampleRate))
    }

    /// Resamples without touching the playback speed.
    static func resample(
        _ samples: [Int16],
        channelCount: Int,
        from fromRate: Int,
        to toRate: Int
    ) throws -> [Int16] {
        if fromRate == toRate { return samples }
        guard
            let input = format(rate: fromRate, channels: channelCount),
            let output = format(rate: toRate, channels: channelCount),
            let converter = AVAudioConverter(from: input, to: output)
        else {
            throw ConversionError(message: "No converter from \(fromRate) to \(toRate)")
        }
        // Highest quality on purpose: a cheap resampler folds everything above the new Nyquist
        // back down as aliasing, which on speech is a hiss laid over the very words being
        // listened for.
        converter.sampleRateConverterQuality = AVAudioQuality.max.rawValue

        guard let inBuffer = buffer(from: samples, format: input) else {
            throw ConversionError(message: "Could not wrap the samples")
        }
        let ratio = Double(toRate) / Double(fromRate)
        let capacity = AVAudioFrameCount(Double(inBuffer.frameLength) * ratio) + 4096
        guard let outBuffer = AVAudioPCMBuffer(pcmFormat: output, frameCapacity: capacity) else {
            throw ConversionError(message: "Could not allocate the output")
        }

        var supplied = false
        var conversionError: NSError?
        converter.convert(to: outBuffer, error: &conversionError) { _, status in
            if supplied {
                status.pointee = .endOfStream
                return nil
            }
            supplied = true
            status.pointee = .haveData
            return inBuffer
        }
        if let conversionError {
            throw ConversionError(message: conversionError.localizedDescription)
        }
        return read(outBuffer)
    }

    /// Stretches or compresses time while leaving pitch alone.
    ///
    /// Slowing the audio down is what makes the recognizer hear swearing it otherwise replaces
    /// with an innocent near-homophone; pitch has to stay put, or the voice stops sounding like a
    /// voice and the recognition gets worse instead of better.
    ///
    /// Rendered through an offline engine because there is no other way to time-stretch on this
    /// platform — `AVAudioConverter` changes rate but not tempo, and playing it back slowly would
    /// take as long as the clip.
    static func changeTempo(_ samples: [Int16], sampleRate: Int, speed: Float) throws -> [Int16] {
        if speed == 1 { return samples }
        // The engine will only work in non-interleaved float; handed interleaved 16-bit it
        // refuses the format outright rather than converting.
        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: Double(sampleRate),
            channels: 1,
            interleaved: false
        ) else {
            throw ConversionError(message: "No float format at \(sampleRate) Hz")
        }
        guard let input = AVAudioPCMBuffer(
            pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)
        ), let target = input.floatChannelData else {
            throw ConversionError(message: "Could not wrap the samples")
        }
        input.frameLength = AVAudioFrameCount(samples.count)
        for i in samples.indices { target[0][i] = Float(samples[i]) / 32768 }

        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let timePitch = AVAudioUnitTimePitch()
        timePitch.rate = speed
        engine.attach(player)
        engine.attach(timePitch)
        engine.connect(player, to: timePitch, format: format)
        engine.connect(timePitch, to: engine.mainMixerNode, format: format)

        let expected = AVAudioFramePosition(Double(samples.count) / Double(speed))
        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 4096)
        try engine.start()
        player.scheduleBuffer(input, at: nil, options: [], completionHandler: nil)
        player.play()

        guard let render = AVAudioPCMBuffer(
            pcmFormat: engine.manualRenderingFormat,
            frameCapacity: engine.manualRenderingMaximumFrameCount
        ) else {
            throw ConversionError(message: "Could not allocate the render buffer")
        }

        var out: [Int16] = []
        out.reserveCapacity(Int(expected))
        while engine.manualRenderingSampleTime < expected {
            let remaining = AVAudioFrameCount(expected - engine.manualRenderingSampleTime)
            let status = try engine.renderOffline(
                min(render.frameCapacity, remaining), to: render
            )
            guard status == .success else { break }
            if let data = render.floatChannelData {
                for i in 0..<Int(render.frameLength) {
                    out.append(clampToInt16(data[0][i] * 32768))
                }
            }
        }
        player.stop()
        engine.stop()
        engine.disableManualRenderingMode()
        return out
    }

    /// A tone that covers the word without startling anyone: a sine at `beepHz`, eased in and out
    /// so it starts and stops without a click.
    ///
    /// Added to whatever is already there — which is the separated background, so the music
    /// carries on underneath.
    static func mixBeepInto(
        _ destination: inout [[Float]],
        startFrame: Int,
        endFrame: Int,
        sampleRate: Int,
        level: Float = beepLevel
    ) {
        let span = endFrame - startFrame
        guard span > 0 else { return }
        let fade = max(1, min(span / 2, sampleRate * beepFadeMs / 1000))
        for i in 0..<span {
            let envelope: Float
            if i < fade {
                envelope = raisedCosine(Float(i) / Float(fade))
            } else if i > span - fade {
                envelope = raisedCosine(Float(span - i) / Float(fade))
            } else {
                envelope = 1
            }
            let value = level * envelope
                * Float(sin(2 * Double.pi * beepHz * Double(i) / Double(sampleRate)))
            let index = startFrame + i
            for channel in destination.indices where destination[channel].indices.contains(index) {
                destination[channel][index] += value
            }
        }
    }

    /// Eases `patched` into `original` across `frames` at each end.
    ///
    /// The patched audio and the untouched audio around it do not meet at the same point in the
    /// waveform, and a hard join between them is an audible click on every beep.
    static func crossfadeEdges(original: [[Float]], patched: inout [[Float]], frames: Int) {
        let length = patched[0].count
        let fade = min(frames, length / 2)
        guard fade > 0 else { return }
        for channel in patched.indices {
            for i in 0..<fade {
                let a = Float(i) / Float(fade)
                patched[channel][i] = original[channel][i] * (1 - a) + patched[channel][i] * a
                let end = length - 1 - i
                patched[channel][end] = original[channel][end] * (1 - a) + patched[channel][end] * a
            }
        }
    }

    // MARK: - Buffers

    private static func format(rate: Int, channels: Int) -> AVAudioFormat? {
        AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: Double(rate),
            channels: AVAudioChannelCount(channels),
            interleaved: true
        )
    }

    private static func buffer(from samples: [Int16], format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let frames = AVAudioFrameCount(samples.count / Int(format.channelCount))
        guard frames > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames),
              let target = buffer.int16ChannelData
        else { return nil }
        buffer.frameLength = frames
        samples.withUnsafeBufferPointer { source in
            target[0].update(from: source.baseAddress!, count: samples.count)
        }
        return buffer
    }

    private static func read(_ buffer: AVAudioPCMBuffer) -> [Int16] {
        guard let data = buffer.int16ChannelData else { return [] }
        let count = Int(buffer.frameLength) * Int(buffer.format.channelCount)
        return Array(UnsafeBufferPointer(start: data[0], count: count))
    }

    private static func raisedCosine(_ t: Float) -> Float {
        Float(0.5 - 0.5 * cos(Double.pi * Double(t)))
    }

    private static func clampToInt16(_ value: Float) -> Int16 {
        Int16(max(-32768, min(32767, value.rounded(.towardZero))))
    }

    /// Clear of speech, and the pitch every television has trained people to read as censoring.
    static let beepHz = 1000.0

    /// −12 dBFS: over the top of the background without clipping when it is already loud.
    static let beepLevel: Float = 0.25

    private static let beepFadeMs = 5

    /// How long the patched audio takes to blend into the untouched audio at each edge.
    static let crossfadeMs = 15
}

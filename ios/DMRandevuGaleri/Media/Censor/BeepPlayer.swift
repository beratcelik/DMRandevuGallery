import AVFoundation

/// The censor tone, played live over the video while a marked stretch goes past.
///
/// So the operator can hear what they marked instead of reading a red bar and hoping. It is an
/// approximation of the export, not the export: the real one separates the voice out and leaves
/// the music underneath, which takes seconds a frame and cannot happen during playback. Here the
/// video is simply ducked and the tone laid over it, which is enough to tell whether the mark
/// covers the word — the question the operator is actually asking.
final class BeepPlayer {

    private var player: AVAudioPlayer?

    /// Starts the tone, or does nothing if it is already sounding.
    func start() {
        if player?.isPlaying == true { return }
        if player == nil { player = try? AVAudioPlayer(data: Self.tone()) }
        guard let player else { return }
        // Looped rather than one long buffer, so a mark of any length is covered.
        player.numberOfLoops = -1
        player.volume = Float(Self.level)
        player.prepareToPlay()
        player.play()
    }

    func stop() {
        player?.stop()
        player?.currentTime = 0
    }

    /// One second of 1 kHz as a WAV, eased at both ends so looping it does not tick.
    private static func tone() -> Data {
        let count = sampleRate
        let fade = sampleRate * fadeMs / 1000
        var samples = [Int16](repeating: 0, count: count)
        for i in 0..<count {
            let envelope: Double
            if i < fade {
                envelope = 0.5 - 0.5 * cos(Double.pi * Double(i) / Double(fade))
            } else if i > count - fade {
                envelope = 0.5 - 0.5 * cos(Double.pi * Double(count - i) / Double(fade))
            } else {
                envelope = 1
            }
            let value = envelope * sin(2 * Double.pi * PcmOps.beepHz * Double(i) / Double(sampleRate))
            samples[i] = Int16(value * 32000)
        }
        return wav(samples)
    }

    /// The smallest WAV that AVAudioPlayer will accept: it takes data, not raw PCM.
    private static func wav(_ samples: [Int16]) -> Data {
        let bytes = samples.count * 2
        var data = Data()
        func append(_ string: String) { data.append(contentsOf: Array(string.utf8)) }
        func append32(_ value: UInt32) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }
        func append16(_ value: UInt16) { withUnsafeBytes(of: value.littleEndian) { data.append(contentsOf: $0) } }

        append("RIFF")
        append32(UInt32(36 + bytes))
        append("WAVE")
        append("fmt ")
        append32(16)
        append16(1)                              // PCM
        append16(1)                              // mono
        append32(UInt32(sampleRate))
        append32(UInt32(sampleRate * 2))         // bytes a second
        append16(2)                              // bytes a frame
        append16(16)                             // bits a sample
        append("data")
        append32(UInt32(bytes))
        samples.withUnsafeBufferPointer { data.append(UnsafeRawBufferPointer($0).bindMemory(to: UInt8.self)) }
        return data
    }

    private static let sampleRate = 44_100
    private static let fadeMs = 5

    /// Quieter than the exported beep, which is not competing with a ducked video.
    private static let level = 0.18
}

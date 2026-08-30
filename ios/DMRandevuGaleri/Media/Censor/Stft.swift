import Accelerate
import Foundation

/// The short-time Fourier transform the separation model was trained against.
///
/// This has to match rather than merely resemble: the model is fed the coefficients directly, so a
/// different window, padding rule or normalisation is not a small numerical difference — it is an
/// input the network has never seen, and it answers with noise. Periodic Hann, `center = true`
/// with reflect padding, no normalisation, frequency axis cropped to `bins`.
///
/// Accelerate's DFT rather than its FFT: 7680 is 2^9 x 15, and the FFT only does powers of two.
/// The DFT setup accepts 15 x 2^n, which is exactly this length.
final class Stft {

    struct SetupError: Error {
        var message: String
    }

    /// One channel's coefficients: `real` and `imag`, each `bins` rows of `frames`.
    struct Planes {
        var real: [Float]
        var imag: [Float]
        var bins: Int
        var frames: Int
    }

    private let nFft: Int
    private let hop: Int
    private let pad: Int
    private let window: [Float]
    private let forwardSetup: vDSP_DFT_Setup
    private let inverseSetup: vDSP_DFT_Setup

    init(nFft: Int = Stft.nFftDefault, hop: Int = Stft.hopDefault) throws {
        self.nFft = nFft
        self.hop = hop
        self.pad = nFft / 2
        self.window = (0..<nFft).map {
            Float(0.5 - 0.5 * cos(2 * Double.pi * Double($0) / Double(nFft)))
        }
        guard
            let forward = vDSP_DFT_zop_CreateSetup(nil, vDSP_Length(nFft), .FORWARD),
            let inverse = vDSP_DFT_zop_CreateSetup(nil, vDSP_Length(nFft), .INVERSE)
        else {
            throw SetupError(message: "No DFT of length \(nFft)")
        }
        self.forwardSetup = forward
        self.inverseSetup = inverse
    }

    deinit {
        vDSP_DFT_DestroySetup(forwardSetup)
        vDSP_DFT_DestroySetup(inverseSetup)
    }

    /// Frames produced for `count` input samples, matching `center = true`.
    func frameCount(_ count: Int) -> Int { count / hop + 1 }

    func forward(_ samples: [Float], bins: Int = Stft.binsDefault) -> Planes {
        let frames = frameCount(samples.count)
        var real = [Float](repeating: 0, count: bins * frames)
        var imag = [Float](repeating: 0, count: bins * frames)
        var inReal = [Float](repeating: 0, count: nFft)
        var inImag = [Float](repeating: 0, count: nFft)
        var outReal = [Float](repeating: 0, count: nFft)
        var outImag = [Float](repeating: 0, count: nFft)

        for frame in 0..<frames {
            let start = frame * hop - pad
            // Reflect rather than zero: `center = true` mirrors the signal at the edges, and a
            // zero-padded first frame would put a step change into the very first coefficients.
            for i in 0..<nFft {
                inReal[i] = reflected(samples, start + i) * window[i]
                inImag[i] = 0
            }
            vDSP_DFT_Execute(forwardSetup, inReal, inImag, &outReal, &outImag)
            for bin in 0..<bins {
                real[bin * frames + frame] = outReal[bin]
                imag[bin * frames + frame] = outImag[bin]
            }
        }
        return Planes(real: real, imag: imag, bins: bins, frames: frames)
    }

    /// Inverse transform, overlap-added and normalised by the summed squared window.
    ///
    /// Bins above `planes.bins` are taken as zero, which is what the model's own output implies:
    /// it only ever predicts the cropped range.
    func inverse(_ planes: Planes, length: Int) -> [Float] {
        let frames = planes.frames
        let fullBins = nFft / 2 + 1
        var output = [Float](repeating: 0, count: length + 2 * pad)
        var weight = [Float](repeating: 0, count: length + 2 * pad)
        var inReal = [Float](repeating: 0, count: nFft)
        var inImag = [Float](repeating: 0, count: nFft)
        var outReal = [Float](repeating: 0, count: nFft)
        var outImag = [Float](repeating: 0, count: nFft)

        for frame in 0..<frames {
            for i in 0..<nFft { inReal[i] = 0; inImag[i] = 0 }
            for bin in 0..<fullBins {
                let re: Float
                let im: Float
                if bin < planes.bins {
                    re = planes.real[bin * frames + frame]
                    im = planes.imag[bin * frames + frame]
                } else {
                    re = 0
                    im = 0
                }
                inReal[bin] = re
                inImag[bin] = im
                // The upper half is the conjugate mirror; the transform needs it spelled out.
                if bin > 0 && bin < fullBins - 1 {
                    inReal[nFft - bin] = re
                    inImag[nFft - bin] = -im
                }
            }
            vDSP_DFT_Execute(inverseSetup, inReal, inImag, &outReal, &outImag)

            let start = frame * hop
            let scale = 1 / Float(nFft)
            for i in 0..<nFft {
                let at = start + i
                if at >= output.count { break }
                output[at] += outReal[i] * scale * window[i]
                weight[at] += window[i] * window[i]
            }
        }

        return (0..<length).map { i in
            let w = weight[i + pad]
            return w > 1e-8 ? output[i + pad] / w : 0
        }
    }

    /// Mirrors at both edges, so an index outside the signal reads back into it.
    private func reflected(_ samples: [Float], _ index: Int) -> Float {
        if samples.isEmpty { return 0 }
        if index >= 0 && index < samples.count { return samples[index] }
        let last = samples.count - 1
        if last == 0 { return samples[0] }
        var i = index
        // Two reflections put any index back inside, however far out it started.
        while i < 0 || i > last {
            if i < 0 { i = -i }
            if i > last { i = 2 * last - i }
        }
        return samples[i]
    }

    // Everything below is fixed by the model and cannot be tuned independently of it.
    static let nFftDefault = 7680
    static let hopDefault = 1024

    /// The model predicts this many of the 3841 bins the transform produces.
    static let binsDefault = 3072

    /// Frames the model takes at once.
    static let framesDefault = 256

    /// Samples in one inference: hop x (frames - 1).
    static let chunk = hopDefault * (framesDefault - 1)

    /// Discarded from each end of a chunk — the edges the transform cannot resolve.
    static let trim = nFftDefault / 2

    /// Usable output from one chunk, once both ends are trimmed.
    static let usable = chunk - 2 * trim
}

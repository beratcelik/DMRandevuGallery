import CoreML
import Foundation

/// Pulls the voice out of a stretch of audio, so the beep can be laid over what is left and the
/// music underneath keeps playing.
///
/// The model is UVR's `UVR-MDX-NET-Voc_FT` — MIT, with the authors asking to be credited
/// (Ultimate Vocal Remover, Anjok07 and aufr33) — converted from ONNX to Core ML so this app does
/// not have to carry a second inference runtime beside the one the plate detector already uses.
/// Checked against the original on a real block of the operator's audio: 0.0001% mean error at
/// float32. Float16 was tried and was 13% out, which is not a rounding difference — it would leave
/// the voice audible under the beep.
///
/// It predicts the *vocal* spectrogram; the background is what remains once that is taken away,
/// which is why `compensation` matters: the model systematically under-predicts, and subtracting
/// its raw output leaves an audible ghost of the voice behind.
final class VoiceSeparator {

    struct SeparationFailedError: Error {
        var message: String
    }

    private let model: MLModel
    private let stft: Stft
    private let inputName: String

    init() throws {
        guard let url = Bundle.main.url(forResource: "VocalSeparator", withExtension: "mlmodelc")
        else {
            throw SeparationFailedError(message: "VocalSeparator is missing from the bundle")
        }
        let configuration = MLModelConfiguration()
        configuration.computeUnits = .all
        do {
            model = try MLModel(contentsOf: url, configuration: configuration)
        } catch {
            throw SeparationFailedError(message: "Could not load the separator: \(error)")
        }
        guard let name = model.modelDescription.inputDescriptionsByName.keys.first else {
            throw SeparationFailedError(message: "The separator declares no input")
        }
        inputName = name

        // The layout below is hard-coded to what this model declares. A different export — a
        // different band count or window — would be read as noise rather than refused, so it is
        // checked once at load instead.
        let shape = model.modelDescription.inputDescriptionsByName[name]?
            .multiArrayConstraint?.shape.map(\.intValue)
        let expected = [1, Self.planes, Stft.binsDefault, Stft.framesDefault]
        guard shape == expected else {
            throw SeparationFailedError(
                message: "The separator takes \(shape.map(String.init(describing:)) ?? "?"), "
                    + "expected \(expected)"
            )
        }
        stft = try Stft()
    }

    /// Separates one block of exactly `Stft.chunk` frames of stereo audio.
    ///
    /// Returns the estimated voice, same length and layout, already scaled by `compensation` — so
    /// the caller subtracts it directly.
    func vocals(in block: [[Float]]) throws -> [[Float]] {
        guard block.count == 2 else {
            throw SeparationFailedError(message: "The model works in stereo, got \(block.count)")
        }
        guard block[0].count == Stft.chunk else {
            throw SeparationFailedError(
                message: "Expected \(Stft.chunk) frames, got \(block[0].count)"
            )
        }

        let planeSize = Stft.binsDefault * Stft.framesDefault
        let input = try MLMultiArray(
            shape: [1, NSNumber(value: Self.planes), NSNumber(value: Stft.binsDefault),
                    NSNumber(value: Stft.framesDefault)],
            dataType: .float32
        )
        let transformed = block.map { stft.forward($0) }
        input.withUnsafeMutableBufferPointer(ofType: Float.self) { buffer, _ in
            var offset = 0
            // Interleaved as the model expects: each channel's real plane, then its imaginary one.
            for channel in transformed {
                channel.real.withUnsafeBufferPointer {
                    buffer.baseAddress!.advanced(by: offset)
                        .update(from: $0.baseAddress!, count: planeSize)
                }
                offset += planeSize
                channel.imag.withUnsafeBufferPointer {
                    buffer.baseAddress!.advanced(by: offset)
                        .update(from: $0.baseAddress!, count: planeSize)
                }
                offset += planeSize
            }
        }

        let output: MLMultiArray
        do {
            let provider = try MLDictionaryFeatureProvider(
                dictionary: [inputName: MLFeatureValue(multiArray: input)]
            )
            let result = try model.prediction(from: provider)
            guard let name = result.featureNames.first,
                  let array = result.featureValue(for: name)?.multiArrayValue
            else {
                throw SeparationFailedError(message: "The separator returned nothing")
            }
            output = array
        } catch let error as SeparationFailedError {
            throw error
        } catch {
            throw SeparationFailedError(message: "Separation failed: \(error)")
        }

        return output.withUnsafeBufferPointer(ofType: Float.self) { buffer -> [[Float]] in
            (0..<2).map { channel in
                let base = channel * 2 * planeSize
                let real = Array(UnsafeBufferPointer(
                    start: buffer.baseAddress!.advanced(by: base), count: planeSize
                ))
                let imag = Array(UnsafeBufferPointer(
                    start: buffer.baseAddress!.advanced(by: base + planeSize), count: planeSize
                ))
                var voice = stft.inverse(
                    Stft.Planes(
                        real: real, imag: imag,
                        bins: Stft.binsDefault, frames: Stft.framesDefault
                    ),
                    length: Stft.chunk
                )
                for i in voice.indices { voice[i] *= Self.compensation }
                return voice
            }
        }
    }

    /// Real and imaginary for each of two channels.
    static let planes = 4

    /// What the model's own metadata calls its compensation factor. It under-predicts the voice by
    /// roughly two per cent, and without this the subtraction leaves it audible.
    static let compensation: Float = 1.021
}

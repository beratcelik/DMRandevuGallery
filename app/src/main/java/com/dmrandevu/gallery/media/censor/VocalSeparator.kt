package com.dmrandevu.gallery.media.censor

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import androidx.media3.common.util.UnstableApi
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer

/**
 * Pulls the voice out of a stretch of audio, so the beep can be laid over what is left and the
 * music underneath keeps playing.
 *
 * The model is UVR's `UVR-MDX-NET-Voc_FT` — MIT, with the authors asking to be credited
 * (Ultimate Vocal Remover, Anjok07 and aufr33). It predicts the *vocal* spectrogram; the
 * background is what remains once that is taken away, which is why [COMPENSATION] matters: the
 * model systematically under-predicts, and subtracting its raw output leaves an audible ghost of
 * the voice behind.
 *
 * It works on fixed [Stft.CHUNK]-sample blocks at [PcmOps.SEPARATION_SAMPLE_RATE], stereo. Only
 * the middle [Stft.USABLE] samples of each block are trustworthy — the transform cannot resolve
 * the outermost half-window — so blocks are cut with that overlap already accounted for.
 */
@UnstableApi
class VocalSeparator(model: File) : Closeable {

    class SeparationFailedException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val session: OrtSession = try {
        // Deliberately the plain CPU provider. The spike found this model crashes ONNX Runtime's
        // CoreML backend outright on the desktop, and a hardware backend that silently answers
        // with something different here would leave the voice audible under the beep.
        environment.createSession(model.absolutePath, OrtSession.SessionOptions())
    } catch (e: Exception) {
        throw SeparationFailedException("Could not load ${model.name}", e)
    }

    private val inputName: String = session.inputNames.first()

    init {
        // The layout below is hard-coded to what this model declares. A different export — a
        // different band count or window — would be read as noise rather than refused, so it is
        // checked once at load instead.
        val shape = session.inputInfo[inputName]?.info?.let { it as? ai.onnxruntime.TensorInfo }
            ?.shape
        val expected = listOf(CHANNEL_PLANES.toLong(), Stft.BINS.toLong(), Stft.FRAMES.toLong())
        val actual = shape?.drop(1)?.toList()
        if (actual != expected) {
            session.close()
            throw SeparationFailedException(
                "${model.name} takes ${actual ?: "?"}, expected $expected"
            )
        }
    }

    private val stft = Stft()

    /**
     * Separates one block of exactly [Stft.CHUNK] frames of stereo audio.
     *
     * Returns the estimated voice, same length and layout, already scaled by [COMPENSATION] —
     * so the caller subtracts it directly.
     */
    fun vocalsIn(block: Array<FloatArray>): Array<FloatArray> {
        require(block.size == 2) { "The model works in stereo, got ${block.size} channels" }
        require(block[0].size == Stft.CHUNK) {
            "Expected ${Stft.CHUNK} frames, got ${block[0].size}"
        }

        val planes = block.map { stft.forward(it) }
        val input = FloatBuffer.allocate(CHANNEL_PLANES * Stft.BINS * Stft.FRAMES)
        // Interleaved as the model expects: each channel's real plane, then its imaginary one.
        for (channel in planes) {
            input.put(channel.real)
            input.put(channel.imag)
        }
        input.rewind()

        val shape = longArrayOf(
            1, CHANNEL_PLANES.toLong(), Stft.BINS.toLong(), Stft.FRAMES.toLong()
        )
        val output = try {
            OnnxTensor.createTensor(environment, input, shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { result ->
                    val tensorOut = result[0] as OnnxTensor
                    FloatArray(CHANNEL_PLANES * Stft.BINS * Stft.FRAMES).also {
                        tensorOut.floatBuffer.get(it)
                    }
                }
            }
        } catch (e: Exception) {
            throw SeparationFailedException("Separation failed", e)
        }

        val planeSize = Stft.BINS * Stft.FRAMES
        return Array(2) { channel ->
            val real = output.copyOfRange(channel * 2 * planeSize, (channel * 2 + 1) * planeSize)
            val imag = output.copyOfRange((channel * 2 + 1) * planeSize, (channel * 2 + 2) * planeSize)
            val voice = stft.inverse(
                Stft.Planes(real, imag, Stft.BINS, Stft.FRAMES),
                Stft.CHUNK
            )
            for (i in voice.indices) voice[i] *= COMPENSATION
            voice
        }
    }

    override fun close() = session.close()

    companion object {
        /** Real and imaginary for each of two channels. */
        const val CHANNEL_PLANES = 4

        /**
         * What the model's own metadata calls its compensation factor. It under-predicts the
         * voice by roughly two per cent, and without this the subtraction leaves it audible.
         */
        const val COMPENSATION = 1.021f
    }
}

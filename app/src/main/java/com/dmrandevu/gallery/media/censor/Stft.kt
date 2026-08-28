package com.dmrandevu.gallery.media.censor

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.cos

/**
 * The short-time Fourier transform the separation model was trained against.
 *
 * This is a port of `torch.stft`/`torch.istft` as UVR calls them, and it has to match rather than
 * merely resemble: the model is fed the coefficients directly, so a different window, a different
 * padding rule or a different normalisation is not a small numerical difference — it is a
 * different input than anything the network ever saw, and it answers with noise.
 *
 * The specifics that matter, all taken from the reference implementation rather than assumed:
 * periodic Hann, `center = true` with reflect padding, no normalisation, and the frequency axis
 * cropped to [BINS] of the [n_fft] / 2 + 1 the transform produces.
 *
 * [FloatFFT_1D] rather than anything hand-rolled because 7680 is 2^9 x 15 — a radix-2 transform
 * cannot do it at all.
 */
class Stft(private val nFft: Int = N_FFT, private val hop: Int = HOP) {

    private val fft = FloatFFT_1D(nFft.toLong())
    private val window = FloatArray(nFft) { hann(it) }
    private val pad = nFft / 2

    /** Frames produced for [samples] input, matching `center = true`. */
    fun frameCount(samples: Int) = samples / hop + 1

    /**
     * Forward transform of one channel.
     *
     * Returns real and imaginary planes, each [bins] by frames, laid out row-major by frequency —
     * the shape the model's input tensor wants once the channels are stacked.
     */
    fun forward(samples: FloatArray, bins: Int = BINS): Planes {
        val frames = frameCount(samples.size)
        val real = FloatArray(bins * frames)
        val imag = FloatArray(bins * frames)
        val buffer = FloatArray(nFft * 2)

        for (frame in 0 until frames) {
            val start = frame * hop - pad
            // Reflect rather than zero: `center = true` mirrors the signal at the edges, and a
            // zero-padded first frame would put a step change into the very first coefficients.
            for (i in 0 until nFft) {
                buffer[i * 2] = reflected(samples, start + i) * window[i]
                buffer[i * 2 + 1] = 0f
            }
            fft.complexForward(buffer)
            for (bin in 0 until bins) {
                real[bin * frames + frame] = buffer[bin * 2]
                imag[bin * frames + frame] = buffer[bin * 2 + 1]
            }
        }
        return Planes(real, imag, bins, frames)
    }

    /**
     * Inverse transform, overlap-added and normalised by the summed squared window.
     *
     * Bins above [Planes.bins] are taken as zero, which is what the model's own output implies:
     * it only ever predicts the cropped range.
     */
    fun inverse(planes: Planes, length: Int): FloatArray {
        val frames = planes.frames
        val output = FloatArray(length + 2 * pad)
        val weight = FloatArray(length + 2 * pad)
        val buffer = FloatArray(nFft * 2)
        val fullBins = nFft / 2 + 1

        for (frame in 0 until frames) {
            java.util.Arrays.fill(buffer, 0f)
            for (bin in 0 until fullBins) {
                val re: Float
                val im: Float
                if (bin < planes.bins) {
                    re = planes.real[bin * frames + frame]
                    im = planes.imag[bin * frames + frame]
                } else {
                    re = 0f
                    im = 0f
                }
                buffer[bin * 2] = re
                buffer[bin * 2 + 1] = im
                // The upper half is the conjugate mirror; the transform needs it spelled out.
                if (bin in 1 until fullBins - 1) {
                    val mirror = nFft - bin
                    buffer[mirror * 2] = re
                    buffer[mirror * 2 + 1] = -im
                }
            }
            fft.complexInverse(buffer, true)

            val start = frame * hop
            for (i in 0 until nFft) {
                val at = start + i
                if (at >= output.size) break
                output[at] += buffer[i * 2] * window[i]
                weight[at] += window[i] * window[i]
            }
        }

        val result = FloatArray(length)
        for (i in 0 until length) {
            val w = weight[i + pad]
            result[i] = if (w > 1e-8f) output[i + pad] / w else 0f
        }
        return result
    }

    /** Mirrors at both edges, so an index outside the signal reads back into it. */
    private fun reflected(samples: FloatArray, index: Int): Float {
        if (samples.isEmpty()) return 0f
        if (index in samples.indices) return samples[index]
        val last = samples.size - 1
        if (last == 0) return samples[0]
        var i = index
        // Two reflections put any index back inside, however far out it started.
        while (i < 0 || i > last) {
            if (i < 0) i = -i
            if (i > last) i = 2 * last - i
        }
        return samples[i]
    }

    private fun hann(i: Int) = (0.5 - 0.5 * cos(2.0 * PI * i / nFft)).toFloat()

    /** One channel's coefficients: [real] and [imag], each [bins] rows of [frames]. */
    class Planes(
        val real: FloatArray,
        val imag: FloatArray,
        val bins: Int,
        val frames: Int
    )

    companion object {
        /** Everything below is fixed by the model and cannot be tuned independently of it. */
        const val N_FFT = 7680
        const val HOP = 1024

        /** The model predicts this many of the 3841 bins the transform produces. */
        const val BINS = 3072

        /** Frames the model takes at once. */
        const val FRAMES = 256

        /** Samples in one inference: hop x (frames - 1). */
        const val CHUNK = HOP * (FRAMES - 1)

        /** Discarded from each end of a chunk — the edges the transform cannot resolve. */
        const val TRIM = N_FFT / 2

        /** Usable output from one chunk, once both ends are trimmed. */
        const val USABLE = CHUNK - 2 * TRIM
    }
}

package com.dmrandevu.gallery.media.censor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Checked against `torch.stft` itself, not against a second opinion of my own.
 *
 * The separation model is fed these coefficients directly, so "close enough to look right" is not
 * a standard that means anything here — a window or padding rule that differs from the one the
 * network was trained on is a different input than it has ever seen.
 */
class StftTest {

    /** Values printed by torch for the same input; see the class comment. */
    private val input = floatArrayOf(
        -0.8201f, 0.3956f, 0.8989f, -1.3884f, -0.1670f, 0.2851f, -0.6411f, -0.8937f,
        0.9265f, -0.5355f, -1.1597f, -0.4602f, 0.7085f, 1.0128f, 0.2304f, 1.0902f,
        -1.5827f, -0.3246f, 1.9264f, -0.3300f, 0.1984f, 0.7821f, 1.0391f, -0.7245f,
        -0.1354f, 0.7471f, 0.6118f, 1.8678f, 2.5116f, -1.2548f, 0.8165f, -1.0654f,
        -1.6370f, 0.1577f, 0.3957f, -1.3677f, -0.1007f, 0.2370f, 0.6327f, -0.0917f
    )

    private val small get() = Stft(nFft = 16, hop = 4)

    @Test
    fun `frame count matches the reference`() {
        assertEquals(11, small.frameCount(input.size))
    }

    @Test
    fun `first frame matches torch`() {
        val planes = small.forward(input, bins = 9)
        val expectedReal = floatArrayOf(
            -0.691018f, -0.361752f, 1.069963f, -0.215532f, -2.333844f,
            4.291421f, -2.376163f, -0.433736f, 1.410307f
        )
        for (bin in expectedReal.indices) {
            assertEquals(
                "real bin $bin",
                expectedReal[bin], planes.real[bin * planes.frames + 0], 1e-3f
            )
            assertEquals(
                "imag bin $bin",
                0f, planes.imag[bin * planes.frames + 0], 1e-3f
            )
        }
    }

    @Test
    fun `a later frame matches torch`() {
        val planes = small.forward(input, bins = 9)
        val expectedReal = floatArrayOf(
            0.312728f, -0.797862f, 1.406f, -0.904846f, 0.98538f,
            -1.900113f, 0.6672f, 0.76882f, -0.761887f
        )
        val expectedImag = floatArrayOf(
            0f, 1.5842f, -2.499522f, 3.149599f, -0.492092f,
            -1.569347f, -0.878475f, 1.883654f, 0f
        )
        for (bin in expectedReal.indices) {
            assertEquals(
                "real bin $bin",
                expectedReal[bin], planes.real[bin * planes.frames + 3], 1e-3f
            )
            assertEquals(
                "imag bin $bin",
                expectedImag[bin], planes.imag[bin * planes.frames + 3], 1e-3f
            )
        }
    }

    @Test
    fun `round trip returns the signal it started from`() {
        val stft = small
        val planes = stft.forward(input, bins = 9)
        val back = stft.inverse(planes, input.size)
        val worst = input.indices.maxOf { abs(input[it] - back[it]) }
        assertTrue("worst error $worst", worst < 1e-3f)
    }

    @Test
    fun `round trip at the size the model actually uses`() {
        val stft = Stft()
        val samples = FloatArray(Stft.CHUNK) {
            (sin(2.0 * PI * 440 * it / 44_100) * 0.4 +
                sin(2.0 * PI * 3_000 * it / 44_100) * 0.2).toFloat()
        }
        val back = stft.inverse(stft.forward(samples, bins = Stft.N_FFT / 2 + 1), samples.size)

        // Judged over the interior: the outermost half-window is exactly what TRIM discards.
        val from = Stft.TRIM
        val to = samples.size - Stft.TRIM
        var signal = 0.0
        var noise = 0.0
        for (i in from until to) {
            signal += samples[i].toDouble() * samples[i]
            val error = (samples[i] - back[i]).toDouble()
            noise += error * error
        }
        val snr = 10 * kotlin.math.log10(signal / noise.coerceAtLeast(1e-20))
        assertTrue("SNR was $snr dB", snr > 60)
    }

    @Test
    fun `round trip survives noise, not just tones`() {
        val stft = Stft()
        val random = Random(11)
        val samples = FloatArray(Stft.CHUNK) { random.nextFloat() * 2f - 1f }
        val back = stft.inverse(stft.forward(samples, bins = Stft.N_FFT / 2 + 1), samples.size)

        val from = Stft.TRIM
        val to = samples.size - Stft.TRIM
        var signal = 0.0
        var noise = 0.0
        for (i in from until to) {
            signal += samples[i].toDouble() * samples[i]
            val error = (samples[i] - back[i]).toDouble()
            noise += error * error
        }
        val snr = 10 * kotlin.math.log10(signal / noise.coerceAtLeast(1e-20))
        assertTrue("SNR was $snr dB", snr > 60)
    }

    @Test
    fun `the model sized transform has the shape the model declares`() {
        val stft = Stft()
        val planes = stft.forward(FloatArray(Stft.CHUNK))
        assertEquals(Stft.BINS, planes.bins)
        assertEquals(Stft.FRAMES, planes.frames)
        assertEquals(Stft.BINS * Stft.FRAMES, planes.real.size)
    }

    @Test
    fun `chunk arithmetic agrees with the reference`() {
        assertEquals(261_120, Stft.CHUNK)
        assertEquals(3_840, Stft.TRIM)
        assertEquals(253_440, Stft.USABLE)
    }

    @Test
    fun `dropping the bins above the model's range loses little`() {
        // The model only predicts 3072 of 3841 bins, so everything above ~17.6 kHz is discarded
        // and comes back as silence. Worth stating: it is a real change to the audio, and the
        // reason the patched slice is crossfaded rather than dropped in whole.
        val stft = Stft()
        val samples = FloatArray(Stft.CHUNK) {
            sin(2.0 * PI * 1_000 * it / 44_100).toFloat() * 0.5f
        }
        val cropped = stft.inverse(stft.forward(samples, bins = Stft.BINS), samples.size)
        val from = Stft.TRIM
        val to = samples.size - Stft.TRIM
        val worst = (from until to).maxOf { abs(samples[it] - cropped[it]) }
        assertTrue("a 1 kHz tone should survive cropping, worst $worst", worst < 0.01f)
    }

    @Test
    fun `reflect padding is used at the edges, not zeros`() {
        // A constant signal stays constant under reflection; zero padding would dip at both ends.
        val stft = Stft(nFft = 16, hop = 4)
        val flat = FloatArray(40) { 1f }
        val back = stft.inverse(stft.forward(flat, bins = 9), flat.size)
        val worst = flat.indices.maxOf { abs(flat[it] - back[it]) }
        assertTrue("worst deviation $worst", worst < 1e-3f)
    }
}

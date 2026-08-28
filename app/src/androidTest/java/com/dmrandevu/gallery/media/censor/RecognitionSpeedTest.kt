package com.dmrandevu.gallery.media.censor

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.dmrandevu.whisper.WhisperContext
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * How long one recognition pass really takes on this phone.
 *
 * Written after a debug build turned out to be compiling ggml at -O0, which made a single pass
 * take ten minutes instead of seconds. That is invisible from the Kotlin side and shows up only
 * as an export that never seems to finish, so the number is worth having on record.
 *
 * Needs the models installed and a raw 16 kHz mono PCM file pushed to
 * /data/local/tmp/bench16k.pcm; skips otherwise.
 */
@UnstableApi
class RecognitionSpeedTest {

    @Test
    fun measureOnePass() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val models = CensorModels(context, okhttp3.OkHttpClient())
        val model = models.fileFor(CensorModels.Model.WHISPER_BASE)
        val pcm = File("/data/local/tmp/bench16k.pcm")
        assumeTrue("model not installed", models.isInstalled(CensorModels.Model.WHISPER_BASE))
        assumeTrue("no benchmark audio", pcm.exists())

        val bytes = pcm.readBytes()
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(shorts.remaining()) { shorts.get(it) / 32768f }
        val seconds = samples.size / 16_000f

        Log.i(TAG, "system: ${WhisperContext.systemInfo()}")

        val loadedAt = System.currentTimeMillis()
        val whisper = WhisperContext.load(model)
        Log.i(TAG, "model loaded in ${System.currentTimeMillis() - loadedAt} ms")

        try {
            for (mode in listOf(true, false)) {
                val startedAt = System.currentTimeMillis()
                val segments = whisper.transcribe(samples, noTimestamps = mode)
                val took = System.currentTimeMillis() - startedAt
                Log.i(
                    TAG,
                    "noTimestamps=$mode: ${segments.size} segments for ${seconds}s of audio " +
                        "in $took ms (${"%.2f".format(took / 1000f / seconds)}x realtime)"
                )
            }
        } finally {
            whisper.close()
        }
    }

    private companion object {
        const val TAG = "CensorBench"
    }
}

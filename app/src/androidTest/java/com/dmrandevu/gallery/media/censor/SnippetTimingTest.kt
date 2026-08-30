package com.dmrandevu.gallery.media.censor

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.test.platform.app.InstrumentationRegistry
import com.dmrandevu.whisper.WhisperContext
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Does recognising a short stretch on its own place the word better than recognising the whole
 * clip does?
 *
 * The reason to think it might: the timing error looks like a window-boundary effect. whisper
 * works in 30-second windows and the first token of each inherits the window's start, so a word
 * that lands early in a window is reported early. A few seconds of audio is one window, and a
 * word placed in the middle of it has no boundary to be dragged to.
 *
 * The known answer: "Amına" begins at 30.68 s in the full clip.
 */
@UnstableApi
class SnippetTimingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val models = CensorModels(context, OkHttpClient())

    @Test
    fun whereDoesASnippetPutTheWord() = runBlocking {
        val clip = File("/data/local/tmp/censor_test.mp4")
        assumeTrue("no clip", clip.exists())
        assumeTrue("models missing", models.isInstalled(CensorModels.Model.WHISPER_BASE))

        val audio = AudioTrackDecoder().decode(clip) {}!!
        val model = models.fileFor(CensorModels.Model.WHISPER_BASE)

        // Rough position as the full-clip pass reports it, which is what the app would have.
        for (fromMs in listOf(28_000L, 26_000L)) {
            val toMs = fromMs + 8_000L
            val snippet = cut(audio, fromMs, toMs)

            val whisper = WhisperContext.load(model)
            try {
                for (maxLen in listOf(1, 0)) {
                    val segments = whisper.transcribe(
                        snippet, noTimestamps = false, maxLen = maxLen
                    )
                    val label = if (maxLen == 1) "per-token" else "per-phrase"
                    Log.i(TAG, "$fromMs-$toMs $label: ${segments.size} segments (want 30680)")
                    segments.filter { it.text.isNotBlank() }.take(10).forEach {
                        Log.i(
                            TAG,
                            "   ${it.text.trim().take(30)} @ ${fromMs + it.startMs}" +
                                "-${fromMs + it.endMs}ms"
                        )
                    }
                }
            } finally {
                whisper.close()
            }
        }
    }

    /** 16 kHz mono floats for the given stretch of the decoded track. */
    private fun cut(
        audio: AudioTrackDecoder.DecodedAudio,
        fromMs: Long,
        toMs: Long
    ): FloatArray {
        val channels = audio.channelCount
        val from = (fromMs * audio.sampleRate / 1000).toInt()
        val to = (toMs * audio.sampleRate / 1000).toInt().coerceAtMost(audio.frameCount.toInt())
        val slice = ShortArray((to - from) * channels)
        for (i in slice.indices) {
            val at = from * channels + i
            slice[i] = if (at < audio.samples.size) audio.samples[at] else 0
        }
        return PcmOps.forRecognition(slice, channels, audio.sampleRate, 1f)
    }

    private companion object { const val TAG = "CensorSnippet" }
}

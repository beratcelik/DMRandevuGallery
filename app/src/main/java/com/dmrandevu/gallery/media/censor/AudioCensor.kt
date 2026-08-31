package com.dmrandevu.gallery.media.censor

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * Works out what to censor in a video, and renders the audio that will replace it.
 *
 * The stages are deliberately sequential and each is closed before the next begins. Two speech
 * models and a separation model together are close to a third of a gigabyte of weights, and
 * holding them alongside a decoded audio track is how this gets killed on a mid-range phone.
 *
 * Nothing here falls back to leaving the audio alone. Every failure throws, because the quiet
 * alternative is an export with the swearing still in it — which is the only thing this filter
 * was ever asked to prevent.
 */
@UnstableApi
class AudioCensor(
    private val context: Context,
    private val models: CensorModels
) {

    class CensorFailedException(
        message: String,
        cause: Throwable? = null,
        /**
         * True when the audio was understood perfectly well and the swearing simply could not be
         * given a time. Worth telling apart: it means the video really does need handling, not
         * that the app broke.
         */
        val heardButUnplaced: Boolean = false
    ) : Exception(message, cause)

    /**
     * Decodes [input], finds the swearing, and renders the replacement audio.
     *
     * Returns an empty plan when there is nothing to do — no audio track, or no swearing — which
     * the caller treats as "leave this video alone" rather than as a failure.
     *
     * [onProgress] reports 0..1 across every stage.
     */
    suspend fun analyze(
        input: File,
        tiers: Set<ProfanityLexicon.Tier>,
        manual: List<CensorWindow> = emptyList(),
        onProgress: (Int) -> Unit
    ): CensorPlan = withContext(Dispatchers.Default) {
        try {
            analyzeOrThrow(input, tiers, manual, onProgress)
        } catch (e: CensorFailedException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw CensorFailedException("Censoring the audio failed", e)
        }
    }

    private suspend fun analyzeOrThrow(
        input: File,
        tiers: Set<ProfanityLexicon.Tier>,
        manual: List<CensorWindow>,
        onProgress: (Int) -> Unit
    ): CensorPlan {
        if (!models.allInstalled) {
            throw CensorFailedException("The censor models are not on the phone yet")
        }

        val audio = AudioTrackDecoder().decode(input) { fraction ->
            onProgress(band(DECODE_FROM, DECODE_TO, fraction))
        } ?: return CensorPlan.nothing()

        if (audio.frameCount == 0L) return CensorPlan.nothing()

        // Marked by hand means the listening is already done, and done by someone who can hear
        // what the recognizer cannot. Running it anyway costs three and a half minutes to be told
        // what the operator has already said.
        val windows = if (manual.isNotEmpty()) {
            Log.i(TAG, "${manual.size} marked stretches; not running recognition")
            onProgress(RECOGNISE_TO)
            mergeAll(manual, audio)
        } else {
            SpeechRecognizer(models).use { recognizer ->
                val found = recognizer.findProfanity(audio, tiers) { fraction ->
                    onProgress(band(RECOGNISE_FROM, RECOGNISE_TO, fraction))
                }
                windowsFor(audio, found)
            }
        }
        if (windows.isEmpty()) return CensorPlan.nothing()

        val patches = VocalSeparator(models.fileFor(CensorModels.Model.VOCAL_SEPARATOR)).use {
            BeepPatcher.render(audio, windows, it) { fraction ->
                onProgress(band(SEPARATE_FROM, SEPARATE_TO, fraction))
            }
        }

        onProgress(SEPARATE_TO)
        return CensorPlan(
            sampleRate = audio.sampleRate,
            channelCount = audio.channelCount,
            sourceFrameCount = audio.frameCount,
            windows = windows,
            patches = patches
        )
    }

    /**
     * Turns what the recognizer heard into beeps, and refuses to let swearing through unplaced.
     */
    private fun windowsFor(
        audio: AudioTrackDecoder.DecodedAudio,
        found: SpeechRecognizer.Result
    ): List<CensorWindow> {
        // A second look at a few seconds around each hit places it to within a frame or two, so
        // the beep can be tight. Where that could not be confirmed the rough timing stands, and
        // with it a window wide enough to cover being a second out.
        val allowance = if (found.refined) {
            CensorWindows.RESIDUAL_ALLOWANCE_US
        } else {
            CensorWindows.SHIFT_ALLOWANCE_US
        }
        val placed = CensorWindows.build(
            found.words, found.hits, audio.durationUs, shiftAllowanceUs = allowance
        )
        if (found.unplaced.isEmpty()) return placed

        // Something was heard and could not be given a time, so the export stops here.
        //
        // Not a judgement call: a video with some of its swearing beeped is still a video with
        // swearing in it, and it is worse than an obvious failure because it looks like it
        // worked. The timing pass hears almost nothing on a loud clip — eleven words in
        // sixty-five seconds on the one that prompted this — and a verdict with no word to
        // attach to cannot be placed at all.
        //
        // Walking the clip in short snippets was tried as a way out and dropped: it cost five
        // minutes and found nothing, because whisper leans on context and a few seconds of
        // shouting over music reads to it as music.
        throw CensorFailedException(
            "Heard ${found.unplaced.distinct().joinToString(", ")} but could not place " +
                "${if (placed.isEmpty()) "any of it" else "all of it"}",
            heardButUnplaced = true
        )
    }

    /** Through the same arithmetic the rest uses, so overlapping beeps become one. */
    private fun mergeAll(
        windows: List<CensorWindow>,
        audio: AudioTrackDecoder.DecodedAudio
    ): List<CensorWindow> {
        val asWords = windows.map { TimedWord("", it.startUs, it.endUs) }
        return CensorWindows.build(
            asWords,
            asWords.indices.toSet(),
            audio.durationUs,
            // Already placed; they need no allowance for a timing that was never guessed.
            shiftAllowanceUs = 0
        )
    }

    private fun band(from: Int, to: Int, fraction: Float) =
        from + ((to - from) * fraction).toInt().coerceIn(0, to - from)

    private companion object {
        // Recognition dominates: five passes over the whole track against one decode and a few
        // seconds of separation.
        const val DECODE_FROM = 0
        const val DECODE_TO = 8
        const val RECOGNISE_FROM = 8
        const val RECOGNISE_TO = 88
        const val SEPARATE_FROM = 88
        const val SEPARATE_TO = 100

        const val TAG = "CensorAsr"
    }
}

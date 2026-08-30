package com.dmrandevu.gallery.media.censor

import android.content.Context
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

    class CensorFailedException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

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
        onProgress: (Int) -> Unit
    ): CensorPlan = withContext(Dispatchers.Default) {
        try {
            analyzeOrThrow(input, tiers, onProgress)
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
        onProgress: (Int) -> Unit
    ): CensorPlan {
        if (!models.allInstalled) {
            throw CensorFailedException("The censor models are not on the phone yet")
        }

        val audio = AudioTrackDecoder().decode(input) { fraction ->
            onProgress(band(DECODE_FROM, DECODE_TO, fraction))
        } ?: return CensorPlan.nothing()

        if (audio.frameCount == 0L) return CensorPlan.nothing()

        val found = SpeechRecognizer(models).use { recognizer ->
            recognizer.findProfanity(audio, tiers) { fraction ->
                onProgress(band(RECOGNISE_FROM, RECOGNISE_TO, fraction))
            }
        }

        if (found.hits.isEmpty()) return CensorPlan.nothing()

        // The recognizer's timings run ahead of the audio; how far is measured per clip rather
        // than assumed, because assuming the worst means a second of beep before every word.
        val calibration = TimingCalibration.estimate(audio, found.words)
        val words = if (calibration.shiftUs == 0L) {
            found.words
        } else {
            found.words.map {
                it.copy(
                    startUs = it.startUs + calibration.shiftUs,
                    endUs = it.endUs + calibration.shiftUs
                )
            }
        }
        val windows = CensorWindows.build(
            words,
            found.hits,
            audio.durationUs,
            shiftAllowanceUs = if (calibration.confident) {
                CensorWindows.RESIDUAL_ALLOWANCE_US
            } else {
                CensorWindows.SHIFT_ALLOWANCE_US
            }
        )
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
    }
}

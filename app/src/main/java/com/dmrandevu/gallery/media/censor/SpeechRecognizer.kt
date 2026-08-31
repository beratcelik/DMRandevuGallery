package com.dmrandevu.gallery.media.censor

import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.dmrandevu.whisper.WhisperContext
import java.io.Closeable
import java.io.File

/**
 * Finds the swearing in a decoded audio track, and says when each one happens.
 *
 * It takes several passes to do that, for two reasons the spike measured rather than guessed:
 *
 * - **The recognizer will not give good words and good times at the same time.** With timestamps
 *   suppressed it transcribes swearing faithfully but reports one thirty-second block, which
 *   cannot place a beep. With timestamps on it emits a word at a time, but quietly swaps the
 *   swearing for an innocent near-homophone — the same second of audio came back as "sikeceğim"
 *   one way and "çıkacağım" the other.
 * - **Slowing the audio down changes what it hears.** That "sikeceğim" only appears at 0.75×
 *   speed; every real-time pass wrote the harmless word instead.
 *
 * So the detection passes hunt for words, the timing pass holds the clock, and [WordAlignment]
 * carries each verdict from one to the other.
 */
@UnstableApi
class SpeechRecognizer(
    private val models: CensorModels,
    private val lexicon: ProfanityLexicon = ProfanityLexicon()
) : Closeable {

    class RecognitionFailedException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /**
     * What the timing pass heard, and which of those words have to be beeped.
     *
     * [refined] says a second pass confirmed where the swearing is, so the caller can beep a
     * tight window instead of a defensive one.
     */
    data class Result(
        val words: List<TimedWord>,
        val hits: Set<Int>,
        val refined: Boolean = false,
        /**
         * Swearing a detection pass heard but that could not be given a time.
         *
         * Never silently dropped. The timing pass sometimes hears almost nothing on a loud clip,
         * and then there is no word for a verdict to attach to — which used to mean the app knew
         * the video had swearing in it and handed it over uncensored anyway.
         */
        val unplaced: List<String> = emptyList()
    )

    private var loaded: WhisperContext? = null
    private var loadedFrom: File? = null

    /**
     * [audio] is the whole decoded track. [onProgress] reports 0..1 across every pass.
     */
    suspend fun findProfanity(
        audio: AudioTrackDecoder.DecodedAudio,
        tiers: Set<ProfanityLexicon.Tier> = setOf(ProfanityLexicon.Tier.PROFANITY),
        onProgress: (Float) -> Unit
    ): Result {
        var done = 0f
        var expected = BASE_PASSES.size.toFloat()
        fun step(): Float {
            done += 1f
            return (done / expected).coerceAtMost(1f)
        }

        // The timing pass first: everything else is measured against its word list.
        val timingPass = BASE_PASSES.first { it.carriesTiming }
        val startedAt = System.currentTimeMillis()
        val words = try {
            timedWords(audio, timingPass)
        } catch (e: WhisperContext.TranscriptionFailedException) {
            throw RecognitionFailedException("Recognition failed", e)
        }
        Log.i(TAG, "timing pass: ${words.size} words in ${System.currentTimeMillis() - startedAt} ms")
        onProgress(step())

        if (words.isEmpty()) return Result(emptyList(), emptySet())

        val hits = lexicon.hits(words.map { it.text }, tiers).toMutableSet()
        val timingText = words.map { it.text }
        val unplaced = mutableListOf<String>()

        // The larger model is three times slower and is only worth its minutes in the case the
        // spike actually found it useful: base occasionally goes deaf on a clip and returns
        // nothing but "[MÜZİK ÇALIYOR]" where small transcribes it in full. When base has clearly
        // heard the speech, small adds four minutes to find the same words — and on the one clip
        // with real swearing it was small that sanitised it, not base.
        // Judged by how much of the clip came back as words, not by how many there are. An
        // absolute count cannot tell a short clip from a deaf one: eleven words is plenty for
        // five seconds and almost nothing for sixty-five, and on a sixty-five second clip of
        // shouting that is exactly what base returned — eleven words, over the old threshold of
        // eight, so the larger model never ran and four separate swear words went out uncensored.
        val seconds = (audio.durationUs / 1_000_000.0).coerceAtLeast(1.0)
        val rate = words.size / seconds
        val deaf = rate < DEAF_WORDS_PER_SECOND
        val remaining = BASE_PASSES.filterNot { it.carriesTiming } +
            if (deaf) SMALL_PASSES else emptyList()
        if (deaf) {
            expected += SMALL_PASSES.size
            Log.i(
                TAG,
                "base heard ${words.size} words in ${"%.0f".format(seconds)}s " +
                    "(${"%.2f".format(rate)}/s); escalating to the larger model"
            )
        }

        for (pass in remaining) {
            val passStartedAt = System.currentTimeMillis()
            val heard = try {
                detectionText(audio, pass)
            } catch (e: WhisperContext.TranscriptionFailedException) {
                throw RecognitionFailedException("Recognition failed", e)
            }
            val paired = WordAlignment.align(heard, timingText)
            for (pair in paired) {
                if (lexicon.isProfane(heard[pair.detectionIndex], tiers)) {
                    hits.add(pair.timingIndex)
                }
            }
            // Anything this pass accused that alignment could not pair off. The timing pass never
            // heard it, so it has no time and cannot be beeped — but it was still heard.
            val placed = paired.map { it.detectionIndex }.toSet()
            heard.forEachIndexed { index, word ->
                if (index !in placed && lexicon.isProfane(word, tiers)) unplaced.add(word)
            }
            Log.i(
                TAG,
                "${pass.model.fileName} at ${pass.speed}x: ${heard.size} words in " +
                    "${System.currentTimeMillis() - passStartedAt} ms"
            )
            onProgress(step())
        }
        if (hits.isEmpty()) return Result(words, hits, unplaced = unplaced)

        // The words are right but the times are not; a short second look fixes that. Done here,
        // while the model this needs is still the one that is loaded.
        val refiner = TimingRefiner(lexicon) { samples ->
            context(timingPass.model).transcribe(samples, noTimestamps = false)
        }
        val refinements = refiner.refine(audio, words, hits, tiers)
        val retimed = words.toMutableList()
        var everyRunRefined = refinements.isNotEmpty()
        for ((run, refined) in refinements) {
            if (refined == null) {
                everyRunRefined = false
                continue
            }
            retimed[run.first] = retimed[run.first].copy(startUs = refined.startUs)
            retimed[run.last] = retimed[run.last].copy(endUs = refined.endUs)
            // The middle of a run is swallowed by the ends once the windows merge, so it only
            // has to stay inside them.
            for (index in run) {
                val word = retimed[index]
                retimed[index] = word.copy(
                    startUs = word.startUs.coerceIn(refined.startUs, refined.endUs),
                    endUs = word.endUs.coerceIn(refined.startUs, refined.endUs)
                )
            }
            retimed[run.first] = retimed[run.first].copy(startUs = refined.startUs)
            retimed[run.last] = retimed[run.last].copy(endUs = refined.endUs)
        }
        Log.i(TAG, "refined ${refinements.count { it.value != null }} of ${refinements.size} runs")
        return Result(retimed, hits, refined = everyRunRefined, unplaced = unplaced)
    }

    /** The pass that owns the clock: a segment per token, reassembled into words. */
    private suspend fun timedWords(
        audio: AudioTrackDecoder.DecodedAudio,
        pass: Pass
    ): List<TimedWord> {
        val samples = PcmOps.forRecognition(
            audio.samples, audio.channelCount, audio.sampleRate, pass.speed
        )
        val segments = context(pass.model).transcribe(samples, noTimestamps = false)
        return WordAssembly.fromTokens(segments)
    }

    /** A detection pass: words only, no usable times. */
    private suspend fun detectionText(
        audio: AudioTrackDecoder.DecodedAudio,
        pass: Pass
    ): List<String> {
        val samples = PcmOps.forRecognition(
            audio.samples, audio.channelCount, audio.sampleRate, pass.speed
        )
        return context(pass.model).transcribe(samples, noTimestamps = true)
            .flatMap { it.text.trim().split(WHITESPACE) }
            .filter { it.isNotBlank() }
    }

    /**
     * Turns whisper's one-token segments into words.
     *
     * A token that begins with a space starts a new word; the rest are the middle of the one
     * being built. That is how Turkish comes back — "MÜZİK" arrives as M, Ü, Z, İ, K — so without
     * this the lexicon would be matching single letters.
     */
    /**
     * Models are loaded one at a time and the previous one dropped.
     *
     * Base and small together are a quarter of a gigabyte of weights; holding both while a video
     * decode is also in memory is how this gets killed on a mid-range phone. The passes are
     * ordered so each model is used for everything it is needed for before the next is loaded.
     */
    private suspend fun context(model: CensorModels.Model): WhisperContext {
        val file = models.fileFor(model)
        loaded?.let { existing ->
            if (loadedFrom == file) return existing
            existing.close()
            loaded = null
        }
        if (!models.isInstalled(model)) {
            throw RecognitionFailedException("${model.fileName} is not on the phone")
        }
        return try {
            WhisperContext.load(file, alignmentFor(model)).also {
                loaded = it
                loadedFrom = file
            }
        } catch (e: Exception) {
            throw RecognitionFailedException("Could not load ${model.fileName}", e)
        }
    }

    /**
     * Alignment is off, and this is the awkward part of the story.
     *
     * whisper refuses to run cross-attention alignment alongside flash attention and silently
     * turns the alignment off rather than the flash. Forcing flash off to get alignment works on
     * the desktop, but on this phone's CPU backend it collapses the transcription: the same clip
     * that yields 106 tokens with flash on yields 22 without it, losing most of the speech. A
     * precise timing for a word the recognizer never heard is worth nothing, so the transcription
     * wins and [CensorWindows] is built to tolerate the coarser timings instead.
     *
     * The plumbing is kept because it is correct and the choice may flip: whisper.cpp 1.9.2
     * behaves this way on arm64 CPU, and a later one may not.
     */
    private fun alignmentFor(model: CensorModels.Model) = WhisperContext.Alignment.NONE

    /** Stops whichever pass is running; the coroutine's own cancellation does the rest. */
    fun abort() {
        loaded?.abort()
    }

    override fun close() {
        loaded?.close()
        loaded = null
        loadedFrom = null
    }

    private data class Pass(
        val model: CensorModels.Model,
        val speed: Float,
        /** Exactly one pass carries timings; the others only contribute words. */
        val carriesTiming: Boolean
    )

    private companion object {
        const val TAG = "CensorAsr"
        val WHITESPACE = Regex("\\s+")

        /**
         * Below this many words a second, base is taken to have missed the speech rather than to
         * have heard a quiet clip, and the larger model is brought in.
         *
         * The clip this was tuned on runs at 1.34 words a second. The one that slipped through
         * managed 0.17.
         */
        const val DEAF_WORDS_PER_SECOND = 0.5

        /**
         * Base carries the clock: it is the quicker of the two and the one measured to transcribe
         * swearing most faithfully — on the operator's own clip, small wrote "Ama ne kodumu" for
         * the phrase base heard correctly.
         *
         * Timed on a Galaxy S22+ over 35 seconds of audio: the timestamped pass takes about 17
         * seconds and each detection pass 25, so all three together are near a minute.
         */
        val BASE_PASSES = listOf(
            Pass(CensorModels.Model.WHISPER_BASE, speed = 1f, carriesTiming = true),
            Pass(CensorModels.Model.WHISPER_BASE, speed = 1f, carriesTiming = false),
            Pass(CensorModels.Model.WHISPER_BASE, speed = 0.75f, carriesTiming = false)
        )

        /** Run only when base came back with almost nothing; roughly three minutes more. */
        val SMALL_PASSES = listOf(
            Pass(CensorModels.Model.WHISPER_SMALL, speed = 1f, carriesTiming = false),
            Pass(CensorModels.Model.WHISPER_SMALL, speed = 0.75f, carriesTiming = false)
        )
    }
}

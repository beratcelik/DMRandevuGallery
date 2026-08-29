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

    /** What the timing pass heard, and which of those words have to be beeped. */
    data class Result(val words: List<TimedWord>, val hits: Set<Int>)

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

        // The larger model is three times slower and is only worth its minutes in the case the
        // spike actually found it useful: base occasionally goes deaf on a clip and returns
        // nothing but "[MÜZİK ÇALIYOR]" where small transcribes it in full. When base has clearly
        // heard the speech, small adds four minutes to find the same words — and on the one clip
        // with real swearing it was small that sanitised it, not base.
        val deaf = words.size < DEAF_THRESHOLD
        val remaining = BASE_PASSES.filterNot { it.carriesTiming } +
            if (deaf) SMALL_PASSES else emptyList()
        if (deaf) {
            expected += SMALL_PASSES.size
            Log.i(TAG, "only ${words.size} words from base; escalating to the larger model")
        }

        for (pass in remaining) {
            val passStartedAt = System.currentTimeMillis()
            val heard = try {
                detectionText(audio, pass)
            } catch (e: WhisperContext.TranscriptionFailedException) {
                throw RecognitionFailedException("Recognition failed", e)
            }
            for (pair in WordAlignment.align(heard, timingText)) {
                if (lexicon.isProfane(heard[pair.detectionIndex], tiers)) {
                    hits.add(pair.timingIndex)
                }
            }
            Log.i(
                TAG,
                "${pass.model.fileName} at ${pass.speed}x: ${heard.size} words in " +
                    "${System.currentTimeMillis() - passStartedAt} ms"
            )
            onProgress(step())
        }
        return Result(words, hits)
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
        return assembleWords(segments)
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
    private fun assembleWords(segments: List<WhisperContext.Segment>): List<TimedWord> {
        val words = ArrayList<TimedWord>()
        var text = StringBuilder()
        var startMs = 0L
        var endMs = 0L

        fun flush() {
            val finished = text.toString().trim()
            if (finished.isNotEmpty()) {
                // A word cannot end before it starts; alignment occasionally puts two tokens at
                // the same instant, and a zero-width window would beep nothing at all.
                val end = maxOf(endMs, startMs + MIN_WORD_MS)
                words.add(TimedWord(finished, startMs * 1_000, end * 1_000))
            }
            text = StringBuilder()
        }

        for ((index, segment) in segments.withIndex()) {
            if (segment.text.isBlank()) continue
            if (segment.text.startsWith(" ") || text.isEmpty()) {
                flush()
                startMs = segment.bestStartMs
            }
            text.append(segment.text.trim())
            // Where the next token was aligned is a better end than this one's own timestamp,
            // which comes from the decoder rather than from the alignment.
            val nextAligned = segments.drop(index + 1)
                .firstOrNull { it.text.isNotBlank() }
                ?.alignedStartMs
            endMs = nextAligned ?: segment.endMs
        }
        flush()
        return words
    }

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
     * Alignment reads a fixed set of attention heads, so it has to match the architecture.
     * Naming the wrong one does not fail — it produces confident nonsense.
     */
    private fun alignmentFor(model: CensorModels.Model) = when (model) {
        CensorModels.Model.WHISPER_BASE -> WhisperContext.Alignment.BASE
        CensorModels.Model.WHISPER_SMALL -> WhisperContext.Alignment.SMALL
        else -> WhisperContext.Alignment.NONE
    }

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

        /** Floor on a word's length, so alignment placing two tokens together still beeps. */
        const val MIN_WORD_MS = 120L
        val WHITESPACE = Regex("\\s+")

        /**
         * Below this many words, base is taken to have missed the speech rather than to have
         * heard a quiet clip, and the larger model is brought in.
         */
        const val DEAF_THRESHOLD = 8

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

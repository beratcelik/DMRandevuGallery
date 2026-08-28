package com.dmrandevu.gallery.media.censor

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
        val passes = PASSES
        var done = 0f
        fun step(): Float {
            done += 1f
            return done / passes.size
        }

        // The timing pass first: everything else is measured against its word list.
        val timingPass = passes.first { it.carriesTiming }
        val words = try {
            timedWords(audio, timingPass)
        } catch (e: WhisperContext.TranscriptionFailedException) {
            throw RecognitionFailedException("Recognition failed", e)
        }
        onProgress(step())

        if (words.isEmpty()) return Result(emptyList(), emptySet())

        val hits = lexicon.hits(words.map { it.text }, tiers).toMutableSet()
        val timingText = words.map { it.text }

        for (pass in passes.filterNot { it.carriesTiming }) {
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
                words.add(TimedWord(finished, startMs * 1_000, endMs * 1_000))
            }
            text = StringBuilder()
        }

        for (segment in segments) {
            if (segment.text.isBlank()) continue
            if (segment.text.startsWith(" ") || text.isEmpty()) {
                flush()
                startMs = segment.startMs
            }
            text.append(segment.text.trim())
            endMs = segment.endMs
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
            WhisperContext.load(file).also {
                loaded = it
                loadedFrom = file
            }
        } catch (e: Exception) {
            throw RecognitionFailedException("Could not load ${model.fileName}", e)
        }
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
        val WHITESPACE = Regex("\\s+")

        /**
         * Grouped by model so each one is loaded once. Base carries the clock because it is the
         * smaller of the two and the one measured to transcribe swearing most faithfully; small
         * is there because it hears speech base misses entirely.
         */
        val PASSES = listOf(
            Pass(CensorModels.Model.WHISPER_BASE, speed = 1f, carriesTiming = true),
            Pass(CensorModels.Model.WHISPER_BASE, speed = 1f, carriesTiming = false),
            Pass(CensorModels.Model.WHISPER_BASE, speed = 0.75f, carriesTiming = false),
            Pass(CensorModels.Model.WHISPER_SMALL, speed = 1f, carriesTiming = false),
            Pass(CensorModels.Model.WHISPER_SMALL, speed = 0.75f, carriesTiming = false)
        )
    }
}

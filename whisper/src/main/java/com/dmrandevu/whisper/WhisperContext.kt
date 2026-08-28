package com.dmrandevu.whisper

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/**
 * A loaded whisper model.
 *
 * Everything runs on one dedicated thread. whisper's context holds decoding state that cannot be
 * touched from two places at once, and the alternative — a lock around every call — would still
 * leave the abort flag racing with whichever pass happened to be running.
 */
class WhisperContext private constructor(private var contextPtr: Long) : Closeable {

    class TranscriptionFailedException(message: String) : Exception(message)

    /** One segment as whisper reported it. In [noTimestamps] mode there is only ever one. */
    data class Segment(val text: String, val startMs: Long, val endMs: Long)

    /**
     * Transcribes 16 kHz mono samples.
     *
     * See [WhisperLib.fullTranscribe] for what [noTimestamps] actually changes — it is not a
     * printing option.
     */
    suspend fun transcribe(
        samples: FloatArray,
        noTimestamps: Boolean,
        threads: Int = defaultThreads()
    ): List<Segment> = withContext(worker) {
        currentCoroutineContext().ensureActive()
        check(contextPtr != 0L) { "Model already closed" }

        val result = WhisperLib.fullTranscribe(contextPtr, threads, samples, noTimestamps)
        when (result) {
            0 -> Unit
            ABORTED -> {
                // The caller's coroutine is what set the flag, so let its cancellation surface.
                currentCoroutineContext().ensureActive()
                throw TranscriptionFailedException("Recognition was aborted")
            }

            else -> throw TranscriptionFailedException("Recognition failed with code $result")
        }

        (0 until WhisperLib.segmentCount(contextPtr)).map { index ->
            Segment(
                text = WhisperLib.segmentText(contextPtr, index),
                // whisper counts in hundredths of a second.
                startMs = WhisperLib.segmentStart(contextPtr, index) * 10,
                endMs = WhisperLib.segmentEnd(contextPtr, index) * 10
            )
        }
    }

    /**
     * Stops a transcription that is already running.
     *
     * `whisper_full` blocks for tens of seconds, and it holds the worker thread while it does —
     * so cancelling the coroutine alone would leave a core busy long after the operator moved on.
     */
    fun abort() = WhisperLib.setAbort(true)

    override fun close() {
        if (contextPtr == 0L) return
        WhisperLib.freeContext(contextPtr)
        contextPtr = 0
    }

    companion object {
        private const val ABORTED = -2

        /** Serialises every call into the native context; see the class comment. */
        private val worker = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "whisper").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        suspend fun load(model: File): WhisperContext = withContext(worker) {
            require(model.exists()) { "Model file missing: ${model.absolutePath}" }
            val ptr = WhisperLib.initContext(model.absolutePath)
            if (ptr == 0L) throw TranscriptionFailedException("Could not load ${model.name}")
            WhisperContext(ptr)
        }

        fun systemInfo(): String = WhisperLib.systemInfo()

        /**
         * Leaves a couple of cores alone. Recognition already makes the operator wait; taking
         * every core with it would stall the video playing behind the progress counter too.
         */
        private fun defaultThreads() =
            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}

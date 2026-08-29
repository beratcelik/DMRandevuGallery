package com.dmrandevu.whisper

/** The raw JNI surface. Everything that uses it goes through [WhisperContext] instead. */
internal class WhisperLib {
    companion object {
        init {
            System.loadLibrary("whisper")
        }

        external fun initContext(modelPath: String, aheadsPreset: Int): Long
        external fun freeContext(contextPtr: Long)
        external fun setAbort(abort: Boolean)

        /**
         * Returns 0 on success, -1 if the model failed, -2 if [setAbort] stopped it.
         *
         * [noTimestamps] chooses between the two decodes: suppressed timestamps transcribe
         * swearing accurately but as one long segment, enabled timestamps give a segment per
         * token with the swearing sometimes replaced.
         */
        external fun fullTranscribe(
            contextPtr: Long,
            numThreads: Int,
            audioData: FloatArray,
            noTimestamps: Boolean,
            beamSize: Int,
            noContext: Boolean
        ): Int

        external fun segmentCount(contextPtr: Long): Int
        external fun segmentText(contextPtr: Long, index: Int): String

        /** Hundredths of a second, which is whisper's own unit. */
        external fun segmentStart(contextPtr: Long, index: Int): Long

        /** Where alignment puts the segment, or -1 when it was not computed. */
        external fun segmentAlignedStart(contextPtr: Long, index: Int): Long
        external fun segmentEnd(contextPtr: Long, index: Int): Long

        external fun systemInfo(): String
    }
}

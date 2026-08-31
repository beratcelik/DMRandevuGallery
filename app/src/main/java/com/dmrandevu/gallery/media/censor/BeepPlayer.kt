package com.dmrandevu.gallery.media.censor

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The censor tone, played live over the video while a marked stretch goes past.
 *
 * So the operator can hear what they marked instead of reading a red bar and hoping. It is an
 * approximation of the export, not the export: the real one separates the voice out and leaves
 * the music underneath, which takes seconds a frame and cannot happen during playback. Here the
 * video is simply ducked and the tone laid over it, which is enough to tell whether the mark
 * covers the word — the question the operator is actually asking.
 */
class BeepPlayer {

    private var track: AudioTrack? = null

    /** Starts the tone, or does nothing if it is already sounding. */
    fun start() {
        if (track != null) return
        val samples = tone()
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        // Looped over a whole number of cycles, so the join is silent.
        track.setLoopPoints(0, samples.size, -1)
        track.play()
        this.track = track
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }

    /** One second of 1 kHz, eased at both ends so looping it does not tick. */
    private fun tone(): ShortArray {
        val count = SAMPLE_RATE
        val fade = SAMPLE_RATE * FADE_MS / 1000
        return ShortArray(count) { i ->
            val envelope = when {
                i < fade -> 0.5 - 0.5 * cos(PI * i / fade)
                i > count - fade -> 0.5 - 0.5 * cos(PI * (count - i) / fade)
                else -> 1.0
            }
            (LEVEL * envelope * sin(2 * PI * PcmOps.BEEP_HZ * i / SAMPLE_RATE) * 32767).toInt()
                .toShort()
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val FADE_MS = 5

        /** Quieter than the exported beep, which is not competing with a ducked video. */
        const val LEVEL = 0.18
    }
}

package com.dmrandevu.gallery.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.effect.OverlayEffect
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.dmrandevu.gallery.media.watermark.WanderingWatermark
import okhttp3.OkHttpClient

/**
 * Two players, so the conversation on screen keeps playing while the next one pre-buffers and
 * a swipe starts instantly. A player per page would exhaust decoders; a single player would
 * rebuffer on every swipe.
 *
 * Slots are claimed by conversation key rather than page index: deleting a conversation above
 * the viewport shifts every index down, and an index-keyed pool would hand the visible
 * conversation the *other* player mid-playback, restarting the video.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerManager(
    context: Context,
    okHttpClient: OkHttpClient,
    /** Reports a failed video by its (proxy) url, with true when the session itself is dead. */
    private val onError: (url: String, unauthorized: Boolean) -> Unit
) {

    private val players: List<ExoPlayer> = List(POOL_SIZE) { slot ->
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                // The shared OkHttp client carries the admin session cookie, which /admin/media-proxy requires.
                DefaultMediaSourceFactory(OkHttpDataSource.Factory(okHttpClient))
            )
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                // Looks like a no-op, and is load-bearing: ExoPlayer only builds its effects
                // pipeline if setVideoEffects is called at least once before prepare(). Without
                // this, switching the watermark on later goes silently nowhere.
                setVideoEffects(emptyList())
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        val url = slotUrls[slot] ?: return
                        val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                        onError(url, status == 401)
                    }
                })
            }
    }

    private val slotKeys = arrayOfNulls<String>(POOL_SIZE)
    private val slotUrls = arrayOfNulls<String>(POOL_SIZE)
    private val slotUsedAt = LongArray(POOL_SIZE)
    private var clock = 0L

    /** Which slot is on screen. The other one is only pre-buffering and stays effect-free. */
    private var visibleSlot = -1

    private var watermarkHandle: String? = null

    /** What each slot's video effects were last set to, so they are only rebuilt when they change. */
    private val slotWatermark = arrayOfNulls<String>(POOL_SIZE)

    /** The player currently holding [key], claiming the least recently used slot if it has none. */
    fun playerFor(key: String): ExoPlayer = players[slotFor(key)]

    /**
     * The player already holding [key], or null. Unlike [playerFor] this claims nothing, so it is
     * safe to call from a polling loop that only wants to read the position.
     */
    fun playerHolding(key: String): ExoPlayer? =
        slotKeys.indexOfFirst { it == key }.takeIf { it >= 0 }?.let { players[it] }

    /** Holds or resumes the video on screen. */
    fun setPaused(key: String, paused: Boolean) {
        playerHolding(key)?.playWhenReady = !paused
    }

    /** Plays [key] at [speed] times normal, for press-and-hold to skim through a video. */
    fun setSpeed(key: String, speed: Float) {
        playerHolding(key)?.setPlaybackSpeed(speed)
    }

    private fun slotFor(key: String): Int {
        val existing = slotKeys.indexOfFirst { it == key }
        if (existing >= 0) {
            slotUsedAt[existing] = ++clock
            return existing
        }
        var lru = 0
        for (i in 1 until POOL_SIZE) if (slotUsedAt[i] < slotUsedAt[lru]) lru = i
        slotKeys[lru] = key
        slotUrls[lru] = null // repurposed: whatever it held is no longer loaded for this key
        slotUsedAt[lru] = ++clock
        return lru
    }

    /** Loads [url] on this conversation's player and starts it, pausing every other player. */
    fun play(key: String, url: String) {
        val index = slotFor(key)
        val player = players[index]
        if (slotUrls[index] != url) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            slotUrls[index] = url
        }
        players.forEachIndexed { i, other -> if (i != index) other.playWhenReady = false }
        player.playWhenReady = true
        visibleSlot = index
        applyWatermark()
    }

    /** Buffers the next conversation's first video without starting playback. */
    fun preload(key: String, url: String) {
        val index = slotFor(key)
        if (slotUrls[index] == url) return
        val player = players[index]
        player.playWhenReady = false
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        slotUrls[index] = url
    }

    /**
     * Shows [handle]'s watermark over playback, or clears it when null.
     *
     * Deliberately the same [WanderingWatermark] the export uses rather than something drawn over
     * the player in Compose: a preview that is a re-implementation is a preview that can quietly
     * stop matching what actually gets written to the file.
     */
    fun setWatermark(handle: String?) {
        watermarkHandle = handle
        applyWatermark()
    }

    /**
     * Puts the overlay on the slot being watched and takes it off the other one. The pre-buffering
     * player is off screen, and running a GL pass over frames nobody is looking at is a waste of
     * battery.
     *
     * Each slot gets its own [WanderingWatermark]: the overlay caches a texture against the GL
     * context it runs in, and two pipelines cannot share one.
     */
    private fun applyWatermark() {
        players.forEachIndexed { slot, player ->
            val wanted = if (slot == visibleSlot) watermarkHandle else null
            // Rebuilding the effect list restarts the video pipeline, so only touch it on a change.
            if (slotWatermark[slot] == wanted) return@forEachIndexed
            slotWatermark[slot] = wanted
            player.setVideoEffects(
                if (wanted == null) emptyList()
                else listOf(OverlayEffect(listOf(WanderingWatermark(wanted))))
            )
        }
    }

    fun pauseAll() = players.forEach { it.playWhenReady = false }

    fun release() = players.forEach { it.release() }

    private companion object {
        const val POOL_SIZE = 2
    }
}

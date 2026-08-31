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

/** Why a video stopped, which is what decides whether the operator is offered another go at it. */
enum class PlaybackFailure {
    /** The admin session is gone. Nothing plays again until the operator signs back in. */
    SESSION_LOST,

    /** The CDN turned the link itself down. Asking for the same url again cannot help. */
    LINK_DEAD,

    /** A blip: a dropped connection, a server hiccup, a decoder that fell over. Worth a retry. */
    TRANSIENT
}

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
    /** Reports a failed video by its (proxy) url, with what kind of failure it was. */
    private val onError: (url: String, failure: PlaybackFailure) -> Unit
) {

    private val players: List<ExoPlayer> = List(POOL_SIZE) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                // The shared OkHttp client carries the admin session cookie, which /admin/media-proxy requires.
                DefaultMediaSourceFactory(OkHttpDataSource.Factory(okHttpClient))
            )
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(object : Player.Listener {
                    // onEvents rather than onPlayerError, because the error reaches the main
                    // looper well after the load that raised it — long enough for the slot to
                    // have been handed a different video in the meantime, which is what used to
                    // get the blame. Reading the error and the item together off the same player
                    // closes that: prepare() clears playerError, so one still standing here can
                    // only belong to the item still loaded.
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (!events.contains(Player.EVENT_PLAYER_ERROR)) return
                        val error = player.playerError ?: return
                        val url = player.currentMediaItem
                            ?.localConfiguration?.uri?.toString() ?: return
                        onError(url, classify(error))
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

    /**
     * The watermark each slot was *prepared* with, which is not the same as the one it was last
     * asked for: video effects only take hold when they are set before prepare().
     */
    private val slotWatermark = arrayOfNulls<String>(POOL_SIZE)

    /**
     * Whether a slot has ever been handed video effects. The first call commits that player to
     * ExoPlayer's GL pipeline for good, and the pipeline renders into a SurfaceTexture that only
     * drains while a PlayerView is attached — so a committed player must never pre-buffer.
     */
    private val slotUsesGl = BooleanArray(POOL_SIZE)

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
    /**
     * Ducks the video while a marked stretch plays, so the censor tone over it can be heard.
     *
     * Not muted outright: leaving a little through keeps the video from feeling as though it has
     * dropped out, and the operator is judging whether the beep covers the word, not listening to
     * the word.
     */
    fun setDucked(key: String, ducked: Boolean) {
        playerHolding(key)?.volume = if (ducked) DUCKED_VOLUME else 1f
    }

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
        visibleSlot = index
        load(index, url, watermarkHandle)
        players.forEachIndexed { i, other -> if (i != index) other.playWhenReady = false }
        players[index].playWhenReady = true
    }

    /**
     * Buffers the next conversation's first video without starting playback.
     *
     * Never with a watermark, and never on a slot already committed to the GL pipeline: off
     * screen there is no PlayerView, so nothing drains that pipeline's output and the player
     * wedges for good after a couple of dozen frames — which is what used to leave a healthy
     * video showing black once it was swiped to. A skipped pre-buffer only costs a slower first
     * frame, because [play] loads the slot properly when it arrives on screen.
     */
    fun preload(key: String, url: String) {
        val index = slotFor(key)
        if (slotUsesGl[index]) return
        players[index].playWhenReady = false
        load(index, url, watermark = null)
    }

    /**
     * Points slot [index] at [url] with [watermark] over it, re-preparing only when something
     * actually changed.
     *
     * A standing error counts as a change: a slot that failed once kept the url it failed on and
     * so was never prepared again, leaving the operator with black for the rest of the session
     * even when the video behind it was perfectly good.
     */
    private fun load(index: Int, url: String, watermark: String?) {
        val player = players[index]
        val failed = player.playerError != null
        if (slotUrls[index] == url && slotWatermark[index] == watermark && !failed) return

        // Toggling the watermark re-prepares the player, and should not cost the operator their
        // place in the video they were watching.
        val resumeAt = if (slotUrls[index] == url && !failed) player.currentPosition else 0L

        // Only ever called when there is something to say. The first call is what commits this
        // player to the GL pipeline, so a slot that has never carried a watermark is left on the
        // plain decoder path, where having no surface attached costs nothing.
        if (watermark != null || slotWatermark[index] != null) {
            slotUsesGl[index] = true
            player.setVideoEffects(
                if (watermark == null) emptyList()
                else listOf(OverlayEffect(listOf(WanderingWatermark(watermark))))
            )
        }

        player.setMediaItem(MediaItem.fromUri(url), resumeAt)
        player.prepare()
        slotUrls[index] = url
        slotWatermark[index] = watermark
    }

    /**
     * Shows [handle]'s watermark over playback, or clears it when null.
     *
     * Deliberately the same [WanderingWatermark] the export uses rather than something drawn over
     * the player in Compose: a preview that is a re-implementation is a preview that can quietly
     * stop matching what actually gets written to the file.
     *
     * Only the slot on screen is touched. The other one is pre-buffering with no surface of its
     * own, and it picks the watermark up when [play] brings it forward.
     */
    fun setWatermark(handle: String?) {
        if (watermarkHandle == handle) return
        watermarkHandle = handle
        val index = visibleSlot.takeIf { it >= 0 } ?: return
        val url = slotUrls[index] ?: return
        val resume = players[index].playWhenReady
        load(index, url, handle)
        players[index].playWhenReady = resume
    }

    /**
     * A 401 is the session dying. Any other 4xx is the CDN turning the link itself down, which is
     * the genuinely expired case. Everything else — a 5xx, a dropped connection, a decoder giving
     * up — says nothing about the video, so it stays retryable instead of being written off as
     * expired for the rest of the session.
     */
    private fun classify(error: PlaybackException): PlaybackFailure {
        val status = httpStatus(error) ?: return PlaybackFailure.TRANSIENT
        return when {
            status == 401 -> PlaybackFailure.SESSION_LOST
            status in 400..499 -> PlaybackFailure.LINK_DEAD
            else -> PlaybackFailure.TRANSIENT
        }
    }

    /** media3 wraps the http failure a few layers down, so the whole cause chain gets a look. */
    private fun httpStatus(error: PlaybackException): Int? =
        generateSequence(error.cause) { it.cause.takeIf { next -> next !== it } }
            .take(MAX_CAUSE_DEPTH)
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()
            ?.responseCode

    fun pauseAll() = players.forEach { it.playWhenReady = false }

    fun release() = players.forEach { it.release() }

    private companion object {
        const val POOL_SIZE = 2

        /** How much of the video is left audible under the live censor tone. */
        const val DUCKED_VOLUME = 0.12f

        /** Cause chains are short; the bound is only there so a self-referencing one cannot spin. */
        const val MAX_CAUSE_DEPTH = 8
    }
}

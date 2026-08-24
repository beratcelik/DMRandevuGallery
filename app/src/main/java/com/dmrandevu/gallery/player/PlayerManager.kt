package com.dmrandevu.gallery.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

    /** The player currently holding [key], claiming the least recently used slot if it has none. */
    fun playerFor(key: String): ExoPlayer = players[slotFor(key)]

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

    fun pauseAll() = players.forEach { it.playWhenReady = false }

    fun release() = players.forEach { it.release() }

    private companion object {
        const val POOL_SIZE = 2
    }
}

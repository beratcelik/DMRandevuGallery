package com.dmrandevu.gallery.player

import android.content.Context
import android.util.Log
import android.os.SystemClock
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
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
 * Üç oynatıcı: ekrandaki video oynarken bir sonraki ön belleğe alınıyor ve
 * kaydırma anında başlıyor. Sayfa başına bir oynatıcı kod çözücüleri tüketirdi;
 * tek oynatıcı her kaydırmada yeniden tamponlardı.
 *
 * ─── NEDEN İKİ DEĞİL ÜÇ ────────────────────────────────────────────────────
 * Akış düzleşti: her sayfa artık bir VİDEO (eskiden bir konuşma). Bir sonraki
 * sayfa çoğu zaman AYNI müşterinin bir sonraki videosu, yani ön belleğe alma
 * eskisinden çok daha sık isteniyor. İki yuvayla, ekrandaki videonun yuvası
 * sıklıkla tahliye adayı oluyordu.
 *
 * ─── YUVA ANAHTARI SAYFA KİMLİĞİ ("konuşma#sıra") ──────────────────────────
 * Sıra numarası DEĞİL: görüş alanının üstündeki bir konuşma silindiğinde bütün
 * sıralar kayıyor ve sıra anahtarlı bir havuz, ekrandaki sayfaya ÖTEKİ
 * oynatıcıyı verip videoyu baştan başlatırdı. Konuşma anahtarı da yetmiyor:
 * aynı konuşmanın iki videosu aynı yuvaya düşer ve bir sonraki videoyu ön
 * belleğe almak, ekranda oynayan videonun kaynağını değiştirirdi.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    /** Reports a failed video by its (proxy) url, with what kind of failure it was. */
    private val onError: (url: String, failure: PlaybackFailure) -> Unit
) {

    // onEvents rather than onPlayerError, because the error reaches the main looper well after
    // the load that raised it — long enough for the slot to have been handed a different video in
    // the meantime, which is what used to get the blame. Reading the error and the item together
    // off the same player closes that: prepare() clears playerError, so one still standing here
    // can only belong to the item still loaded.
    private val errorListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (!events.contains(Player.EVENT_PLAYER_ERROR)) return
            val error = player.playerError ?: return
            val url = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return
            onError(url, classify(error))
        }
    }

    /** Mutable: a slot whose effect pipeline has been used gets a fresh player, see [load]. */
    private val players: MutableList<ExoPlayer> = MutableList(POOL_SIZE) { newPlayer() }

    /**
     * Bumped whenever a slot's player is swapped for a fresh one. A page's view reads it when it
     * binds, so it picks the new player up instead of holding on to the released one.
     */
    var generation by mutableIntStateOf(0)
        private set

    private fun newPlayer(): ExoPlayer =
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                // The shared OkHttp client carries the admin session cookie, which /admin/media-proxy requires.
                DefaultMediaSourceFactory(OkHttpDataSource.Factory(okHttpClient))
            )
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_ONE
                addListener(errorListener)
            }

    /** Her oynatıcının hangi SAYFAYI tuttuğu; bkz. [SlotTable]. */
    private val slots = SlotTable(POOL_SIZE)

    /** Which slot is on screen. The other one is only pre-buffering and stays effect-free. */
    private var visibleSlot = -1

    private var watermarkHandle: String? = null

    /**
     * The watermark each slot was *prepared* with, which is not the same as the one it was last
     * asked for: video effects only take hold when they are set before prepare().
     */
    private val slotWatermark = arrayOfNulls<String>(POOL_SIZE)

    /** The player currently holding [key], claiming the least recently used slot if it has none. */
    fun playerFor(key: String): ExoPlayer = players[claim(key)]

    /**
     * [SlotTable.claim], plus emptying a player the moment its slot is handed to another page.
     *
     * The page's view binds to its player on composition, before [play] has loaded anything, and
     * a repurposed player was still holding the video it last played, paused on a decoded frame.
     * Given the new surface, it drew that frame there — which opened the view's shutter — and the
     * frame then sat on screen until the new video's first one replaced it: a flash of an old
     * video before every one that had not been pre-buffered.
     */
    private fun claim(key: String): Int {
        val index = slots.claim(key)
        val player = players[index]
        if (slots.urlAt(index) == null) {
            // A player that has run the effect pipeline is swapped rather than emptied: see
            // [load] for why it can never safely take another video.
            if (slots.usesGlAt(index)) {
                replacePlayer(index)
            } else if (player.mediaItemCount > 0) {
                player.stop()
                player.clearMediaItems()
            }
        }
        return index
    }

    /**
     * The player already holding [key], or null. Unlike [playerFor] this claims nothing, so it is
     * safe to call from a polling loop that only wants to read the position.
     */
    fun playerHolding(key: String): ExoPlayer? =
        slots.holding(key).takeIf { it >= 0 }?.let { players[it] }

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

    /** Loads [url] on this conversation's player and starts it, pausing every other player. */
    fun play(key: String, url: String) {
        val index = claim(key)
        visibleSlot = index
        load(index, url, watermarkHandle)
        players.forEachIndexed { i, other -> if (i != index) other.playWhenReady = false }
        players[index].playWhenReady = true
    }

    /**
     * Bir sonraki SAYFANIN videosunu oynatmadan tamponlar.
     *
     * Never with a watermark, and never on a slot already committed to the GL pipeline: off
     * screen there is no PlayerView, so nothing drains that pipeline's output and the player
     * wedges for good after a couple of dozen frames — which is what used to leave a healthy
     * video showing black once it was swiped to. A skipped pre-buffer only costs a slower first
     * frame, because [play] loads the slot properly when it arrives on screen.
     */
    fun preload(key: String, url: String) {
        // Asked before claiming, which is the whole point of [SlotTable.wouldServe]. Claiming
        // first and backing out afterwards left the slot assigned to a conversation whose video
        // was never loaded, having evicted the one on screen — whose view then bound to a player
        // with nothing prepared and showed black, with no error anywhere to say why.
        if (slots.usesGlAt(slots.wouldServe(key))) return
        val index = claim(key)
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
        var player = players[index]
        val failed = player.playerError != null
        if (slots.urlAt(index) == url && slotWatermark[index] == watermark && !failed) return

        // Toggling the watermark re-prepares the player, and should not cost the operator their
        // place in the video they were watching.
        val resumeAt = if (slots.urlAt(index) == url && !failed) player.currentPosition else 0L

        // A PLAYER THAT HAS RUN THE EFFECT PIPELINE IS NEVER LOADED AGAIN; IT IS REPLACED.
        // setVideoEffects on it registers a new input stream with the pipeline, and that waits
        // for the previous stream to be drained through to the screen. A player paused or off
        // screen never drains it, so its playback thread waited forever — found on device with
        // jdb, parked in DefaultVideoFrameProcessor.registerInputStream. That player then showed
        // black for every video it was given, with no error, and each surface change after it
        // froze the UI for the two-second detach timeout. One at a time the pool went black,
        // until a restart built new players. A fresh player has no previous stream to wait for.
        if (slots.usesGlAt(index)) player = replacePlayer(index)

        // Only ever called when there is something to say. The first call is what commits this
        // player to the GL pipeline, so a slot that has never carried a watermark is left on the
        // plain decoder path, where having no surface attached costs nothing.
        if (watermark != null || slotWatermark[index] != null) {
            slots.markUsesGl(index)
            player.setVideoEffects(
                if (watermark == null) emptyList()
                else listOf(OverlayEffect(listOf(WanderingWatermark(watermark))))
            )
        }

        // Re-prepared even when only the watermark changed. Skipping it was tried, on the
        // hope that effects could be swapped on a running player: the black gap went away and so
        // did the watermark, on that toggle and on every one after it. They really do only take
        // hold at prepare().
        player.setMediaItem(MediaItem.fromUri(url), resumeAt)
        player.prepare()
        slots.setUrl(index, url)
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
        val url = slots.urlAt(index) ?: return
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

    /**
     * Swaps slot [index]'s player for a new one and releases the old, which may be wedged: its
     * release then gives up after ExoPlayer's release timeout, a one-off pause, rather than
     * hanging. The listener comes off first, or that timeout would be reported as the video
     * failing.
     */
    private fun replacePlayer(index: Int): ExoPlayer {
        val old = players[index]
        old.removeListener(errorListener)
        val startedAt = SystemClock.elapsedRealtime()
        old.release()
        // A release that takes the full timeout is a player that was wedged, which is worth
        // being able to see in the log when black screens are reported again.
        Log.i(TAG, "slot $index: player replaced, release took ${SystemClock.elapsedRealtime() - startedAt} ms")
        val fresh = newPlayer()
        players[index] = fresh
        slots.clearUsesGl(index)
        slotWatermark[index] = null
        generation++
        return fresh
    }

    fun pauseAll() = players.forEach { it.playWhenReady = false }

    fun release() = players.forEach { it.release() }

    private companion object {
        const val TAG = "PlayerPool"

        /**
         * Havuz boyutu.
         *
         * ÜÇ: ekrandaki sayfa, bir sonraki (ön belleğe alınan) ve geri
         * kaydırıldığında hemen açılacak bir yedek. Dördüncü bir yuva, orta
         * sınıf cihazlarda aynı anda açık kod çözücü sınırını zorluyor.
         */
        const val POOL_SIZE = 3

        /** How much of the video is left audible under the live censor tone. */
        const val DUCKED_VOLUME = 0.12f

        /** Cause chains are short; the bound is only there so a self-referencing one cannot spin. */
        const val MAX_CAUSE_DEPTH = 8
    }
}

package com.dmrandevu.gallery.player

/**
 * Which conversation each player in the pool is holding, and how recently.
 *
 * Its own class because this bookkeeping has now produced two black-screen bugs, and neither was
 * visible in a player: the symptom is a video that never draws and never errors, which looks like
 * a broken clip and is not. Kept free of ExoPlayer so it can be tested on its own.
 */
class SlotTable(val size: Int) {

    private val keys = arrayOfNulls<String>(size)
    private val urls = arrayOfNulls<String>(size)
    private val usedAt = LongArray(size)

    /**
     * Whether a slot has ever been handed video effects. The first call commits that player to
     * ExoPlayer's GL pipeline for good, and the pipeline renders into a SurfaceTexture that only
     * drains while a PlayerView is attached — so a committed player must never pre-buffer.
     */
    private val usesGl = BooleanArray(size)

    private var clock = 0L

    fun keyAt(index: Int): String? = keys[index]
    fun urlAt(index: Int): String? = urls[index]
    fun usesGlAt(index: Int): Boolean = usesGl[index]

    fun markUsesGl(index: Int) {
        usesGl[index] = true
    }

    fun setUrl(index: Int, url: String?) {
        urls[index] = url
    }

    /** The slot already holding [key], or -1. Claims nothing. */
    fun holding(key: String): Int = keys.indexOfFirst { it == key }

    /**
     * Which slot [key] would be given, without giving it.
     *
     * Separate from [claim] on purpose. A caller that has to decide whether to proceed — the
     * pre-buffer does, because it refuses slots committed to the GL pipeline — must be able to
     * ask before anything moves. Asking by claiming and then backing out leaves the slot assigned
     * to a conversation whose video was never loaded, having evicted the one on screen, whose
     * view then binds to a player with nothing prepared and shows black with no error at all.
     */
    fun wouldServe(key: String): Int {
        val existing = holding(key)
        if (existing >= 0) return existing
        var lru = 0
        for (i in 1 until size) if (usedAt[i] < usedAt[lru]) lru = i
        return lru
    }

    /** Gives [key] a slot, evicting the least recently used one if it has none. */
    fun claim(key: String): Int {
        val index = wouldServe(key)
        if (keys[index] != key) {
            keys[index] = key
            // Repurposed: whatever it held is no longer loaded for this key.
            urls[index] = null
        }
        usedAt[index] = ++clock
        return index
    }

    /** Marks a slot as freshly used without moving anything, for reads that must not evict. */
    fun touch(index: Int) {
        usedAt[index] = ++clock
    }
}

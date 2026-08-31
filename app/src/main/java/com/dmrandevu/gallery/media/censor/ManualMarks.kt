package com.dmrandevu.gallery.media.censor

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Stretches the operator marked by hand, for the videos the recognizer cannot manage.
 *
 * There are clips it will not place: sixty-five seconds of shouting over music came back as
 * eleven words, and three of the four swear words in it fell in a stretch no pass heard at all.
 * On those the operator can hear perfectly well where the swearing is, and this is how they say
 * so.
 *
 * Kept per conversation and media index rather than by url. The server re-signs links whenever it
 * is asked, so a url is not the same tomorrow — or after a refresh — and marks kept against one
 * would quietly detach from the video they belong to.
 */
class ManualMarks(private val prefs: SharedPreferences) {

    /** Every marked stretch for one video, earliest first. */
    fun forMedia(conversationKey: String, mediaIndex: Int): List<CensorWindow> =
        parse(prefs.getString(key(conversationKey, mediaIndex), null))

    fun add(conversationKey: String, mediaIndex: Int, window: CensorWindow) {
        if (window.endUs <= window.startUs) return
        val merged = merge(forMedia(conversationKey, mediaIndex) + window)
        write(conversationKey, mediaIndex, merged)
    }

    /** Removes whichever mark covers [atUs], if any. */
    fun removeAt(conversationKey: String, mediaIndex: Int, atUs: Long) {
        val remaining = forMedia(conversationKey, mediaIndex)
            .filterNot { atUs in it.startUs..it.endUs }
        write(conversationKey, mediaIndex, remaining)
    }

    fun clear(conversationKey: String, mediaIndex: Int) {
        prefs.edit { remove(key(conversationKey, mediaIndex)) }
    }

    private fun write(conversationKey: String, mediaIndex: Int, windows: List<CensorWindow>) {
        val encoded = windows.joinToString(";") { "${it.startUs},${it.endUs}" }
        prefs.edit {
            if (encoded.isEmpty()) remove(key(conversationKey, mediaIndex))
            else putString(key(conversationKey, mediaIndex), encoded)
        }
    }

    /** Overlapping or touching marks become one, so two presses over one word are one beep. */
    private fun merge(windows: List<CensorWindow>): List<CensorWindow> {
        val sorted = windows.sortedBy { it.startUs }
        val merged = ArrayList<CensorWindow>()
        for (window in sorted) {
            val last = merged.lastOrNull()
            if (last != null && window.startUs <= last.endUs) {
                merged[merged.size - 1] =
                    CensorWindow(last.startUs, maxOf(last.endUs, window.endUs))
            } else {
                merged.add(window)
            }
        }
        return merged
    }

    private fun parse(encoded: String?): List<CensorWindow> =
        encoded?.split(";")?.mapNotNull { entry ->
            val parts = entry.split(",")
            val start = parts.getOrNull(0)?.toLongOrNull()
            val end = parts.getOrNull(1)?.toLongOrNull()
            if (start != null && end != null && end > start) CensorWindow(start, end) else null
        }.orEmpty()

    private fun key(conversationKey: String, mediaIndex: Int) =
        "marks_${conversationKey}_$mediaIndex"
}

package com.dmrandevu.gallery.data

import android.content.SharedPreferences
import androidx.core.content.edit

/** Remembers everything needed to log back in except the password. */
class SettingsStore(private val prefs: SharedPreferences) {

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)!!
        set(value) = prefs.edit { putString(KEY_BASE_URL, value.trimEnd('/')) }

    var adminUsername: String
        get() = prefs.getString(KEY_ADMIN, "")!!
        set(value) = prefs.edit { putString(KEY_ADMIN, value.trim()) }

    var igUsername: String
        get() = prefs.getString(KEY_IG, DEFAULT_IG_ACCOUNT)!!
        set(value) = prefs.edit { putString(KEY_IG, value.trim().removePrefix("@")) }

    /** Blur faces in every exported video. Off by default: it re-encodes, which takes a while. */
    var blurFaces: Boolean
        get() = prefs.getBoolean(KEY_BLUR_FACES, DEFAULT_BLUR_FACES)
        set(value) = prefs.edit { putBoolean(KEY_BLUR_FACES, value) }

    /** Blur licence plates in every exported video. Off by default, like the face filter. */
    var blurPlates: Boolean
        get() = prefs.getBoolean(KEY_BLUR_PLATES, DEFAULT_BLUR_PLATES)
        set(value) = prefs.edit { putBoolean(KEY_BLUR_PLATES, value) }

    /**
     * Run the plate detector at the smaller input size: about 40% quicker, and it finds roughly
     * two thirds as many plates. On by default while the trade is being lived with.
     */
    var fastPlates: Boolean
        get() = prefs.getBoolean(KEY_FAST_PLATES, DEFAULT_FAST_PLATES)
        set(value) = prefs.edit { putBoolean(KEY_FAST_PLATES, value) }

    /**
     * Beep over Turkish swearing in every exported video. Off by default: it needs a third of a
     * gigabyte of models downloaded before it can do anything.
     */
    var censorAudio: Boolean
        get() = prefs.getBoolean(KEY_CENSOR_AUDIO, DEFAULT_CENSOR_AUDIO)
        set(value) = prefs.edit { putBoolean(KEY_CENSOR_AUDIO, value) }

    /**
     * Beep milder insults too, not only outright profanity. Off by default — the spike had
     * "manyak" firing on a clip where nobody swore.
     */
    var censorInsults: Boolean
        get() = prefs.getBoolean(KEY_CENSOR_INSULTS, DEFAULT_CENSOR_INSULTS)
        set(value) = prefs.edit { putBoolean(KEY_CENSOR_INSULTS, value) }

    /** Drift the account handle across every exported video, so a repost still shows whose it is. */
    var watermark: Boolean
        get() = prefs.getBoolean(KEY_WATERMARK, DEFAULT_WATERMARK)
        set(value) = prefs.edit { putBoolean(KEY_WATERMARK, value) }

    companion object {
        const val DEFAULT_BASE_URL = "https://dmrandevu.com"
        const val DEFAULT_IG_ACCOUNT = "trafik_cezasi"
        const val DEFAULT_BLUR_FACES = false
        const val DEFAULT_BLUR_PLATES = false
        const val DEFAULT_FAST_PLATES = true
        const val DEFAULT_WATERMARK = false
        const val DEFAULT_CENSOR_AUDIO = false
        const val DEFAULT_CENSOR_INSULTS = false
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ADMIN = "admin_username"
        private const val KEY_IG = "ig_username"
        private const val KEY_BLUR_FACES = "blur_faces"
        private const val KEY_BLUR_PLATES = "blur_plates"
        private const val KEY_FAST_PLATES = "fast_plates"
        private const val KEY_WATERMARK = "watermark"
        private const val KEY_CENSOR_AUDIO = "censor_audio"
        private const val KEY_CENSOR_INSULTS = "censor_insults"
    }
}

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

    companion object {
        const val DEFAULT_BASE_URL = "https://dmrandevu.com"
        const val DEFAULT_IG_ACCOUNT = "trafik_cezasi"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_ADMIN = "admin_username"
        private const val KEY_IG = "ig_username"
    }
}

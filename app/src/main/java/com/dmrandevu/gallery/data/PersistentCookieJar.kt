package com.dmrandevu.gallery.data

import android.content.SharedPreferences
import androidx.core.content.edit
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the admin session cookie across app restarts, so a returning user skips login
 * for the full 7-day server-side session lifetime.
 *
 * Session cookies are HttpOnly with an explicit Expires, so they survive serialization —
 * we keep the last cookie seen per (name, domain) and drop expired ones on load.
 */
class PersistentCookieJar(private val prefs: SharedPreferences) : CookieJar {

    private val cache = linkedMapOf<String, Cookie>()

    init {
        load()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        for (cookie in cookies) cache[keyOf(cookie)] = cookie
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        val expired = cache.filterValues { it.expiresAt < now }.keys
        if (expired.isNotEmpty()) {
            expired.forEach { cache.remove(it) }
            persist()
        }
        return cache.values.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() {
        cache.clear()
        prefs.edit { remove(KEY) }
    }

    private fun keyOf(cookie: Cookie) = "${cookie.name}|${cookie.domain}|${cookie.path}"

    private fun persist() {
        val arr = JSONArray()
        for (cookie in cache.values) {
            arr.put(
                JSONObject().apply {
                    put("name", cookie.name)
                    put("value", cookie.value)
                    put("expiresAt", cookie.expiresAt)
                    put("domain", cookie.domain)
                    put("path", cookie.path)
                    put("secure", cookie.secure)
                    put("httpOnly", cookie.httpOnly)
                    put("hostOnly", cookie.hostOnly)
                }
            )
        }
        prefs.edit { putString(KEY, arr.toString()) }
    }

    private fun load() {
        val raw = prefs.getString(KEY, null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.getLong("expiresAt") < now) continue
                val builder = Cookie.Builder()
                    .name(o.getString("name"))
                    .value(o.getString("value"))
                    .expiresAt(o.getLong("expiresAt"))
                    .path(o.getString("path"))
                if (o.getBoolean("hostOnly")) builder.hostOnlyDomain(o.getString("domain"))
                else builder.domain(o.getString("domain"))
                if (o.getBoolean("secure")) builder.secure()
                if (o.getBoolean("httpOnly")) builder.httpOnly()
                val cookie = builder.build()
                cache[keyOf(cookie)] = cookie
            }
        }
    }

    private companion object {
        const val KEY = "cookies"
    }
}

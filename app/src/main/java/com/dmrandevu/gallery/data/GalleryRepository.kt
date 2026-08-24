package com.dmrandevu.gallery.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Every call the app makes against the DMRandevu admin API. All endpoints are gated by the
 * express-session cookie, which [PersistentCookieJar] carries automatically.
 */
class GalleryRepository(
    private val client: OkHttpClient,
    private val settings: SettingsStore,
    private val cookieJar: PersistentCookieJar
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val base: String get() = settings.baseUrl.trimEnd('/')

    /** URL that streams a CDN video through the server-side proxy (Range-capable). */
    fun proxyUrl(rawUrl: String): String =
        "$base/admin/media-proxy?url=" + URLEncoder.encode(rawUrl, "UTF-8")

    /**
     * The login route answers with a 302 either way, so success is decided by the Location
     * header — redirects must stay off or we would follow it and lose that signal.
     */
    suspend fun login(baseUrl: String, username: String, password: String): Boolean =
        withContext(Dispatchers.IO) {
            settings.baseUrl = baseUrl
            val body = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .build()
            val request = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/admin/auth/login")
                .post(body)
                .build()
            val noRedirect = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            noRedirect.newCall(request).execute().use { response ->
                // The Set-Cookie on this very 302 is what the cookie jar stores.
                val location = response.header("Location").orEmpty()
                response.isRedirect && !location.contains("/admin/login")
            }
        }

    /**
     * Account identifier → Instagram id.
     *
     * A numeric entry is already an id and is used as-is — the same passthrough the server does.
     * That also means the app can talk to a server that does not carry the resolve endpoint yet,
     * as long as the id is typed instead of the @handle.
     */
    suspend fun resolveAccount(igUsername: String): ResolveResponse {
        val account = igUsername.trim().removePrefix("@")
        if (account.isNotEmpty() && account.all { it.isDigit() }) {
            return ResolveResponse(igId = account, username = account)
        }
        return withContext(Dispatchers.IO) {
            val url = "$base/admin/media-gallery-resolve".toHttpUrl().newBuilder()
                .addQueryParameter("username", account)
                .build()
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.code == 404) {
                    // Either the account is unknown, or this server predates the resolve
                    // endpoint. Fall back to the handles we already know the id for, so the
                    // app keeps working against a server that has not been updated yet.
                    KNOWN_ACCOUNTS[account.lowercase()]
                        ?.let { return@use ResolveResponse(igId = it, username = account) }
                    throw AccountNotFoundException()
                }
                json.decodeFromString<ResolveResponse>(response.requireBody())
            }
        }
    }

    /** Cheapest authenticated call that proves the stored session cookie is still good. */
    suspend fun isSessionValid(igId: String): Boolean = try {
        loadPage(igId, offset = 0, limit = 1)
        true
    } catch (e: UnauthorizedException) {
        false
    }

    suspend fun loadPage(igId: String, offset: Int, limit: Int): GalleryPage = withContext(Dispatchers.IO) {
        val url = "$base/admin/media-gallery-page".toHttpUrl().newBuilder()
            .addQueryParameter("igId", igId)
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            json.decodeFromString<GalleryPage>(response.requireBody())
        }
    }

    /** Deletes the whole conversation. A 404 means it is already gone — same end state. */
    suspend fun deleteConversation(salonId: String, clientId: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$base/admin/conversation/$salonId/$clientId")
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw UnauthorizedException()
            if (!response.isSuccessful && response.code != 404) {
                throw IllegalStateException("Delete failed: HTTP ${response.code}")
            }
        }
    }

    /** OpenAI-backed caption; slow enough (10-30s) to need its own read timeout. */
    suspend fun generateCaption(
        salonId: String,
        clientId: String,
        rawMediaUrl: String,
        manualExplanation: String? = null
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("salonId", salonId)
            put("clientId", clientId)
            put("mediaUrl", rawMediaUrl)
            if (!manualExplanation.isNullOrBlank()) put("manualExplanation", manualExplanation)
        }
        val request = Request.Builder()
            .url("$base/admin/generate-caption")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val slowClient = client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
        slowClient.newCall(request).execute().use { response ->
            json.decodeFromString<CaptionResponse>(response.requireBody()).caption
        }
    }

    fun clearSession() = cookieJar.clear()

    /** Body text of a successful response; maps the server's 401 JSON onto a typed failure. */
    private fun Response.requireBody(): String {
        if (code == 401) throw UnauthorizedException()
        val text = body?.string().orEmpty()
        if (!isSuccessful) throw IllegalStateException("HTTP $code")
        return text
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Handle → Instagram id, for servers without /admin/media-gallery-resolve. */
        val KNOWN_ACCOUNTS = mapOf(
            "trafik_cezasi" to "17841468848724091",
            "trafykamerasi" to "17841472755272054"
        )
    }
}

class AccountNotFoundException : Exception("Account not found")

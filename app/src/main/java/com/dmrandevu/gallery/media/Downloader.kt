package com.dmrandevu.gallery.media

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.dmrandevu.gallery.data.GalleryRepository
import com.dmrandevu.gallery.data.UnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Saves proxied videos either to the phone's gallery (MediaStore) or to a private cache file
 * for sharing. minSdk 29 means app-created MediaStore entries need no storage permission.
 */
class Downloader(
    private val context: Context,
    private val client: OkHttpClient,
    private val repository: GalleryRepository
) {

    /** Streams the video into Movies/DMRandevu so it shows up in the phone's gallery apps. */
    suspend fun saveToGallery(rawUrl: String, clientName: String): Boolean = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName(clientName))
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/DMRandevu")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return@withContext false

        try {
            fetch(rawUrl) { source ->
                resolver.openOutputStream(uri)?.use { out -> source.copyTo(out) }
                    ?: throw IllegalStateException("Cannot open output stream")
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            // Leave no half-written entry visible in the gallery.
            runCatching { resolver.delete(uri, null, null) }
            if (e is UnauthorizedException) throw e
            false
        }
    }

    /** Downloads to cacheDir/share so the file can be handed to Instagram via FileProvider. */
    suspend fun downloadForShare(rawUrl: String, clientName: String): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        // One file per share keeps a previous, still-open share from being overwritten.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(clientName))
        fetch(rawUrl) { source -> file.outputStream().use { out -> source.copyTo(out) } }
        file
    }

    private inline fun fetch(rawUrl: String, write: (java.io.InputStream) -> Unit) {
        val request = Request.Builder().url(repository.proxyUrl(rawUrl)).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw UnauthorizedException()
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("Empty body")
            write(body.byteStream())
        }
    }

    private fun fileName(clientName: String): String {
        val safe = clientName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24).ifBlank { "video" }
        return "dmrandevu_${safe}_${System.currentTimeMillis()}.mp4"
    }

    companion object {
        const val MIME_TYPE = "video/mp4"
    }
}

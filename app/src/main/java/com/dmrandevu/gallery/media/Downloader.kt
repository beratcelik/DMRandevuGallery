package com.dmrandevu.gallery.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import com.dmrandevu.gallery.data.GalleryRepository
import com.dmrandevu.gallery.data.UnauthorizedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream

/**
 * Saves proxied videos either to the phone's gallery (MediaStore) or to a private cache file
 * for sharing. minSdk 29 means app-created MediaStore entries need no storage permission.
 *
 * When [ExportOptions] asks for anything, the video is pulled into the cache and run through
 * [VideoExporter] before it goes anywhere — nothing leaves the app unprotected once a filter is
 * on. With no options set the bytes are streamed straight through, as they always were.
 */
@UnstableApi
class Downloader(
    private val context: Context,
    private val client: OkHttpClient,
    private val repository: GalleryRepository,
    private val exporter: VideoExporter
) {

    /** Streams the video into Movies/DMRandevu so it shows up in the phone's gallery apps. */
    suspend fun saveToGallery(
        rawUrl: String,
        clientName: String,
        options: ExportOptions,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Processing first: the MediaStore row is only created once there are final bytes to
            // write, so a failed blur leaves nothing behind in the gallery to clean up.
            val processed = if (!options.changesNothing) {
                try {
                    prepareProcessed(rawUrl, options, onProgress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: UnauthorizedException) {
                    throw e
                } catch (e: VideoExporter.ExportFailedException) {
                    // The caller has to be able to tell "could not process" from "could not
                    // download" — one of them means an unprotected video nearly got out.
                    throw e
                } catch (e: Exception) {
                    // A plain download failure keeps this method's original contract.
                    return@withContext false
                }
            } else {
                null
            }

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
                if (processed != null) {
                    processed.inputStream().use { source -> copyInto(uri, source) }
                } else {
                    fetch(rawUrl) { source -> copyInto(uri, source) }
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
        } finally {
            clearWorkDir()
        }
    }

    /**
     * Zaten dışa aktarılmış bir dosyayı galeriye yazar.
     *
     * [saveToGallery]'den farkı ikinci bir DIŞA AKTARMA yapmaması: elindeki baytları
     * kopyalıyor. Reels yolu videoyu hem besteciye veriyor hem galeriye bırakıyor, çünkü
     * Instagram kimliği bestecinin içinde doğruluyor ve reddettiğini bize söylemiyor —
     * galerideki kopya, o sessiz reddin tek telafisi. İki ayrı dışa aktarma çağırmak,
     * bulanıklaştırmayı ve bip geçişini ikinci kez koşturmak olurdu.
     */
    suspend fun saveFileToGallery(file: File, clientName: String): Boolean =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName(clientName))
                put(MediaStore.Video.Media.MIME_TYPE, MIME_TYPE)
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/DMRandevu"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection =
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return@withContext false
            try {
                file.inputStream().use { source -> copyInto(uri, source) }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                // Yarım yazılmış bir girdi galeride görünmesin.
                runCatching { resolver.delete(uri, null, null) }
                false
            }
        }

    /**
     * Downloads to cacheDir/share so the file can be handed to Instagram via FileProvider.
     *
     * [playerMs] is how long the player on screen found the video to be, the first length in
     * the [ShareTrace] chain that ends at the file Instagram gets.
     */
    suspend fun downloadForShare(
        rawUrl: String,
        clientName: String,
        options: ExportOptions,
        playerMs: Long? = null,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        ShareTrace.log(
            context,
            "share start: client=$clientName player=${ShareTrace.format(playerMs)} " +
                "options=$options url=${rawUrl.substringBefore('?')}" +
                // The messaging CDN names the video only in the query; the rest of it is a
                // signature that expires anyway.
                (Uri.parse(rawUrl).getQueryParameter("asset_id")?.let { " asset_id=$it" } ?: "")
        )
        try {
            val processed = if (options.changesNothing) {
                null
            } else {
                prepareProcessed(rawUrl, options, onProgress, expectedMs = playerMs)
            }

            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            // One file per share keeps a previous, still-open share from being overwritten.
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, fileName(clientName))
            if (processed != null) {
                // The processed file has to end up under share/ — it is the only path
                // FileProvider exposes (res/xml/file_paths.xml). Same filesystem, so this is a
                // rename rather than a second copy.
                if (!processed.renameTo(file)) processed.copyTo(file, overwrite = true)
            } else {
                fetch(rawUrl) { source -> file.outputStream().use { out -> source.copyTo(out) } }
            }
            ShareTrace.probe(context, "share file ${file.name}", file, playerMs)
            file
        } catch (e: Exception) {
            ShareTrace.log(context, "share failed: $e")
            throw e
        } finally {
            clearWorkDir()
        }
    }

    /**
     * Downloads the video and applies [options], returning whichever file should be handed on. A
     * video the options turn out not to change — asking for face blur when there are no faces —
     * comes back untouched rather than needlessly re-encoded.
     *
     * Throws [VideoExporter.ExportFailedException] if the processing cannot be applied — never a
     * quietly unprotected original.
     */
    private suspend fun prepareProcessed(
        rawUrl: String,
        options: ExportOptions,
        onProgress: (Int) -> Unit,
        expectedMs: Long? = null
    ): File {
        val dir = workDir()
        val input = File(dir, "input.mp4")
        onProgress(0)
        fetch(rawUrl) { source -> input.outputStream().use { out -> source.copyTo(out) } }
        onProgress(DOWNLOAD_SHARE)
        val inputMs = ShareTrace.probe(context, "downloaded", input, expectedMs)

        val output = File(dir, "processed.mp4")
        val result = exporter.export(input, output, options) { percent ->
            onProgress(DOWNLOAD_SHARE + percent * (100 - DOWNLOAD_SHARE) / 100)
        }
        return when (result) {
            is VideoExporter.Result.Exported -> {
                ShareTrace.probe(context, "exported", result.file, inputMs)
                result.file
            }
            VideoExporter.Result.NothingToDo -> {
                ShareTrace.log(context, "exported: nothing to do, original passed on")
                input
            }
        }
    }

    /**
     * Deliberately not cacheDir/share: [downloadForShare] wipes that directory on every call,
     * and FileProvider exposes it, so intermediates have no business being there.
     */
    private fun workDir() = File(context.cacheDir, WORK_DIR).apply {
        mkdirs()
        // Whatever a previous export left behind, including one killed mid-flight.
        listFiles()?.forEach { it.delete() }
    }

    private fun clearWorkDir() {
        File(context.cacheDir, WORK_DIR).listFiles()?.forEach { it.delete() }
    }

    private fun copyInto(uri: Uri, source: InputStream) {
        context.contentResolver.openOutputStream(uri)?.use { out -> source.copyTo(out) }
            ?: throw IllegalStateException("Cannot open output stream")
    }

    private inline fun fetch(rawUrl: String, write: (InputStream) -> Unit) {
        val request = Request.Builder().url(repository.proxyUrl(rawUrl)).build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw UnauthorizedException()
            if (!response.isSuccessful) {
                ShareTrace.log(context, "fetch: HTTP ${response.code}")
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Empty body")
            // What the server promised against what arrived: a body cut short, or a partial
            // (206) answer saved as though it were the whole video, would show up here first.
            val source = CountingInputStream(body.byteStream())
            write(source)
            val advertised = response.header("Content-Length")?.toLongOrNull()
            ShareTrace.log(
                context,
                "fetch: HTTP ${response.code} length=${advertised ?: "?"} read=${source.count}" +
                    (response.header("Content-Range")?.let { " range=$it" } ?: "") +
                    (if (advertised != null && advertised != source.count) " SUSPECT: short read" else "")
            )
        }
    }

    private class CountingInputStream(private val inner: InputStream) : InputStream() {
        var count = 0L
            private set

        override fun read(): Int = inner.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, len).also { if (it > 0) count += it }

        override fun available(): Int = inner.available()

        override fun close() = inner.close()
    }

    private fun fileName(clientName: String): String {
        val safe = clientName.replace(Regex("[^A-Za-z0-9_-]"), "_").take(24).ifBlank { "video" }
        return "dmrandevu_${safe}_${System.currentTimeMillis()}.mp4"
    }

    companion object {
        const val MIME_TYPE = "video/mp4"

        /** Share of the progress bar spent downloading before the processing passes start. */
        private const val DOWNLOAD_SHARE = 10

        private const val WORK_DIR = "export"

    }
}

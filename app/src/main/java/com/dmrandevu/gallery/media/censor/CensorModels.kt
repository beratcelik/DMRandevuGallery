package com.dmrandevu.gallery.media.censor

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * The models the censor pass needs, fetched the first time the filter is switched on.
 *
 * They are not in the APK. Together they are about 320 MB, which would turn a 30 MB install into
 * something nobody wants to sit through on a phone, for a filter that may never be used. They are
 * downloaded once, verified, and kept.
 *
 * A model that is missing or damaged fails the export. Falling back to a shorter word list, or to
 * no censoring, would hand over a video with the swearing still in it — the one outcome this
 * filter exists to prevent.
 */
class CensorModels(private val context: Context, private val client: OkHttpClient) {

    class ModelUnavailableException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /**
     * [sha256] is checked after every download. A truncated file — the phone lost signal
     * two thirds of the way through — otherwise loads as a valid-looking model that recognises
     * nothing, and silently stops finding swearing.
     */
    enum class Model(
        val fileName: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String
    ) {
        /**
         * Catches swearing that the larger model writes around: measured on real clips it heard
         * "Amına koydum" where small answered "Ama ne kodumu".
         */
        WHISPER_BASE(
            fileName = "ggml-base-q5_1.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin",
            sizeBytes = 59_707_625,
            sha256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
        ),

        /**
         * Hears speech that base misses entirely — base returned nothing but "[MÜZİK ÇALIYOR]" on
         * a clip this one transcribed in full. Neither is reliable alone, so both run.
         */
        WHISPER_SMALL(
            fileName = "ggml-small-q5_1.bin",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin",
            sizeBytes = 190_085_487,
            sha256 = "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"
        ),

        /** Separates the voice from the background so the beep can leave the music playing. */
        VOCAL_SEPARATOR(
            fileName = "UVR-MDX-NET-Voc_FT.onnx",
            url = "https://github.com/TRvlvr/model_repo/releases/download/all_public_uvr_models/" +
                "UVR-MDX-NET-Voc_FT.onnx",
            sizeBytes = 66_762_490,
            sha256 = "534b2070fcc7df514b13ef660dc8cbb328679c2374d04354a5c42bb14ecce111"
        )
    }

    fun fileFor(model: Model) = File(modelDir(), model.fileName)

    fun isInstalled(model: Model): Boolean {
        val file = fileFor(model)
        return file.exists() && file.length() == model.sizeBytes
    }

    val allInstalled: Boolean get() = Model.entries.all(::isInstalled)

    /** Total bytes still to fetch, for telling the operator what they are waiting for. */
    val bytesOutstanding: Long
        get() = Model.entries.filterNot(::isInstalled).sumOf { it.sizeBytes }

    /**
     * Downloads whatever is missing. [onProgress] reports 0..1 across all of them together.
     */
    suspend fun ensureAvailable(onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val missing = Model.entries.filterNot(::isInstalled)
        if (missing.isEmpty()) {
            onProgress(1f)
            return@withContext
        }
        val total = missing.sumOf { it.sizeBytes }.toFloat()
        var done = 0L
        for (model in missing) {
            download(model) { bytes -> onProgress(((done + bytes) / total).coerceIn(0f, 1f)) }
            done += model.sizeBytes
        }
        onProgress(1f)
    }

    private suspend fun download(model: Model, onBytes: (Long) -> Unit) {
        val target = fileFor(model)
        // Downloaded beside the real name and moved into place at the end, so a download that
        // dies halfway can never be picked up as a finished model.
        val partial = File(target.parentFile, "${model.fileName}.part")
        partial.delete()

        try {
            val request = Request.Builder().url(model.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw ModelUnavailableException(
                        "Could not fetch ${model.fileName}: HTTP ${response.code}"
                    )
                }
                val body = response.body
                    ?: throw ModelUnavailableException("Empty response for ${model.fileName}")

                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                body.byteStream().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            written += read
                            onBytes(written)
                        }
                    }
                }

                if (written != model.sizeBytes) {
                    throw ModelUnavailableException(
                        "${model.fileName} came back $written bytes, expected ${model.sizeBytes}"
                    )
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(model.sha256, ignoreCase = true)) {
                    throw ModelUnavailableException(
                        "${model.fileName} does not match its checksum"
                    )
                }
            }
            if (!partial.renameTo(target)) {
                throw ModelUnavailableException("Could not put ${model.fileName} in place")
            }
        } catch (e: ModelUnavailableException) {
            throw e
        } catch (e: Exception) {
            throw ModelUnavailableException("Could not fetch ${model.fileName}", e)
        } finally {
            partial.delete()
        }
    }

    /**
     * Kept in files rather than the cache: the system empties the cache when storage runs low,
     * and re-downloading 320 MB because the phone wanted a few megabytes back is not a trade
     * worth making.
     */
    private fun modelDir(): File =
        File(context.filesDir, "censor-models").apply { mkdirs() }
}

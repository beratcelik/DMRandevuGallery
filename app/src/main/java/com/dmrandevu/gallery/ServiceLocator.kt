package com.dmrandevu.gallery

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.dmrandevu.gallery.data.GalleryRepository
import com.dmrandevu.gallery.data.PersistentCookieJar
import com.dmrandevu.gallery.data.SettingsStore
import com.dmrandevu.gallery.media.Downloader
import com.dmrandevu.gallery.media.VideoExporter
import com.dmrandevu.gallery.media.censor.AudioCensor
import com.dmrandevu.gallery.media.censor.CensorModels
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled singletons. One user, one screen pair, a handful of dependencies — a DI framework
 * would cost more than it saves here.
 */
@UnstableApi
object ServiceLocator {

    lateinit var appContext: Context
        private set

    lateinit var settings: SettingsStore
        private set

    lateinit var cookieJar: PersistentCookieJar
        private set

    lateinit var client: OkHttpClient
        private set

    lateinit var repository: GalleryRepository
        private set

    lateinit var censorModels: CensorModels
        private set

    lateinit var exporter: VideoExporter
        private set

    lateinit var downloader: Downloader
        private set

    fun init(context: Context) {
        if (::repository.isInitialized) return
        appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences("dmrandevu_gallery", Context.MODE_PRIVATE)
        settings = SettingsStore(prefs)
        cookieJar = PersistentCookieJar(prefs)
        client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // The login route rejects bot-shaped User-Agents (curl/python/java/...), so we
                // announce ourselves explicitly instead of relying on OkHttp's default.
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                )
            }
            .build()
        repository = GalleryRepository(client, settings, cookieJar)
        censorModels = CensorModels(appContext, client)
        exporter = VideoExporter(appContext, AudioCensor(appContext, censorModels))
        downloader = Downloader(appContext, client, repository, exporter)
    }

    const val USER_AGENT = "DMRandevuGaleri/1.0 (Android)"
}

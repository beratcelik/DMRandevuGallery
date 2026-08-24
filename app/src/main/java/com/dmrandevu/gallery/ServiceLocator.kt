package com.dmrandevu.gallery

import android.content.Context
import com.dmrandevu.gallery.data.GalleryRepository
import com.dmrandevu.gallery.data.PersistentCookieJar
import com.dmrandevu.gallery.data.SettingsStore
import com.dmrandevu.gallery.media.Downloader
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled singletons. One user, one screen pair, four dependencies — a DI framework
 * would cost more than it saves here.
 */
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
        downloader = Downloader(appContext, client, repository)
    }

    const val USER_AGENT = "DMRandevuGaleri/1.0 (Android)"
}

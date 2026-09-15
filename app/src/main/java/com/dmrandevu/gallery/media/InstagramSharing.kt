package com.dmrandevu.gallery.media

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a video straight to one of Instagram's composers.
 *
 * Instagram exposes dedicated entry points per surface, so we can skip the share chooser and
 * land the operator directly in Stories or Reels. Verified against Instagram 444.x, which
 * registers `ADD_TO_STORY` (CustomStoryShareHandlerActivity) and `ADD_TO_REEL`
 * (ClipsShareHandlerActivity), both accepting a video.
 *
 * Note on captions: no Instagram share intent accepts one. Text extras are ignored on every
 * surface, so the caption can only travel via the clipboard for the operator to paste.
 */
object InstagramSharing {

    const val PACKAGE = "com.instagram.android"

    /**
     * Meta uygulama kimliğimiz. Instagram gelen paylaşımı BUNUNLA tanıyor.
     *
     * Bir süre burada Meta'nın örnek belgelerinden gelen herkese açık bir kimlik
     * (685976850839286) duruyordu; com.dmrandevu.gallery adına kayıtlı olmadığı için Instagram
     * paylaşımı kendi penceresinde reddediyordu ("bu uygulama ... desteklemiyor" /
     * "doğrulanmadı"). Hikaye düğmesinde görülen uyarının sebebi oydu.
     *
     * KİMLİĞİN ÇALIŞMASI, uygulamanın Meta panelinde CANLI modda olmasına bağlı: geliştirme
     * modunda yalnızca uygulamada rolü olan hesaplar geçiyor, yani kendi hesabınızla yapılan
     * sınama hiçbir şey kanıtlamıyor. Rolü olmayan bir Instagram hesabıyla denenmeli.
     */
    const val META_APP_ID = "1059486250258693"

    /**
     * Reels bestecisini doğrudan açmayı dene.
     *
     * AÇIK. Kapalıyken video önce galeriye kaydediliyor ve Reels'te elle seçiliyordu; artık
     * besteci doğrudan açılıyor.
     *
     * BİLİNEN RİSK: Instagram kimliği bestecinin İÇİNDE doğruluyor ve reddettiğini bize
     * SÖYLEMİYOR. [openReelComposer] yalnızca "Instagram bu niyeti hiç karşılamadı" durumunda
     * false dönüyor; kimlik reddedilirse niyet karşılanmış sayılıyor, operatör Instagram'da bir
     * hata penceresiyle kalıyor ve video galeride DEĞİL (besteci yolu oraya hiç yazmıyor).
     * O hâlde bu bayrağı kapatmak bilinen yola geri döndürüyor.
     */
    const val REELS_COMPOSER_ENABLED = true

    private const val ACTION_STORY = "com.instagram.share.ADD_TO_STORY"
    private const val ACTION_REEL = "com.instagram.share.ADD_TO_REEL"

    /**
     * Kimliğin belgelenmiş anahtarı. Eski `source_application` hâlâ gönderiliyor çünkü eski
     * Instagram yapıları yalnızca onu okuyor; tanımadığı bir ekstra zaten yok sayılıyor.
     */
    private const val EXTRA_APP_ID = "com.instagram.platform.extra.APPLICATION_ID"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Opens the Stories composer with [video] already loaded. */
    fun openStoryComposer(context: Context, video: File): Boolean =
        launch(context, video, ACTION_STORY, "video/mp4")

    /**
     * Reels bestecisini videoyla açar. Yalnızca [REELS_COMPOSER_ENABLED] açıkken çağrılmalı.
     *
     * `false` dönmesi "Instagram bu niyeti karşılamadı" demek; kimliği reddetmesi bundan
     * AYRI bir şey ve buradan görünmüyor — o yüzden çağıranın her hâlükârda galeriye kaydeden
     * yolu elinde tutması gerekiyor.
     */
    fun openReelComposer(context: Context, video: File): Boolean =
        launch(context, video, ACTION_REEL, "video/*")

    /**
     * Opens Instagram so a Reel can be created from a video already sitting in the phone's
     * gallery, with the caption waiting on the clipboard.
     *
     * Reels bestecisi bu yoldan AÇILMIYOR ve bunun sebebi bir Meta onayı değil: `ADD_TO_REEL`
     * kendi Meta uygulama kimliğimizi istiyor, elimizdeki kimlik bizim değil ve Instagram
     * tanımadığı kimlikle gelen paylaşımı kendi penceresinde reddediyor (Instagram 444.x
     * üzerinde bu uygulamadan, doğrudan bileşen açarak ve kabuktan denendi). Instagram'ın
     * paylaşım hedefleri de kaçış yolu değil — Samsung'un paylaşım sayfası hepsini tek bir
     * girdiye indiriyor ve Direct'e düşürüyor. Bu yüzden video önce galeriye kaydediliyor ve
     * Reels'te oradan seçiliyor. Kendi kimliğimiz alındığında [REELS_COMPOSER_ENABLED]
     * açılarak besteci yolu devreye giriyor; ayrıntısı [META_APP_ID] başlığında.
     */
    fun openInstagram(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return try {
            context.startActivity(launch)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    fun copyCaption(context: Context, caption: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("caption", caption))
    }

    private fun launch(context: Context, video: File, action: String, mimeType: String): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", video)
        // These composers read the video from `data`, so the read grant has to be given to
        // Instagram explicitly as well as carried on the intent.
        context.grantUriPermission(PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val intent = Intent(action).apply {
            setDataAndType(uri, mimeType)
            putExtra("source_application", META_APP_ID)
            putExtra(EXTRA_APP_ID, META_APP_ID)
            putExtra(Intent.EXTRA_STREAM, uri)
            setPackage(PACKAGE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            fallbackToChooser(context, uri, mimeType)
        }
    }

    /** Older Instagram builds may lack a surface; the generic share sheet still reaches it. */
    private fun fallbackToChooser(context: Context, uri: Uri, mimeType: String): Boolean = try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage(PACKAGE)
        }
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

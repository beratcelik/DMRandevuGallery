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

    /** Public Meta app identifier; Instagram requires it to attribute the incoming share. */
    private const val SOURCE_APPLICATION = "685976850839286"

    private const val ACTION_STORY = "com.instagram.share.ADD_TO_STORY"
    private const val ACTION_REEL = "com.instagram.share.ADD_TO_REEL"

    fun isInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    /** Opens the Stories composer with [video] already loaded. */
    fun openStoryComposer(context: Context, video: File): Boolean =
        launch(context, video, ACTION_STORY, "video/mp4")

    /**
     * Opens Instagram so a Reel can be created from a video already sitting in the phone's
     * gallery, with the caption waiting on the clipboard.
     *
     * There is no way to hand a video straight to the Reels composer: `ADD_TO_REEL` is only
     * honoured for apps Meta approved for "Sharing to Reels", and an unapproved caller is
     * silently bounced to Stories or the home feed (verified on Instagram 444.x from this app,
     * from a direct component launch, and from the shell). Instagram's share targets are not a
     * way around it either — Samsung's share sheet collapses them into a single entry that
     * lands in Direct. So the video is saved to the gallery first and picked there instead.
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
            putExtra("source_application", SOURCE_APPLICATION)
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

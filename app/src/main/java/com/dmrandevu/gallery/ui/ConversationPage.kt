package com.dmrandevu.gallery.ui

import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dmrandevu.gallery.R
import com.dmrandevu.gallery.ServiceLocator
import com.dmrandevu.gallery.data.Conversation
import com.dmrandevu.gallery.data.UnauthorizedException
import com.dmrandevu.gallery.media.InstagramSharing
import com.dmrandevu.gallery.player.PlayerManager
import kotlinx.coroutines.launch

/**
 * One customer, full screen. Horizontal swipes move between that customer's videos and never
 * delete anything — only the vertical swipe (handled by [GalleryViewModel]) does.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun ConversationPage(
    conversation: Conversation,
    page: Int,
    isActivePage: Boolean,
    isNextPage: Boolean,
    playerManager: PlayerManager,
    viewModel: GalleryViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = ServiceLocator.repository
    val downloader = ServiceLocator.downloader

    val mediaPager = rememberPagerState(pageCount = { conversation.urls.size })
    var downloading by remember { mutableStateOf(false) }
    var sharingStory by remember { mutableStateOf(false) }
    var sharingReels by remember { mutableStateOf(false) }
    var captionForUrl by remember { mutableStateOf<String?>(null) }

    val currentRawUrl = conversation.urls.getOrNull(mediaPager.currentPage)
    val currentProxyUrl = currentRawUrl?.let(repository::proxyUrl)

    // Playback follows the settled vertical page; the neighbouring page only pre-buffers.
    LaunchedEffect(isActivePage, isNextPage, currentProxyUrl) {
        val proxyUrl = currentProxyUrl ?: return@LaunchedEffect
        when {
            isActivePage -> playerManager.play(conversation.key, proxyUrl)
            isNextPage -> playerManager.preload(conversation.key, proxyUrl)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = mediaPager,
            modifier = Modifier.fillMaxSize()
        ) { mediaIndex ->
            val rawUrl = conversation.urls[mediaIndex]
            val proxyUrl = repository.proxyUrl(rawUrl)
            val isExpired = viewModel.expiredUrls[proxyUrl] == true
            val isCurrentMedia = mediaPager.currentPage == mediaIndex

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    isExpired -> Text(
                        text = stringResource(R.string.video_expired),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyLarge
                    )

                    isActivePage && isCurrentMedia -> AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                setBackgroundColor(android.graphics.Color.BLACK)
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        update = { view -> view.player = playerManager.playerFor(conversation.key) },
                        onRelease = { view -> view.player = null },
                        modifier = Modifier.fillMaxSize()
                    )

                    else -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.35f))
                }
            }
        }

        // Scrims: white controls have to stay readable over a bright frame.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent))
                )
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(160.dp)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))
                )
        )

        // Header: which customer this is, and where we are in their videos.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "@${conversation.clientName}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
                // Per-video, so it follows horizontal swipes within the conversation.
                formatSentAt(conversation.sentAt(mediaPager.currentPage))?.let { sentAt ->
                    Text(
                        text = sentAt,
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            // How many customers are still waiting. The dots below already say how many
            // videos this one has, so the per-video position is not repeated here.
            val remaining by viewModel.remaining.collectAsStateWithLifecycle()
            if (remaining > 0) {
                Text(
                    text = remaining.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // Dots + actions.
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.urls.size > 1) {
                    repeat(conversation.urls.size) { index ->
                        val active = mediaPager.currentPage == index
                        Box(
                            Modifier
                                .padding(end = 6.dp)
                                .size(if (active) 8.dp else 6.dp)
                                .background(
                                    if (active) Color.White else Color.White.copy(alpha = 0.4f),
                                    CircleShape
                                )
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionButton(
                    icon = Icons.Filled.Download,
                    label = stringResource(if (downloading) R.string.downloading else R.string.download),
                    busy = downloading,
                    enabled = currentRawUrl != null
                ) {
                    val rawUrl = currentRawUrl ?: return@ActionButton
                    downloading = true
                    scope.launch {
                        val message = try {
                            if (downloader.saveToGallery(rawUrl, conversation.clientName)) {
                                R.string.download_done
                            } else {
                                R.string.download_failed
                            }
                        } catch (e: UnauthorizedException) {
                            viewModel.reportSessionLost()
                            R.string.download_failed
                        } finally {
                            downloading = false
                        }
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }

                // Stories carry no caption, so this is a straight hand-off of the video.
                ActionButton(
                    icon = Icons.Filled.AddCircleOutline,
                    label = stringResource(R.string.story),
                    busy = sharingStory,
                    enabled = currentRawUrl != null
                ) {
                    val rawUrl = currentRawUrl ?: return@ActionButton
                    if (!InstagramSharing.isInstalled(context)) {
                        Toast.makeText(context, R.string.instagram_missing, Toast.LENGTH_SHORT).show()
                        return@ActionButton
                    }
                    sharingStory = true
                    scope.launch {
                        try {
                            val file = downloader.downloadForShare(rawUrl, conversation.clientName)
                            InstagramSharing.openStoryComposer(context, file)
                        } catch (e: UnauthorizedException) {
                            viewModel.reportSessionLost()
                        } catch (e: Exception) {
                            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
                        } finally {
                            sharingStory = false
                        }
                    }
                }

                // Reels takes the video directly; the caption can only ride the clipboard,
                // so it is generated first and the operator pastes it in the composer.
                ActionButton(
                    icon = Icons.Filled.Theaters,
                    label = stringResource(R.string.reels),
                    busy = sharingReels,
                    enabled = currentRawUrl != null
                ) {
                    val rawUrl = currentRawUrl ?: return@ActionButton
                    if (!InstagramSharing.isInstalled(context)) {
                        Toast.makeText(context, R.string.instagram_missing, Toast.LENGTH_SHORT).show()
                        return@ActionButton
                    }
                    sharingReels = true
                    scope.launch {
                        try {
                            // Saved to the gallery rather than handed over directly, because
                            // Reels can only take a video the operator picks there.
                            downloader.saveToGallery(rawUrl, conversation.clientName)
                            val caption = runCatching {
                                repository.generateCaption(
                                    salonId = conversation.salonId,
                                    clientId = conversation.clientId,
                                    rawMediaUrl = rawUrl
                                )
                            }.getOrNull()
                            val hasCaption = !caption.isNullOrBlank()
                            if (hasCaption) InstagramSharing.copyCaption(context, caption!!)
                            Toast.makeText(
                                context,
                                if (hasCaption) R.string.reels_ready else R.string.reels_ready_no_caption,
                                Toast.LENGTH_LONG
                            ).show()
                            InstagramSharing.openInstagram(context)
                        } catch (e: UnauthorizedException) {
                            viewModel.reportSessionLost()
                        } catch (e: Exception) {
                            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
                        } finally {
                            sharingReels = false
                        }
                    }
                }

                ActionButton(
                    icon = Icons.Filled.AutoAwesome,
                    label = stringResource(R.string.caption),
                    busy = false,
                    enabled = currentRawUrl != null
                ) {
                    captionForUrl = currentRawUrl
                }
            }
        }
    }

    captionForUrl?.let { rawUrl ->
        CaptionSheet(
            conversation = conversation,
            rawMediaUrl = rawUrl,
            onSessionLost = viewModel::reportSessionLost,
            onDismiss = { captionForUrl = null }
        )
    }
}

/**
 * One compact action in the bottom bar. Four of these have to share the width, so the label
 * sits under the icon and the busy state replaces the icon rather than adding to the row.
 */
@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled && !busy,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) {
                CircularProgressIndicator(
                    Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else {
                Icon(icon, contentDescription = label, tint = Color.White)
            }
            Text(
                text = label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

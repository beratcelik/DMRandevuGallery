package com.dmrandevu.gallery.ui

import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOff
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.BrandingWatermark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Theaters
import kotlinx.coroutines.CancellationException
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.dmrandevu.gallery.R
import com.dmrandevu.gallery.media.censor.BeepPlayer
import com.dmrandevu.gallery.media.censor.CensorWindow
import com.dmrandevu.gallery.ServiceLocator
import com.dmrandevu.gallery.data.Conversation
import com.dmrandevu.gallery.data.UnauthorizedException
import com.dmrandevu.gallery.media.InstagramSharing
import com.dmrandevu.gallery.media.VideoExporter
import com.dmrandevu.gallery.player.PlaybackFailure
import com.dmrandevu.gallery.player.PlayerManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One customer, full screen. Horizontal swipes move between that customer's videos and never
 * delete anything — only the vertical swipe (handled by [GalleryViewModel]) does.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    // Percentage of the running export, or null while nothing is being processed. Only one
    // action can run at a time, so a single holder covers all three buttons.
    var exportProgress by remember { mutableStateOf<Int?>(null) }

    // Playback controls. Hidden until the screen is touched, because the video is the point.
    var controlsShown by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }

    val blurFaces by viewModel.blurFaces.collectAsStateWithLifecycle()
    val blurPlates by viewModel.blurPlates.collectAsStateWithLifecycle()
    val fastPlates by viewModel.fastPlates.collectAsStateWithLifecycle()
    val watermark by viewModel.watermark.collectAsStateWithLifecycle()
    val censorAudio by viewModel.censorAudio.collectAsStateWithLifecycle()
    // Non-null only while the models are coming down, which is a one-off on first use.
    var censorDownload by remember { mutableStateOf<Int?>(null) }
    // True only while the server is being asked for a fresh link.
    var refreshing by remember { mutableStateOf(false) }
    /// Where the video was when the mark button went down, or null when nothing is being marked.
    var markingFrom by remember { mutableStateOf<Long?>(null) }
    val markRevision = viewModel.markRevision

    // The censor tone, played over the video while a marked stretch goes past so the operator can
    // hear what they marked rather than reading a red bar and hoping.
    val beeps = remember { BeepPlayer() }
    DisposableEffect(Unit) { onDispose { beeps.stop() } }
    // Exports share one cache directory and one progress readout, so they have to run one at a
    // time — a second one starting would wipe the first one's working files out from under it.
    val exporting = downloading || sharingStory || sharingReels

    val currentRawUrl = conversation.urls.getOrNull(mediaPager.currentPage)
    val currentProxyUrl = currentRawUrl?.let(repository::proxyUrl)

    // The player has no position callback, so it gets read on a timer while this page is the one
    // on screen. Paused while scrubbing, or the thumb would fight the poll for the same value.
    LaunchedEffect(isActivePage, currentProxyUrl) {
        while (isActivePage) {
            playerManager.playerHolding(conversation.key)?.let { player ->
                if (!scrubbing) positionMs = player.currentPosition
                durationMs = player.duration.takeIf { it > 0 } ?: 0L
            }
            delay(POSITION_POLL_MS)
        }
    }

    // Anything the operator does keeps the controls up; going quiet puts them away again. A
    // paused video is not "going quiet" — the bar is the reason it was paused.
    LaunchedEffect(controlsShown, scrubbing, paused) {
        if (controlsShown && !scrubbing && !paused) {
            delay(CONTROLS_LINGER_MS)
            controlsShown = false
        }
    }

    // A different video always starts playing, however the last one was left.
    LaunchedEffect(currentProxyUrl, isActivePage) { paused = false }

    LaunchedEffect(paused, isActivePage) {
        if (isActivePage) playerManager.setPaused(conversation.key, paused)
    }

    // Holding the screen runs the video fast; letting go puts it back. Reset on leaving the page
    // too, or a video swiped away mid-hold would still be racing when it came back.
    LaunchedEffect(holding, isActivePage) {
        playerManager.setSpeed(conversation.key, if (holding && isActivePage) HOLD_SPEED else 1f)
    }

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
        Box(
            Modifier
                .fillMaxSize()
                // Wrapped around the pager rather than laid over it. As an overlaying sibling
                // this won the hit test outright and the pager never saw a swipe again; as the
                // pager's parent, a drag reaches the pager first and only surfaces here if the
                // pager did not want it. The controls stay siblings on top, so pressing one
                // never reaches this at all.
                .pointerInput(conversation.key) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var somebodyElses = false

                        // Time runs out, the finger lifts, the finger starts travelling, or something
                        // nearer the touch takes it — whichever happens first says what this was.
                        val lifted = withTimeoutOrNull(HOLD_THRESHOLD_MS) {
                            var up: PointerInputChange? = null
                            while (up == null && !somebodyElses) {
                                val change = awaitPointerEvent().changes
                                    .firstOrNull { it.id == down.id } ?: break
                                when {
                                    // A pager or a button took it. Holding the screen still asks for
                                    // fast playback; dragging across it, or pressing a control that
                                    // happens to sit on it, does not.
                                    change.isConsumed -> somebodyElses = true
                                    change.changedToUp() -> up = change
                                    (change.position - down.position).getDistance() >
                                        viewConfiguration.touchSlop -> somebodyElses = true
                                }
                            }
                            up
                        }

                        when {
                            // Never about the video, so leave it alone.
                            somebodyElses -> Unit

                            lifted != null -> {
                                // A quick tap stops or restarts the video, and brings the controls
                                // up — stopping to look at something is when the bar is wanted.
                                paused = !paused
                                controlsShown = true
                            }

                            else -> {
                                holding = true
                                waitForUpOrCancellation()
                                holding = false
                            }
                        }
                    }
                }
        ) {
        HorizontalPager(
            state = mediaPager,
            modifier = Modifier.fillMaxSize()
        ) { mediaIndex ->
            val rawUrl = conversation.urls[mediaIndex]
            val proxyUrl = repository.proxyUrl(rawUrl)
            val failure = viewModel.failures[proxyUrl]
            val isCurrentMedia = mediaPager.currentPage == mediaIndex

            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    // The link is dead, so trying it again would fail the same way — but the
                    // server re-signs these on request, so asking for the conversation again
                    // gets one that works. That is what this retry does, unlike the transient
                    // one below.
                    failure == PlaybackFailure.LINK_DEAD -> PlaybackRetry(
                        message = stringResource(R.string.video_expired),
                        busy = refreshing,
                        onRetry = {
                            refreshing = true
                            scope.launch {
                                val renewed = viewModel.refreshLinks(conversation)
                                refreshing = false
                                if (!renewed) {
                                    Toast.makeText(
                                        context, R.string.video_refresh_failed, Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )

                    // Nothing about this one says the video itself is bad, so it keeps the offer
                    // of another go instead of being written off for the rest of the session.
                    failure == PlaybackFailure.TRANSIENT -> PlaybackRetry(
                        message = stringResource(R.string.video_failed),
                        onRetry = {
                            viewModel.clearFailure(proxyUrl)
                            playerManager.play(conversation.key, proxyUrl)
                        }
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

        if (paused) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.resume),
                tint = Color.White.copy(alpha = 0.75f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(72.dp)
            )
        }

        if (holding) {
            SpeedBadge(
                speed = HOLD_SPEED.toInt(),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // Header: which customer this is, and where we are in their videos.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Yields to the filters rather than pushing them off the screen: a long handle like
            // @ismailakbaba_gayrimenkul otherwise takes the whole width and the last toggle
            // simply is not there.
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = "@${conversation.clientName}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Up here rather than in the action row below, which is already tight on width.
                IconButton(
                    modifier = Modifier.size(FILTER_TOGGLE),
                    onClick = {
                        viewModel.setBlurFaces(!blurFaces)
                        Toast.makeText(
                            context,
                            if (blurFaces) R.string.face_blur_off else R.string.face_blur_on,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Icon(
                        imageVector = if (blurFaces) Icons.Filled.BlurOn else Icons.Filled.BlurOff,
                        contentDescription = stringResource(R.string.face_blur_toggle),
                        tint = if (blurFaces) Color.White else Color.White.copy(alpha = 0.45f)
                    )
                }
                Box(
                    // Tap switches the filter; holding switches how hard it looks. Tucked behind
                    // a long press because it is a knob to set once, not one to reach for daily.
                    Modifier.size(FILTER_TOGGLE).combinedClickable(
                        onClick = {
                            viewModel.setBlurPlates(!blurPlates)
                            Toast.makeText(
                                context,
                                if (blurPlates) R.string.plate_blur_off else R.string.plate_blur_on,
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onLongClick = {
                            viewModel.setFastPlates(!fastPlates)
                            Toast.makeText(
                                context,
                                if (fastPlates) R.string.plates_thorough else R.string.plates_fast,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                ) {
                    val plateTint =
                        if (blurPlates) Color.White else Color.White.copy(alpha = 0.45f)
                    Icon(
                        imageVector = Icons.Filled.DirectionsCar,
                        contentDescription = stringResource(R.string.plate_blur_toggle),
                        tint = plateTint,
                        modifier = Modifier.padding(12.dp)
                    )
                    if (fastPlates) {
                        // A bolt on the corner for the quicker setting, nothing for the thorough
                        // one — so the icon says which of the two the long press left it on.
                        Icon(
                            imageVector = Icons.Filled.Bolt,
                            contentDescription = stringResource(R.string.plates_fast_badge),
                            tint = plateTint,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 4.dp, bottom = 8.dp)
                                .size(14.dp)
                        )
                    }
                }
                IconButton(
                    modifier = Modifier.size(FILTER_TOGGLE),
                    onClick = {
                        viewModel.setWatermark(!watermark)
                        Toast.makeText(
                            context,
                            if (watermark) R.string.watermark_off else R.string.watermark_on,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.BrandingWatermark,
                        contentDescription = stringResource(R.string.watermark_toggle),
                        tint = if (watermark) Color.White else Color.White.copy(alpha = 0.45f)
                    )
                }
                IconButton(
                    modifier = Modifier.size(FILTER_TOGGLE),
                    enabled = censorDownload == null,
                    onClick = {
                        if (censorAudio) {
                            viewModel.setCensorAudio(false)
                            Toast.makeText(context, R.string.censor_audio_off, Toast.LENGTH_SHORT)
                                .show()
                            return@IconButton
                        }
                        // The models are a third of a gigabyte and are not in the app, so the
                        // first time this is switched on it has to fetch them. Switched on only
                        // once they are all here: a half-downloaded model would fail every
                        // export instead of censoring anything.
                        scope.launch {
                            try {
                                censorDownload = 0
                                ServiceLocator.censorModels.ensureAvailable { fraction ->
                                    censorDownload = (fraction * 100).toInt()
                                }
                                viewModel.setCensorAudio(true)
                                Toast.makeText(
                                    context, R.string.censor_audio_on, Toast.LENGTH_LONG
                                ).show()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context, R.string.censor_models_failed, Toast.LENGTH_LONG
                                ).show()
                            } finally {
                                censorDownload = null
                            }
                        }
                    }
                ) {
                    val progress = censorDownload
                    if (progress != null) {
                        Text(
                            text = stringResource(R.string.censor_models_downloading, progress),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    } else {
                        Icon(
                            imageVector = if (censorAudio) {
                                Icons.AutoMirrored.Filled.VolumeOff
                            } else {
                                Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = stringResource(R.string.censor_audio_toggle),
                            tint = if (censorAudio) Color.White else Color.White.copy(alpha = 0.45f)
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
        }

        if (controlsShown) {
            VideoScrubber(
                marks = remember(markRevision, conversation.key, mediaPager.currentPage) {
                    viewModel.manualMarks(conversation.key, mediaPager.currentPage)
                        .map { it.startUs / 1000..it.endUs / 1000 }
                },
                positionMs = positionMs,
                durationMs = durationMs,
                onScrubTo = {
                    scrubbing = true
                    positionMs = it
                },
                onScrubFinished = {
                    playerManager.playerHolding(conversation.key)?.seekTo(positionMs)
                    scrubbing = false
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 92.dp)
            )

            if (censorAudio) {
                MarkButton(
                    marking = markingFrom != null,
                    onPress = {
                        // Where the video is now, not where the finger went down on screen.
                        markingFrom = positionMs
                        controlsShown = true
                    },
                    onRelease = {
                        val from = markingFrom
                        markingFrom = null
                        if (from != null && positionMs > from) {
                            viewModel.addMark(
                                conversation.key,
                                mediaPager.currentPage,
                                CensorWindow(from * 1_000, positionMs * 1_000)
                            )
                        }
                        controlsShown = true
                    },
                    onRemove = {
                        viewModel.removeMarkAt(
                            conversation.key, mediaPager.currentPage, positionMs * 1_000
                        )
                        controlsShown = true
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 140.dp)
                )
            }
        }

        // Whether the playhead is inside something marked — including the mark being made right
        // now, which is the moment the operator most wants to hear.
        val marked = remember(markRevision, conversation.key, mediaPager.currentPage) {
            viewModel.manualMarks(conversation.key, mediaPager.currentPage)
        }
        val inMark = censorAudio && isActivePage && !paused && (
            markingFrom != null ||
                marked.any { positionMs * 1_000 in it.startUs..it.endUs }
            )
        LaunchedEffect(inMark, conversation.key) {
            playerManager.setDucked(conversation.key, inMark)
            if (inMark) beeps.start() else beeps.stop()
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
                    label = exportProgress.percentWhen(
                        downloading,
                        if (downloading) R.string.downloading else R.string.download
                    ),
                    busy = downloading,
                    enabled = currentRawUrl != null && !exporting
                ) {
                    val rawUrl = currentRawUrl ?: return@ActionButton
                    downloading = true
                    scope.launch {
                        val message = try {
                            val saved = downloader.saveToGallery(
                                rawUrl,
                                conversation.clientName,
                                viewModel.exportOptions(conversation.key, mediaPager.currentPage)
                            ) { exportProgress = it }
                            if (saved) R.string.download_done else R.string.download_failed
                        } catch (e: UnauthorizedException) {
                            viewModel.reportSessionLost()
                            R.string.download_failed
                        } catch (e: VideoExporter.ExportFailedException) {
                            // Swearing heard but not placed is a different thing from a broken
                            // export: the video really does need handling, and saying so is the
                            // difference between the operator checking it and assuming a glitch.
                            if (ServiceLocator.exporter.isUnplacedProfanity(e)) {
                                R.string.censor_unplaced
                            } else {
                                R.string.export_failed
                            }
                        } finally {
                            downloading = false
                            exportProgress = null
                        }
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                    }
                }

                // Stories carry no caption, so this is a straight hand-off of the video.
                ActionButton(
                    icon = Icons.Filled.AddCircleOutline,
                    label = exportProgress.percentWhen(sharingStory, R.string.story),
                    busy = sharingStory,
                    enabled = currentRawUrl != null && !exporting
                ) {
                    val rawUrl = currentRawUrl ?: return@ActionButton
                    if (!InstagramSharing.isInstalled(context)) {
                        Toast.makeText(context, R.string.instagram_missing, Toast.LENGTH_SHORT).show()
                        return@ActionButton
                    }
                    sharingStory = true
                    scope.launch {
                        try {
                            val file = downloader.downloadForShare(
                                rawUrl,
                                conversation.clientName,
                                viewModel.exportOptions(conversation.key, mediaPager.currentPage)
                            ) { exportProgress = it }
                            InstagramSharing.openStoryComposer(context, file)
                        } catch (e: UnauthorizedException) {
                            viewModel.reportSessionLost()
                        } catch (e: VideoExporter.ExportFailedException) {
                            Toast.makeText(
                                context,
                                if (ServiceLocator.exporter.isUnplacedProfanity(e)) {
                                    R.string.censor_unplaced
                                } else {
                                    R.string.export_failed
                                },
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
                        } finally {
                            sharingStory = false
                            exportProgress = null
                        }
                    }
                }

                // Reels takes the video directly; the caption can only ride the clipboard,
                // so it is generated first and the operator pastes it in the composer.
                ActionButton(
                    icon = Icons.Filled.Theaters,
                    label = exportProgress.percentWhen(sharingReels, R.string.reels),
                    busy = sharingReels,
                    enabled = currentRawUrl != null && !exporting
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
                            downloader.saveToGallery(
                                rawUrl,
                                conversation.clientName,
                                viewModel.exportOptions(conversation.key, mediaPager.currentPage)
                            ) { exportProgress = it }
                            exportProgress = null
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
                        } catch (e: VideoExporter.ExportFailedException) {
                            Toast.makeText(
                                context,
                                if (ServiceLocator.exporter.isUnplacedProfanity(e)) {
                                    R.string.censor_unplaced
                                } else {
                                    R.string.export_failed
                                },
                                Toast.LENGTH_LONG
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
                        } finally {
                            sharingReels = false
                            exportProgress = null
                        }
                    }
                }

                ActionButton(
                    icon = Icons.Filled.AutoAwesome,
                    label = stringResource(R.string.caption),
                    busy = false,
                    enabled = currentRawUrl != null && !exporting
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

/** Stands in for a video that fell over for a reason that may well not happen twice. */
@Composable
private fun PlaybackRetry(
    message: String,
    busy: Boolean = false,
    onRetry: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodyLarge
        )
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.video_retry), color = Color.White)
            }
        }
    }
}

/**
 * The export's percentage while [busy], otherwise [fallbackRes]. Blurring a video takes long
 * enough that a spinner alone leaves the operator wondering whether it is stuck. The progress
 * holder is shared, so the flag keeps the count on the one button that is actually working.
 */
@Composable
private fun Int?.percentWhen(busy: Boolean, fallbackRes: Int): String =
    if (busy && this != null) stringResource(R.string.face_blur_progress, this)
    else stringResource(fallbackRes)

/** How long the controls stay up once nothing is happening. */
private const val CONTROLS_LINGER_MS = 3_000L

/** How often the player is asked where it has got to. */
private const val POSITION_POLL_MS = 120L

/** Past this, a press is a hold rather than a tap. */
private const val HOLD_THRESHOLD_MS = 250L

/** How much faster a held-down video runs. */
private const val HOLD_SPEED = 3f

/**
 * Narrower than the default 48dp button. Four filters and the waiting count have to sit beside a
 * customer handle, and at the default the last one lands off the edge of the screen.
 */
private val FILTER_TOGGLE = 42.dp

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

/**
 * Hold while the swearing plays; let go when it stops.
 *
 * The obvious alternative was dragging a range along the scrubber, which means finding a moment
 * you have already heard go past. Holding is how the operator experiences the problem: the word
 * arrives, the thumb goes down, the word ends, the thumb comes up.
 *
 * Deliberately not the video surface, which already means run-at-triple-speed while held.
 */
@Composable
private fun MarkButton(
    marking: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .background(
                    if (marking) MarkColour else Color.Black.copy(alpha = 0.55f),
                    CircleShape
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        onPress()
                        // Whether the finger lifted or slid off, the mark ends here — a mark left
                        // open would keep growing for the rest of the video.
                        waitForUpOrCancellation()
                        onRelease()
                    }
                }
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Text(
                text = stringResource(
                    if (marking) R.string.mark_holding else R.string.mark_hint
                ),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
        }
        TextButton(onClick = onRemove) {
            Text(stringResource(R.string.mark_remove), color = Color.White.copy(alpha = 0.8f))
        }
    }
}

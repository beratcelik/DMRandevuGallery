package com.dmrandevu.gallery.ui

import android.util.Log
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurOff
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.BrandingWatermark
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.TouchApp
import kotlinx.coroutines.CancellationException
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import com.dmrandevu.gallery.data.GalleryRepository
import com.dmrandevu.gallery.data.IhbarPhase
import com.dmrandevu.gallery.data.saysSomething
import com.dmrandevu.gallery.data.UnauthorizedException
import com.dmrandevu.gallery.media.InstagramSharing
import com.dmrandevu.gallery.media.VideoExporter
import com.dmrandevu.gallery.player.PlaybackFailure
import com.dmrandevu.gallery.player.PlayerManager
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * TEK video, tam ekran.
 *
 * ─── İKİ EKSEN DE YENİ ─────────────────────────────────────────────────────
 * DİKEY: bir sonraki video. Aynı müşterinin videoları bittiğinde sonraki
 * müşteriye geçiliyor ve geride bırakılan müşteri silme sırasına giriyor
 * (karar [GalleryViewModel.onPageSettled]'de, kuralı ui/FeedPages.kt'te).
 * YATAY: KARAR. Sağa at = "bu bir ihlal, ihbar et", sola at = "ihlal değil".
 *
 * Eskiden dikey eksen müşteriler, yatay eksen o müşterinin videoları arasında
 * geziniyordu ve karar ekranın köşesindeki iki kapsül düğmeyle veriliyordu.
 * Sahip yüzlerce video geziyor; kaydırma aynı kararı parmağın zaten bulunduğu
 * yerde veriyor.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@kotlin.OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun VideoPage(
    conversation: Conversation,
    page: FeedPage,
    isActivePage: Boolean,
    isNextPage: Boolean,
    playerManager: PlayerManager,
    viewModel: GalleryViewModel,
    /**
     * Karar verildikten sonra bir sonraki videoya geçiş.
     *
     * NEDEN ÇAĞIRANDAN GELİYOR: sayfalayıcının durumu [GalleryScreen]'de ve
     * orada kalmalı. Sayfayı buradan sürmek, her videonun kendi akışını
     * kaydırabilmesi demekti.
     */
    onAdvance: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = ServiceLocator.repository
    val downloader = ServiceLocator.downloader

    var downloading by remember { mutableStateOf(false) }
    var sharingStory by remember { mutableStateOf(false) }
    var sharingReels by remember { mutableStateOf(false) }
    // Reels akışının caption aşamasında mıyız: yüzde bittikten sonraki uzun bekleme.
    var captioningReels by remember { mutableStateOf(false) }
    var captionForUrl by remember { mutableStateOf<String?>(null) }
    // Belirteci yapıştırma penceresi açık mı.
    var ihbarTokenPrompt by remember { mutableStateOf(false) }
    // Toplu eleme onayı istenirken kaç video elenecek (null: pencere kapalı).
    var bulkDismissCount by remember { mutableStateOf<Int?>(null) }
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

    // ─── KARAR KAYDIRMASI ────────────────────────────────────────────────────
    // Parmağın taşıdığı yatay yol (px) ve kartın ölçülen genişliği. Eşik
    // hesabı dp'ye çevriliyor: piksel eşiği ekran yoğunluğuna bağlı olurdu ve
    // aynı parmak hareketi telefondan telefona farklı karar verirdi.
    var dragX by remember(page.id) { mutableFloatStateOf(0f) }
    // DİKEY DE İZLENİYOR ve bu, kartın "fiziksel" hissinin yarısı: parmağını
    // hafif yukarı doğru savuran birinin kartı da yukarı gidiyor. Yalnızca yatay
    // izleseydik kart bir rayda kayan bir panel gibi dururdu.
    var dragY by remember(page.id) { mutableFloatStateOf(0f) }
    // Parmağın karta DOKUNDUĞU yükseklik: üst yarıdan tutulan kart bir yöne,
    // alt yarıdan tutulan TERS yöne deviriliyor — gerçek bir kartı masada
    // itmenin yaptığı şey. Tinder'ın "eldeki kart" duygusunu veren ayrıntı bu;
    // sabit yönlü bir eğim, kartı bir animasyon gibi gösteriyor.
    var grabbedAbove by remember(page.id) { mutableStateOf(true) }
    // Kart uçarken ikinci bir hareket alınmıyor: uçuş sırasında yapılan yeni bir
    // sürükleme, henüz gönderilmemiş kararı ikinci kez tetiklerdi.
    var flying by remember(page.id) { mutableStateOf(false) }
    var screenWidthPx by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current.density
    val haptics = LocalView.current

    // Bu videonun ihbar durumu: hem çipin rengi hem de kaydırmanın ne yapacağı
    // buna bakıyor (onaylanmış kayıtta sola atış soru soruyor).
    val ihbarMark = viewModel.ihbarMark(conversation.key, page.mediaIndex)

    // Kaydırma YALNIZCA ihbar hesabında bir şey yapıyor. trafykamerasi'nin
    // videolarının ihbar sisteminde karşılığı hiç yok; orada kartı oynatmak,
    // hiçbir şey yapmayacak bir karar vaat etmek olurdu.
    val swipeEnabled = viewModel.ihbarEnabled

    val blurFaces by viewModel.blurFaces.collectAsStateWithLifecycle()
    val blurPlates by viewModel.blurPlates.collectAsStateWithLifecycle()
    val fastPlates by viewModel.fastPlates.collectAsStateWithLifecycle()
    val watermark by viewModel.watermark.collectAsStateWithLifecycle()
    val censorAudio by viewModel.censorAudio.collectAsStateWithLifecycle()
    val censorByHand by viewModel.censorByHand.collectAsStateWithLifecycle()
    // Non-null only while the models are coming down, which is a one-off on first use.
    var censorDownload by remember { mutableStateOf<Int?>(null) }
    // True only while the server is being asked for a fresh link.
    var refreshing by remember { mutableStateOf(false) }
    /// Where the video was when the mark button went down, or null when nothing is being marked.
    var markingFrom by remember { mutableStateOf<Long?>(null) }
    val markRevision = viewModel.markRevision

    // ─── KARTIN FİZİĞİ ───────────────────────────────────────────────────────
    //
    // Kart elden bırakıldığında iki şeyden biri oluyor: yerine OTURUYOR ya da
    // ekrandan UÇUP gidiyor. İkisi de animasyonlu; sert bir sıfırlama, kartı
    // "yarıda kesilmiş bir animasyon" gibi gösteriyor ve elle tutulan bir şey
    // olduğu duygusunu bozuyordu.

    /** Karar verilmedi: kart yaylanarak yerine oturuyor. */
    suspend fun settleBack() {
        coroutineScope {
            launch { animate(dragX, 0f, animationSpec = CARD_SPRING) { v, _ -> dragX = v } }
            launch { animate(dragY, 0f, animationSpec = CARD_SPRING) { v, _ -> dragY = v } }
        }
    }

    /**
     * Karar verildi: kart atıldığı yönde ekrandan çıkıyor, sonra akış ilerliyor.
     *
     * KARAR UÇUŞ BİTTİKTEN SONRA VERİLİYOR: kart hâlâ ekrandayken kuyruğa
     * yazsaydık geri alma çipi, kart uçarken belirir ve sahip "neyi geri
     * alıyorum" diye bakacağı videoyu görmeden karar vermiş olurdu.
     */
    suspend fun flingAway(width: Float, outcome: SwipeOutcome) {
        flying = true
        // Kararın kendisi: eşikteki hafif titreşimden AYRI ve daha ağır.
        haptics.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val target = if (outcome == SwipeOutcome.REPORT) {
            width * FLY_DISTANCE
        } else {
            -width * FLY_DISTANCE
        }
        coroutineScope {
            // Uçarken hafifçe aşağı düşüyor: düz bir yatay kayma, kartı bir
            // rayda gidiyormuş gibi gösteriyor.
            launch {
                animate(dragY, dragY + width * FLY_DROP, animationSpec = tween(FLY_MS)) { v, _ ->
                    dragY = v
                }
            }
            animate(
                dragX, target,
                animationSpec = tween(FLY_MS, easing = LinearOutSlowInEasing),
            ) { v, _ -> dragX = v }
        }

        viewModel.decide(conversation, page.mediaIndex, outcome)
        onAdvance()
        // Kart ekran dışındayken bekliyoruz: hemen sıfırlamak, akış bir sonraki
        // videoya kayarken kartın ortaya geri zıpladığını göstermek olurdu.
        delay(CARD_RESET_DELAY_MS)
        dragX = 0f
        dragY = 0f
        flying = false
    }

    // Sayfa görüş alanından çıktığında kart her hâlükârda ortalanıyor: uçuş
    // animasyonu yarıda kesilirse (sayfa bileşimden düştü, kapsam iptal oldu)
    // kart ekran dışında kalmış olurdu ve o sayfaya dönüldüğünde video
    // görünmezdi.
    LaunchedEffect(isActivePage) {
        if (!isActivePage) {
            dragX = 0f
            dragY = 0f
            flying = false
        }
    }

    // The censor tone, played over the video while a marked stretch goes past so the operator can
    // hear what they marked rather than reading a red bar and hoping.
    val beeps = remember { BeepPlayer() }
    DisposableEffect(Unit) { onDispose { beeps.stop() } }
    // Exports share one cache directory and one progress readout, so they have to run one at a
    // time — a second one starting would wipe the first one's working files out from under it.
    val exporting = downloading || sharingStory || sharingReels

    val currentRawUrl = conversation.urls.getOrNull(page.mediaIndex)
    val currentProxyUrl = currentRawUrl?.let(repository::proxyUrl)

    // The player has no position callback, so it gets read on a timer while this page is the one
    // on screen. Paused while scrubbing, or the thumb would fight the poll for the same value.
    LaunchedEffect(isActivePage, currentProxyUrl) {
        while (isActivePage) {
            playerManager.playerHolding(page.id)?.let { player ->
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
        if (isActivePage) playerManager.setPaused(page.id, paused)
    }

    // Holding the screen runs the video fast; letting go puts it back. Reset on leaving the page
    // too, or a video swiped away mid-hold would still be racing when it came back.
    LaunchedEffect(holding, isActivePage) {
        playerManager.setSpeed(page.id, if (holding && isActivePage) HOLD_SPEED else 1f)
    }

    // Playback follows the settled vertical page; the neighbouring page only pre-buffers.
    LaunchedEffect(isActivePage, isNextPage, currentProxyUrl) {
        val proxyUrl = currentProxyUrl ?: return@LaunchedEffect
        when {
            isActivePage -> playerManager.play(page.id, proxyUrl)
            isNextPage -> playerManager.preload(page.id, proxyUrl)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ─── KARAR YÜZEYİ ────────────────────────────────────────────────────
        //
        // Kart, kaydırıldığı yöne doğru kayıyor ve hafifçe dönüyor; damga
        // hangi kararın verileceğini söylüyor. Parmak kalkınca ya karar
        // veriliyor (ve kart uçup gidiyor) ya da kart yerine dönüyor.
        // Damga, KARARIN kendisine değil parmağın YÖNÜNE bakıyor ve eşikten
        // ÖNCE beliriyor. Karara bağlasaydık ancak eşik geçildikten sonra
        // görünürdü — yani sahip kararının ne olacağını, kararı verdikten sonra
        // öğrenirdi. Parmak kalkmadan önce görmek, yanlış kararı ağa hiç
        // çıkmadan engelleyen ilk (ve ücretsiz) fırsat.
        val stamp = when {
            dragX > STAMP_APPEARS_PX -> SwipeDecision.REPORT
            dragX < -STAMP_APPEARS_PX -> SwipeDecision.DISMISS
            else -> null
        }

        Box(
            Modifier
                .fillMaxSize()
                // YATAY JEST BURADA, DİKEY SAYFALAYICININ ALTINDA.
                //
                // awaitHorizontalTouchSlopOrCancellation YALNIZCA yatay eşikte
                // tetikleniyor: dikeyde baskın bir sürükleme hiç uyanmıyor ve
                // olay dikey sayfalayıcıya gidiyor. Yatay ekseni doldurmak
                // güvenli, çünkü o eksende artık başka hiçbir şey yok — videolar
                // arası yatay sayfalayıcı kaldırıldı.
                //
                // Değişiklikler TÜKETİLİYOR (change.consume): tüketilmemiş bir
                // yatay sürükleme, ebeveyn sayfalayıcının kendi eşik hesabına
                // girip sayfayı da kaydırmaya çalışırdı.
                .pointerInput(page.id, swipeEnabled) {
                    if (!swipeEnabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Kart uçarken yeni bir hareket alınmıyor.
                        if (flying) return@awaitEachGesture

                        val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            dragX = over
                        } ?: return@awaitEachGesture

                        // BASILI TUTMA İLE ÇAKIŞMA: hızlı oynatma çoktan
                        // başlamışsa bu bir karar değil, izleme hareketidir.
                        // Kartı oynatmıyoruz ve kararı vermiyoruz.
                        if (holding) {
                            dragX = 0f
                            return@awaitEachGesture
                        }

                        // Parmağın karta girdiği yükseklik, kartın hangi yöne
                        // devrileceğini belirliyor.
                        grabbedAbove = down.position.y < size.height / 2f

                        var travelledX = dragX
                        var travelledY = 0f
                        // Eşik geçildiğinde BİR KEZ titreşim: karar verilecek
                        // noktayı parmak kalkmadan önce bildiren tek sinyal.
                        var buzzed = false
                        val thresholdPx = size.width * SWIPE_DISTANCE_FRACTION

                        horizontalDrag(slop.id) { change ->
                            travelledX += change.positionChange().x
                            // DİKEYİ SÖNÜMLEYEREK İZLİYORUZ: kart parmağı
                            // birebir takip etseydi dikey sayfalayıcıyla
                            // yarışıyormuş gibi görünürdü. Üçte bir, "kart biraz
                            // savruldu" demeye yetiyor.
                            travelledY += change.positionChange().y * VERTICAL_FOLLOW
                            dragX = travelledX
                            dragY = travelledY
                            if (!buzzed && kotlin.math.abs(travelledX) >= thresholdPx) {
                                buzzed = true
                                haptics.performHapticFeedback(
                                    android.view.HapticFeedbackConstants.VIRTUAL_KEY
                                )
                            }
                            change.consume()
                        }

                        val verdict = decisionFor(
                            dragXdp = travelledX / density,
                            velocityDpPerSec = 0f,
                            widthDp = size.width / density,
                        )
                        val width = size.width.toFloat()

                        when {
                            // KART YERİNE DÖNÜYOR — ZIPLAYARAK. Sert bir sıfırlama
                            // kartı "iptal edilmiş bir animasyon" gibi gösteriyordu;
                            // yay, elden bırakılan bir kartın masaya oturması gibi.
                            verdict == null -> scope.launch { settleBack() }

                            ihbarMark.phase == IhbarPhase.NO_TOKEN -> {
                                // Belirteç yokken kaydırma ağa çıkmıyor; eksik
                                // olanı sormak tek makul davranış.
                                scope.launch { settleBack() }
                                ihbarTokenPrompt = true
                            }

                            else -> {
                                val outcome = if (verdict == SwipeDecision.REPORT) {
                                    SwipeOutcome.REPORT
                                } else {
                                    SwipeOutcome.DISMISS
                                }
                                scope.launch { flingAway(width, outcome) }
                            }
                        }
                    }
                }
                // Dokunma/basılı tutma bloğu jestin İÇİNDE değil ALTINDA:
                // yatay eşik geçilirse yukarıdaki blok olayı tüketiyor ve
                // buraya "başkası aldı" olarak yansıyor.
                .pointerInput(page.id) {
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
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { screenWidthPx = it.width.toFloat() }
                    .graphicsLayer {
                        translationX = dragX
                        translationY = dragY
                        // EĞİM, PARMAĞIN TUTTUĞU YERE GÖRE TERS DÖNÜYOR: üstten
                        // tutulan kart bir yöne, alttan tutulan öteki yöne
                        // deviriliyor — masadaki bir kartı iterken olan şey.
                        // Sabit yönlü bir eğim, kartı elle tutulan bir şey değil
                        // bir animasyon gibi gösteriyordu.
                        val tilt = if (grabbedAbove) 1f else -1f
                        rotationZ = (dragX / size.width) * CARD_TILT_DEGREES * tilt

                        // KART MASADAN KALKIYOR: parmak değdiği an hafifçe
                        // küçülüyor ve kenarları ekranın kenarından ayrılıyor.
                        //
                        // NEDEN ÖLÇEK (yuvarlatılmış köşe değil): köşeleri
                        // yuvarlatmak denendi ve tutmadı — video kendi donanım
                        // katmanında çiziliyor, üstteki katmanın DÖNÜŞÜNÜ alıyor
                        // ama KIRPMA yolunu almıyor; köşeler sipsivri kalıyordu.
                        // Ölçek aynı katmanın özelliği olduğu için dönüşle aynı
                        // yoldan geçiyor ve çalışıyor.
                        //
                        // Ve işi aynı: kenarların siyah zeminden ayrılması,
                        // ekranı bir anda ELLE TUTULAN bir nesneye çeviriyor.
                        // Durgun hâlde ölçek tam 1 — video tam ekran olmak
                        // zorunda, çünkü sahip plakayı, şeridi, ışığı o karede
                        // arıyor.
                        val lift = (kotlin.math.abs(dragX) / CARD_LIFT_AT)
                            .coerceIn(0f, 1f) * CARD_LIFT
                        scaleX = 1f - lift
                        scaleY = 1f - lift
                    },
                contentAlignment = Alignment.Center
            ) {
                val rawUrl = conversation.urls.getOrNull(page.mediaIndex)
                val proxyUrl = rawUrl?.let(repository::proxyUrl)
                val failure = proxyUrl?.let { viewModel.failures[it] }

                when {
                    rawUrl == null || proxyUrl == null ->
                        CircularProgressIndicator(color = Color.White.copy(alpha = 0.35f))

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
                            playerManager.play(page.id, proxyUrl)
                        }
                    )

                    isActivePage -> AndroidView(
                        // DÜZENDEN ŞİŞİRİLİYOR, koddan kurulmuyor: yüzey türü
                        // (doku / surface) yalnızca kuruluşta öznitelikten
                        // okunuyor ve karar kartının eğilebilmesi doku yüzeyine
                        // bağlı. Gerekçesi res/layout/video_surface.xml'de.
                        //
                        // Denetimin kapalı olması, en-boy kipi ve "son kareyi
                        // tut" da aynı dosyada: ikisini iki yere bölmek, koddaki
                        // ayarın XML'dekini sessizce ezmesi demekti.
                        factory = { ctx ->
                            (
                                android.view.LayoutInflater.from(ctx)
                                    .inflate(R.layout.video_surface, null) as PlayerView
                                ).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        // Reading the generation makes this run again when the slot's player is
                        // swapped for a fresh one, so the view never keeps a released player.
                        update = { view ->
                            playerManager.generation
                            view.player = playerManager.playerFor(page.id)
                        },
                        onRelease = { view -> view.player = null },
                        modifier = Modifier.fillMaxSize()
                    )

                    else -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.35f))
                }

                // DAMGA KARTIN İÇİNDE: kartla birlikte eğiliyor ve onunla
                // uçuyor. Kartın dışında, sabit dururken bir arayüz etiketi gibi
                // görünüyordu; üstüne yapıştırılmış bir mühür gibi durması,
                // kararın karta ait olduğunu söyleyen şey.
                if (stamp != null && screenWidthPx > 0f) {
                    SwipeStamp(
                        decision = stamp,
                        alpha = (kotlin.math.abs(dragX) / (screenWidthPx * STAMP_FULL_AT))
                            .coerceIn(0f, 1f),
                        modifier = Modifier
                            .align(
                                if (stamp == SwipeDecision.REPORT) {
                                    Alignment.TopStart
                                } else {
                                    Alignment.TopEnd
                                }
                            )
                            .padding(top = 120.dp, start = 24.dp, end = 24.dp)
                            // Mühür gibi eğik durması, ekrana yapıştırılmış bir
                            // etiket olmadığını söylüyor.
                            .graphicsLayer {
                                rotationZ = if (stamp == SwipeDecision.REPORT) -14f else 14f
                            }
                    )
                }
            }

            // Damga, kartla BİRLİKTE hareket etmiyor: kararın adı sabit durup
            // okunabilir kalmalı, kayan bir metin okunmuyor.

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

        // KENARDA, ORTADA DEĞİL: hızlı oynatma tam da operatörün videoya dikkatle baktığı an;
        // ortadaki rozet bakılan ayrıntının üstüne oturuyordu. Sol alt köşe, sağ şeritle aynı
        // yükseklikte; geri alma çipi oradaysa onun üstüne çıkıyor, altına girmiyor.
        if (holding) {
            val undoShowing = viewModel.pendingDecisions.all().isNotEmpty()
            SpeedBadge(
                speed = HOLD_SPEED.toInt(),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 12.dp,
                        bottom = when {
                            censorAudio -> 200.dp
                            controlsShown -> 148.dp
                            else -> 92.dp
                        } + if (undoShowing) 56.dp else 0.dp
                    )
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
            // Sıradaki müşteri sayısına yaslanıyor, onu ekrandan itmiyor: filtreler
            // sağ raya indikten sonra başlıkta itilecek tek şey o sayı kaldı, ama kural
            // aynı kaldı — @ismailakbaba_gayrimenkul gibi uzun bir kullanıcı adı yoksa
            // tüm genişliği alıp sayıyı ekran dışına taşırdı.
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = "@${conversation.clientName}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Video başına: dikey kaydırma videoyu değiştirdiğinde bu da
                // değişiyor.
                formatSentAt(conversation.sentAt(page.mediaIndex))?.let { sentAt ->
                    Text(
                        text = sentAt,
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Kaç müşteri sırada bekliyor. Düğme DEĞİL, o yüzden raya inmedi:
            // başlıkta kalması aynı zamanda yukarıdaki weight(1f, fill = false)
            // kuralını da ayakta tutuyor — uzun bir kullanıcı adının yaslanacağı
            // bir şey kalmasaydı elips hiç devreye girmezdi.
            val remaining by viewModel.remaining.collectAsStateWithLifecycle()
            if (remaining > 0) {
                Text(
                    text = remaining.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }

        // ─── FİLTRE RAYI ────────────────────────────────────────────────────
        //
        // SAĞ KENARDA, DİKEY: Instagram Reels'in beğen/yorum sütunuyla aynı yerde,
        // aynı ritimde. Başlıkta yatay bir sıraydılar ve orada iki sorunu vardı:
        // uzun bir kullanıcı adı son düğmeyi ekrandan itiyordu, ve başparmağın
        // doğal olarak durduğu yer değildi.
        //
        // ALTTAN ÇIPALI, ORTADAN DEĞİL: Instagram'da da sütun alt bloğun
        // hizasından yukarı doğru diziliyor. Bizde alt merdiven (eylem sırası 92,
        // ilerleme çubuğu 148, işaretleme düğmesi 200) aynı kademeleri istiyor;
        // GERİ AL çipiyle BİREBİR aynı merdiven kullanılıyor, biri solda biri
        // sağda aynı satırda dursun diye.
        //
        // YUKARIYA TAŞMIYOR: damga (kararın adı) sağ üstte 120dp'de duruyor ve
        // kart uçarken onun ÜSTÜNE çizilen bir ray, kararın okunmasını engellerdi.
        // Ray alt üçte birde kaldığı sürece ikisi hiç karşılaşmıyor.
        //
        // ARKA PLAN KÖŞEGEN SOLUYOR: sağ kenarda scrim yok (üstteki 140dp ve
        // alttaki 160dp yalnızca tam genişlik bantları), yani beyaz simgeler
        // aydınlık bir kareye düşünce kayboluyordu. Köşegen geçiş, dikdörtgenin
        // açıkta kalan iki kenarını (sol ve üst) saydam bırakıyor.
        //
        // DOKUNUŞ YUTMUYOR: Column'un kendisinde pointerInput yok, yalnızca
        // içindeki düğmelerin var. Aradaki boşluk karar sürüklemesine geçiyor.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    bottom = when {
                        censorAudio -> 200.dp
                        controlsShown -> 148.dp
                        else -> 92.dp
                    }
                )
                .background(
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.34f))
                    )
                )
                .padding(start = 28.dp, top = 20.dp, end = RAIL_EDGE, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(RAIL_GAP),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
            Box(
                // Tap switches the filter; holding switches whether it listens to the video
                // or only beeps what was marked by hand — the same shape as the plate toggle,
                // because it is the same kind of choice: a knob to set, not one to reach for.
                Modifier
                    .size(FILTER_TOGGLE)
                    .combinedClickable(
                        enabled = censorDownload == null,
                        onLongClick = {
                            viewModel.setCensorByHand(!censorByHand)
                            Toast.makeText(
                                context,
                                if (censorByHand) R.string.censor_auto else R.string.censor_by_hand,
                                Toast.LENGTH_LONG
                            ).show()
                        },
                        onClick = {
                            if (censorAudio) {
                        viewModel.setCensorAudio(false)
                        Toast.makeText(context, R.string.censor_audio_off, Toast.LENGTH_SHORT)
                            .show()
                        return@combinedClickable
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
                    ),
                contentAlignment = Alignment.Center
            ) {
                val progress = censorDownload
                if (progress != null) {
                    Text(
                        text = stringResource(R.string.censor_models_downloading, progress),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    val tint =
                        if (censorAudio) Color.White else Color.White.copy(alpha = 0.45f)
                    Icon(
                        imageVector = if (censorAudio) {
                            Icons.AutoMirrored.Filled.VolumeOff
                        } else {
                            Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = stringResource(R.string.censor_audio_toggle),
                        tint = tint
                    )
                    if (censorAudio && censorByHand) {
                        // A hand on the corner for the by-hand setting, nothing for
                        // automatic — so the icon says which of the two the long press left
                        // it on, the way the plate toggle's bolt does.
                        Icon(
                            imageVector = Icons.Filled.TouchApp,
                            contentDescription = stringResource(R.string.censor_by_hand_badge),
                            tint = tint,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(13.dp)
                        )
                    }
                }
            }
            // TOPLU ELEME: bu müşterinin karar verilmemiş videoları çoksa
            // hepsini tek istekte elemek. İki yazma ucu tek bir oran sınırı
            // kovasını paylaşıyor (dakikada yirmi); on beş videoyu tek tek
            // elemek o bütçenin dörtte üçünü yakar ve aynı dakikadaki
            // GERÇEK ihbarı da engellerdi.
            //
            // EN AZ İKİ VİDEO ŞARTI: tek video için toplu bir hareket,
            // kaydırmanın zaten yaptığı işi ikinci bir yüzeyden tekrar
            // sunmak olurdu.
            if (viewModel.ihbarEnabled) {
                val undecided = viewModel.undecidedIndices(conversation)
                if (undecided.size >= 2) {
                    IconButton(
                        modifier = Modifier.size(FILTER_TOGGLE),
                        onClick = { bulkDismissCount = undecided.size }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Block,
                            contentDescription = stringResource(R.string.bulk_dismiss),
                            tint = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }

        // ─── İHBAR DURUM ÇİPİ ────────────────────────────────────────────────
        //
        // SALT OKUNUR. Karar kaydırmayla veriliyor; çip yalnızca sonucu
        // gösteriyor: bu videoya ne dedim, sunucu ne yaptı. İki istisna dokunuş
        // kabul ediyor ve ikisi de karar değil (belirteç penceresi).
        //
        // ÜSTTE, başlığın altında: ekranın altı zaten katmanlı ve kaydırma
        // kartın TAMAMINI hareket ettiriyor — karar yüzeyiyle aynı yerde duran
        // bir gösterge her kaydırmada parmağın altında kalırdı.
        // Çip YALNIZCA söyleyecek bir şeyi varken çiziliyor; gerekçesi
        // IhbarMark.saysSomething başlığında.
        if (viewModel.ihbarEnabled && ihbarMark.saysSomething) {
            IhbarStatusChip(
                mark = ihbarMark,
                onTap = { if (ihbarMark.phase == IhbarPhase.NO_TOKEN) ihbarTokenPrompt = true },
                onLongPress = { ihbarTokenPrompt = true },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 72.dp)
            )
        }

        // The scrubber stays up while the filter is on. Marking is aiming at a moment, and a
        // bar that hides itself three seconds in is no use for that.
        if (controlsShown || censorAudio) {
            VideoScrubber(
                marks = remember(markRevision, conversation.key, page.mediaIndex) {
                    viewModel.manualMarks(conversation.key, page.mediaIndex)
                        .map { it.startUs / 1000..it.endUs / 1000 }
                },
                positionMs = positionMs,
                durationMs = durationMs,
                onScrubTo = {
                    scrubbing = true
                    positionMs = it
                },
                onScrubFinished = {
                    playerManager.playerHolding(page.id)?.seekTo(positionMs)
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
                                page.mediaIndex,
                                CensorWindow(from * 1_000, positionMs * 1_000)
                            )
                        }
                        controlsShown = true
                    },
                    onRemove = {
                        viewModel.removeMarkAt(
                            conversation.key, page.mediaIndex, positionMs * 1_000
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
        val marked = remember(markRevision, conversation.key, page.mediaIndex) {
            viewModel.manualMarks(conversation.key, page.mediaIndex)
        }
        val inMark = censorAudio && isActivePage && !paused && (
            markingFrom != null ||
                marked.any { positionMs * 1_000 in it.startUs..it.endUs }
            )
        LaunchedEffect(inMark, conversation.key) {
            playerManager.setDucked(page.id, inMark)
            if (inMark) beeps.start() else beeps.stop()
        }

        // ─── BEKLEYEN KARARIN GERİ ALMA ÇİPİ ────────────────────────────────
        //
        // Karar verildiği anda kart uçuyor ve akış bir sonraki videoya geçiyor:
        // sahip kararını verdiği videoyu ARTIK GÖRMÜYOR. Geri alma yolunun
        // kararla aynı anda ve aynı ekranda durması gerekiyor. İkinci yol,
        // o sayfaya geri kaydırmak (GalleryViewModel.onPageSettled).
        //
        // Ekranın altı katmanlı: eylem şeridi 0-92dp, oynatma çubuğu 92dp,
        // küfür işaretleme düğmesi 140dp. Çip o an açık olan en üst katmanın
        // üstüne çıkıyor; sabit bir yükseklik, çubuk açıldığı anda üst üste
        // binme demekti.
        val queued = viewModel.pendingDecisions.all().lastOrNull()
        if (queued != null) {
            UndoChip(
                decision = queued.decision,
                onUndo = { viewModel.undoDecision(queued.page.id) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = 12.dp,
                        bottom = when {
                            censorAudio -> 200.dp
                            controlsShown -> 148.dp
                            else -> 92.dp
                        }
                    )
            )
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
            // NOKTA DİZİSİ KALKTI, YERİNE SAYI: noktalar yatay bir sayfalayıcıyı
            // anlatıyordu ("sağa kaydır, sonraki video"). Videolar artık dikey
            // eksende; aynı noktalar şimdi var olmayan bir hareketi öğretirdi.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conversation.urls.size > 1) {
                    Text(
                        text = stringResource(
                            R.string.video_position,
                            page.mediaIndex + 1,
                            conversation.urls.size
                        ),
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelMedium
                    )
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
                                viewModel.exportOptions(conversation.key, page.mediaIndex)
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
                                viewModel.exportOptions(conversation.key, page.mediaIndex)
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

                // Videoyu Instagram'a VERMİYOR: galeriye kaydedip uygulamayı açıyor,
                // operatör Reels'te oradan seçiyor. Videoyu doğrudan besteciye veren
                // düğme caption sayfasındaki "Reels olarak paylaş". Caption her iki
                // yolda da panodan gidiyor; hiçbir Instagram girişi metin kabul etmiyor.
                ActionButton(
                    icon = Icons.Filled.Theaters,
                    // DIŞA AKTARMA BİTİNCE ETİKET SUSMUYOR: caption çağrısı 90 saniyeye
                    // kadar sürebiliyor ve yüzde sıfırlandığı an düğme "Reels" yazan boş
                    // bir dönece düşüyordu — en uzun aşama, hakkında en az şey söylenen
                    // aşamaydı.
                    label = if (captioningReels) {
                        stringResource(R.string.caption_generating)
                    } else {
                        exportProgress.percentWhen(sharingReels, R.string.reels)
                    },
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
                            // SIRA: hazırla, caption'ı panoya koy, SONRA devret.
                            // Sahibin istediği bu; caption hazır olmadan Instagram'a
                            // geçmek, yapıştıracak bir şey olmadan geçmek demek.
                            val file = downloader.downloadForShare(
                                rawUrl,
                                conversation.clientName,
                                viewModel.exportOptions(conversation.key, page.mediaIndex)
                            ) { exportProgress = it }
                            exportProgress = null

                            // GALERİYE DE BIRAKILIYOR, ikinci bir dışa aktarma olmadan.
                            // Besteci açıldıktan sonra Instagram kimliği reddederse bunu
                            // bize SÖYLEMİYOR; operatör hata penceresiyle kalıyor ve
                            // galerideki kopya o sessiz reddin tek telafisi oluyor.
                            val saved = downloader.saveFileToGallery(file, conversation.clientName)

                            captioningReels = true
                            val caption = try {
                                captionOrNull(repository, conversation, rawUrl)
                            } finally {
                                captioningReels = false
                            }
                            val hasCaption = !caption.isNullOrBlank()
                            if (hasCaption) InstagramSharing.copyCaption(context, caption!!)

                            // ÖNCE BESTECİ, sonra uygulama. Besteci niyeti hiç
                            // karşılanmazsa (eski Instagram, kaldırılmış paket) uygulamayı
                            // açmak hâlâ bir şeye yarıyor: video galeride duruyor.
                            //
                            // AÇILMADIYSA BAŞARILI DENMİYOR: isInstalled dakikalar önce,
                            // dışa aktarmadan da önce bakıyor ve dondurulmuş bir pakette
                            // bile doğru diyor.
                            val inComposer = InstagramSharing.openReelComposer(context, file)
                            val opened = inComposer || InstagramSharing.openInstagram(context)
                            Toast.makeText(
                                context,
                                when {
                                    !opened -> R.string.instagram_missing
                                    inComposer && hasCaption -> R.string.reels_composer_ready
                                    inComposer -> R.string.reels_composer_no_caption
                                    hasCaption && saved -> R.string.reels_ready
                                    else -> R.string.reels_ready_no_caption
                                },
                                Toast.LENGTH_LONG
                            ).show()
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
                        } catch (e: CancellationException) {
                            // Sayfadan ayrılmak kapsamı iptal ediyor. Bu bir arıza değil,
                            // operatörün kendi kaydırması; "paylaşım başarısız" demek
                            // olmayan bir hatayı bildirmek olurdu.
                            throw e
                        } catch (e: Exception) {
                            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
                        } finally {
                            sharingReels = false
                            captioningReels = false
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

    bulkDismissCount?.let { count ->
        AlertDialog(
            onDismissRequest = { bulkDismissCount = null },
            title = { Text(stringResource(R.string.bulk_dismiss_title)) },
            text = {
                Text(
                    text = stringResource(R.string.bulk_dismiss_explain, count),
                    style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    bulkDismissCount = null
                    viewModel.dismissAll(conversation)
                }) {
                    Text(stringResource(R.string.bulk_dismiss_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { bulkDismissCount = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (ihbarTokenPrompt) {
        IhbarTokenDialog(
            onDismiss = { ihbarTokenPrompt = false },
            onSave = { token ->
                viewModel.saveIhbarToken(token)
                ihbarTokenPrompt = false
            }
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
 * Kartın kaydırılırken eğildiği açı (derece, tam genişlikte).
 *
 * KÜÇÜK TUTULUYOR: kart bir fotoğraf değil, oynayan bir video. Büyük bir eğim
 * kararı daha "oyun gibi" yapar ama izlenen görüntüyü okunmaz hâle getirir —
 * oysa sahip tam da o anda görüntüye bakarak karar veriyor.
 */
private const val CARD_TILT_DEGREES = 8f

/** Kartın tam kalktığı yatay yol (px). Kısa: kart hemen "ele geçmeli". */
private const val CARD_LIFT_AT = 90f

/**
 * Kalkan kartın küçülme oranı.
 *
 * KÜÇÜK (%4): daha fazlası videoyu belirgin biçimde küçültüyor ve sahip tam da
 * o anda görüntüye bakarak karar veriyor. Bu kadarı kenarları siyahtan ayırmaya
 * yetiyor, okunaklılığı bozmuyor.
 */
private const val CARD_LIFT = 0.04f

/**
 * Damganın tam görünür olduğu mesafe — genişliğin oranı.
 *
 * Karar eşiğinden (%25) KÜÇÜK: damga kararın verileceğini önceden söylemeli,
 * eşiğe varıldığında belirmemeli. Parmak kalkmadan önce "ne olacağını" görmek,
 * yanlış kararı ağa hiç çıkmadan engelleyen ilk fırsat.
 */
private const val STAMP_FULL_AT = 0.18f

/** Damganın belirmeye başladığı yatay yol (px). Parmak eşiği geçmeden görünür. */
private const val STAMP_APPEARS_PX = 8f

/** Dikey hareketin karta yansıyan oranı; birebir izlemek kartı savruk gösterir. */
private const val VERTICAL_FOLLOW = 0.34f

/** Kartın uçarken gittiği yol, genişliğin katı olarak — ekranı tam terk etmeli. */
private const val FLY_DISTANCE = 1.6f

/** Uçarken düştüğü mesafe (genişliğin oranı): düz bir kayma ray gibi görünüyor. */
private const val FLY_DROP = 0.12f

/** Uçuş süresi. Uzatmak kararı yavaşlatıyor, kısaltmak hareketi görünmez kılıyor. */
private const val FLY_MS = 260

/**
 * Uçuştan sonra kartın ortaya alınması için beklenen süre.
 *
 * Akışın bir sonraki videoya kayması bu kadar sürüyor; daha erken sıfırlamak,
 * kartın ekrandan çıkarken ortaya geri zıpladığını göstermek olurdu.
 */
private const val CARD_RESET_DELAY_MS = 160L

/**
 * Kartın yerine oturma yayı.
 *
 * Düşük sönüm (0,55) bir kez hafifçe geri sekmesini sağlıyor — elden bırakılan
 * bir kartın masaya oturması bu. Kritik sönüm (1,0) teknik olarak "doğru" ama
 * cansız; sekme, kartın bir ağırlığı olduğunu söyleyen tek şey.
 */
private val CARD_SPRING = spring<Float>(dampingRatio = 0.55f, stiffness = 380f)

/**
 * Narrower than the default 48dp button. Four filters and the waiting count have to sit beside a
 * customer handle, and at the default the last one lands off the edge of the screen.
 */
/**
 * Caption'ı getirir, üretilemezse null döner.
 *
 * OTURUM KAYBI YUKARI GEÇİYOR: burada yutulsaydı operatöre, oturumu bittiği hâlde "caption
 * üretilemedi" denirdi. Paylaşımın kendisi caption'sız bilerek sürüyor — video zaten
 * kaydedilmiş oluyor — ama sebebin logcat'te bir izi kalıyor.
 */
private suspend fun captionOrNull(
    repository: GalleryRepository,
    conversation: Conversation,
    rawUrl: String
): String? = try {
    repository.generateCaption(
        salonId = conversation.salonId,
        clientId = conversation.clientId,
        rawMediaUrl = rawUrl
    )
} catch (e: UnauthorizedException) {
    throw e
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w("GalleryCaption", "Reels caption failed", e)
    null
}

private val FILTER_TOGGLE = 42.dp

/**
 * Filtre rayındaki simgeler arası dikey boşluk.
 *
 * 42dp kutu + 8dp = 50dp adım. Instagram'ın sütunu 68dp adımla diziliyor ama orada her
 * simgenin ALTINDA bir sayı satırı var; sayıyı çıkarınca kalan ritim tam olarak bu.
 */
private val RAIL_GAP = 8.dp

/**
 * Rayın ekranın sağ kenarına uzaklığı.
 *
 * Ölçü kutunun değil SİMGENİN kenara uzaklığından geliyor: Instagram'da simge mürekkebi
 * kenardan 14-15dp içeride duruyor, 42dp kutunun içindeki 24dp simge ise her yanından 9dp
 * boşluk taşıyor. 4 + 9 = 13dp, aynı hiza.
 */
private val RAIL_EDGE = 4.dp

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

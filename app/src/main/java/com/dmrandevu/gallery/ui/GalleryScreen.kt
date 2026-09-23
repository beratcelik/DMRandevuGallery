package com.dmrandevu.gallery.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.dmrandevu.gallery.R
import com.dmrandevu.gallery.ServiceLocator
import com.dmrandevu.gallery.player.PlaybackFailure
import com.dmrandevu.gallery.player.PlayerManager

private class GalleryViewModelFactory(private val igId: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = GalleryViewModel(igId) as T
}

@Composable
fun GalleryScreen(
    igId: String,
    onSessionLost: () -> Unit,
    viewModel: GalleryViewModel = viewModel(factory = GalleryViewModelFactory(igId))
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val hasMore by viewModel.hasMore.collectAsStateWithLifecycle()

    // SAYFA SAYISI ARTIK VİDEO SAYISI: akış düzleşti (her sayfa bir video),
    // çünkü yatay eksen karara ayrıldı (sağa at = ihbar, sola at = ihlal değil).
    //
    // ARTI BİR: son videodan sonra "hepsini gördün" sayfası. Olmadığında akış son
    // videoda duruyordu ve operatör bitip bitmediğini anlamak için boşuna kaydırıyordu.
    val pagerState = rememberPagerState(pageCount = { viewModel.feed.size + 1 })
    viewModel.currentPageProvider = { pagerState.currentPage }
    viewModel.keepCurrentPage = pagerState::requestScrollToPage

    // Gezilen hesabı ViewModel'e bildiriyoruz.
    //
    // NEDEN KURUCUYA VERİLEN [igId] YETMİYOR: `viewModel()` anahtarsız
    // çağrıldığı için örnek etkinliğin deposunda kalıyor. Oturum kaybından
    // sonra BAŞKA bir hesapla girildiğinde aynı örnek geri dönüyor ve kurucusu
    // bir daha çalışmıyor — ihbar düğmesinin görünürlüğü ile eldeki işaretler
    // o hâlde eski hesabın kararı olurdu.
    LaunchedEffect(igId, viewModel) { viewModel.onAccountShown(igId) }

    // Tanıtım kurulum başına bir kez. Karar AÇILIŞTA bir kez okunuyor: her yeniden
    // birleşimde ayarları okumak, "Anladım"a basıldıktan sonra aynı karede bayrağın
    // yazılmasıyla okunması arasında yarış açardı.
    val tag = remember(context) { buildTag(context) }
    var showTour by rememberSaveable(tag) {
        mutableStateOf(ServiceLocator.settings.tourShownBuild != tag)
    }

    val playerManager = remember {
        PlayerManager(
            context = context.applicationContext,
            okHttpClient = ServiceLocator.client,
            onError = { url, failure ->
                if (failure == PlaybackFailure.SESSION_LOST) viewModel.reportSessionLost()
                else viewModel.reportPlaybackFailure(url, failure)
            }
        )
    }
    DisposableEffect(Unit) {
        onDispose { playerManager.release() }
    }

    // Leaving the app settles the pending deletion — otherwise a swipe followed by a home
    // press would silently keep the conversation the user meant to discard.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.commitPendingNow()
                // Bekleyen kaydırma kararları da anında gönderiliyor: kaydırıp
                // ana ekrana basmak kararı sessizce yutmamalı.
                viewModel.commitDecisionsNow()
                playerManager.pauseAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(pagerState, viewModel) {
        snapshotFlow { pagerState.settledPage }.collect { viewModel.onPageSettled(it) }
    }

    // Preview the watermark on the players themselves, so what plays here is what gets exported.
    val watermark by viewModel.watermark.collectAsStateWithLifecycle()
    LaunchedEffect(watermark, playerManager) {
        playerManager.setWatermark(viewModel.watermarkHandle())
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                GalleryEvent.SessionLost -> {
                    ServiceLocator.repository.clearSession()
                    onSessionLost()
                }

                is GalleryEvent.Toast ->
                    Toast.makeText(context, event.messageRes, Toast.LENGTH_SHORT).show()

                // Sunucunun KENDİ cümlesi. Karar verilir verilmez kart uçtuğu
                // için, cevabı gösterecek bir düğme yüzeyi kalmıyor.
                is GalleryEvent.ToastText ->
                    Toast.makeText(context, event.text, Toast.LENGTH_LONG).show()

                is GalleryEvent.ToastFormat -> Toast.makeText(
                    context,
                    context.getString(event.messageRes, *event.args.toTypedArray()),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    Scaffold(containerColor = Color.Black) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            when {
                loading && viewModel.items.isEmpty() ->
                    CircularProgressIndicator(Modifier.align(Alignment.Center))

                viewModel.items.isEmpty() ->
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.empty_gallery),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }

                else -> VerticalPager(
                    state = pagerState,
                    // Anahtar SAYFA kimliği ("konuşma#sıra"): sıra numarası
                    // kullanmak, bir konuşma silindiğinde sayfaların altından
                    // kayması demekti.
                    //
                    // Son sayfanın anahtarı SIRASI: yeni müşteriler yüklenince o sıraya
                    // ilk yeni video oturuyor ve operatör onu görüyor. Sabit bir "son"
                    // anahtarı olsaydı sayfalayıcı onu izleyip yeni videoların hepsinin
                    // üstünden atlardı.
                    key = { index -> viewModel.feed.getOrNull(index)?.id ?: index },
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val feedPage = viewModel.feed.getOrNull(page)
                    val conversation = feedPage?.let { p ->
                        viewModel.items.firstOrNull { it.key == p.conversationKey }
                    }
                    if (page >= viewModel.feed.size) {
                        EndOfFeedPage(
                            isActivePage = pagerState.settledPage == page,
                            stillLoading = loading || hasMore,
                            playerManager = playerManager,
                            onNeedMore = { viewModel.loadMore() },
                            modifier = Modifier.padding(padding)
                        )
                    } else if (feedPage != null && conversation != null) {
                        VideoPage(
                            conversation = conversation,
                            page = feedPage,
                            isActivePage = pagerState.settledPage == page,
                            isNextPage = pagerState.settledPage + 1 == page,
                            playerManager = playerManager,
                            viewModel = viewModel,
                            // Karar verildikten sonra bir sonraki videoya:
                            // kart uçuyor ve akış ilerliyor. Son videodan sonra
                            // "hepsini gördün" sayfasına.
                            onAdvance = {
                                val next = page + 1
                                if (next < pagerState.pageCount) {
                                    scope.launch { pagerState.animateScrollToPage(next) }
                                }
                            },
                            modifier = Modifier.padding(padding)
                        )
                    }
                }
            }

            // ─── AKIŞ TANITIMI ──────────────────────────────────────────────
            //
            // BURADA, sayfanın içinde değil: VerticalPager'ın içeriği her sayfa için
            // yeniden kuruluyor ve orada duran bir tanıtım her videoda bir kez
            // çizilirdi. Kutunun SON çocuğu olduğu için akışın tamamının üstünde.
            //
            // AKIŞ GELDİKTEN SONRA: boş bir ekranın üstünde hareketleri anlatmak,
            // anlatılan şeyin arkasında hiçbir şey yokken anlatmak olurdu. Bir de
            // ihbar hesabı olup olmadığı ancak hesap yüklendikten sonra belli.
            if (showTour && viewModel.items.isNotEmpty()) {
                OrientationOverlay(
                    ihbarEnabled = viewModel.ihbarEnabled,
                    onDismiss = {
                        ServiceLocator.settings.tourShownBuild = tag
                        showTour = false
                    }
                )
            }
        }
    }
}

/**
 * The page after the last video.
 *
 * While the server may still have more customers it only shows a spinner, and keeps asking for
 * them. Saying "all seen" and then having new videos appear under it would be wrong. When new
 * videos arrive, this index becomes the first of them and this page is gone. When nothing more
 * is coming, it says so.
 *
 * It keeps asking rather than asking once because a batch can arrive with nothing new in it
 * (customers the app already holds) while the server still says there is more. A single request
 * would then leave the spinner up for good. [GalleryViewModel.loadMore] ignores calls while one
 * is in flight, so repeating it is harmless.
 *
 * Pauses every player on arrival. No video page is active here, so none of them would pause
 * itself, and the last video would keep playing behind this page.
 */
@Composable
private fun EndOfFeedPage(
    isActivePage: Boolean,
    stillLoading: Boolean,
    playerManager: PlayerManager,
    onNeedMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(isActivePage) {
        if (isActivePage) playerManager.pauseAll()
    }
    LaunchedEffect(isActivePage, stillLoading) {
        while (isActivePage && stillLoading) {
            onNeedMore()
            delay(MORE_RETRY_MS)
        }
    }
    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (stillLoading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.all_seen),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.end_of_feed),
                    color = Color.White.copy(alpha = 0.6f),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/** How often the end page asks again while the server still says there is more. */
private const val MORE_RETRY_MS = 1_500L

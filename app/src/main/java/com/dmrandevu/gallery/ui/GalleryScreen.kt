package com.dmrandevu.gallery.ui

import android.widget.Toast
import androidx.compose.foundation.background
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

    // SAYFA SAYISI ARTIK VİDEO SAYISI: akış düzleşti (her sayfa bir video),
    // çünkü yatay eksen karara ayrıldı (sağa at = ihbar, sola at = ihlal değil).
    val pagerState = rememberPagerState(pageCount = { viewModel.feed.size })
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
                    key = { index -> viewModel.feed.getOrNull(index)?.id ?: index },
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val feedPage = viewModel.feed.getOrNull(page)
                    val conversation = feedPage?.let { p ->
                        viewModel.items.firstOrNull { it.key == p.conversationKey }
                    }
                    if (feedPage != null && conversation != null) {
                        VideoPage(
                            conversation = conversation,
                            page = feedPage,
                            isActivePage = pagerState.settledPage == page,
                            isNextPage = pagerState.settledPage + 1 == page,
                            playerManager = playerManager,
                            viewModel = viewModel,
                            // Karar verildikten sonra bir sonraki videoya:
                            // kart uçuyor ve akış ilerliyor. Son sayfadaysak
                            // hiçbir yere gitmiyoruz — kaydıracak yer yok ve
                            // zorlamak, kararı vermiş sayfayı titretirdi.
                            onAdvance = {
                                val next = page + 1
                                if (next < viewModel.feed.size) {
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

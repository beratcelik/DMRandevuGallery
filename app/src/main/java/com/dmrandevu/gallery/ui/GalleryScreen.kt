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
import androidx.compose.runtime.remember
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
import com.dmrandevu.gallery.R
import com.dmrandevu.gallery.ServiceLocator
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
    val loading by viewModel.loading.collectAsStateWithLifecycle()

    val pagerState = rememberPagerState(pageCount = { viewModel.items.size })
    viewModel.currentPageProvider = { pagerState.currentPage }
    viewModel.keepCurrentPage = pagerState::requestScrollToPage

    val playerManager = remember {
        PlayerManager(
            context = context.applicationContext,
            okHttpClient = ServiceLocator.client,
            onError = { url, unauthorized ->
                if (unauthorized) viewModel.reportSessionLost() else viewModel.markExpired(url)
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
                playerManager.pauseAll()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(pagerState, viewModel) {
        snapshotFlow { pagerState.settledPage }.collect { viewModel.onPageSettled(it) }
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
                    key = { index -> viewModel.items.getOrNull(index)?.key ?: index },
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    viewModel.items.getOrNull(page)?.let { conversation ->
                        ConversationPage(
                            conversation = conversation,
                            page = page,
                            isActivePage = pagerState.settledPage == page,
                            isNextPage = pagerState.settledPage + 1 == page,
                            playerManager = playerManager,
                            viewModel = viewModel,
                            modifier = Modifier.padding(padding)
                        )
                    }
                }
            }
        }
    }
}

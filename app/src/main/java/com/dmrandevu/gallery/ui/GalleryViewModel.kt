package com.dmrandevu.gallery.ui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dmrandevu.gallery.ServiceLocator
import com.dmrandevu.gallery.data.Conversation
import com.dmrandevu.gallery.data.UnauthorizedException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** One-shot instructions for the composable layer. */
sealed interface GalleryEvent {
    data object SessionLost : GalleryEvent
    data class Toast(val messageRes: Int) : GalleryEvent
}

private data class PendingDelete(val conversation: Conversation, val job: Job)

class GalleryViewModel(private val igId: String) : ViewModel() {

    private val repo = ServiceLocator.repository

    val items = mutableStateListOf<Conversation>()

    /** Proxy urls that failed to play — the CDN link behind them has expired. */
    val expiredUrls = mutableStateMapOf<String, Boolean>()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore

    /** How many video-carrying conversations are left for this account. */
    private val _remaining = MutableStateFlow(0)
    val remaining: StateFlow<Int> = _remaining

    private val _events = Channel<GalleryEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var pending: PendingDelete? = null
    private var lastSettledKey: String? = null
    private var nextOffset = 0
    private var committedDeletes = 0
    private var loadingMore = false

    /** Supplied by the UI so a commit can keep the pager pointed at the same conversation. */
    var currentPageProvider: () -> Int = { 0 }

    /**
     * Set by the UI to `PagerState::requestScrollToPage`. It has to be called synchronously,
     * in the same frame as the removal — routing it through an event would leave one frame
     * where the pager still points at the old index, flashing the conversation below.
     */
    var keepCurrentPage: (Int) -> Unit = {}

    init {
        loadMore(initial = true)
    }

    // ── paging ────────────────────────────────────────────────────────────────────

    fun loadMore(initial: Boolean = false) {
        if (loadingMore || (!initial && !_hasMore.value)) return
        loadingMore = true
        viewModelScope.launch {
            try {
                var warmupAttempt = 0
                while (true) {
                    // Deleting shrinks the server-side index, so every committed delete shifts
                    // the window down by one; without this correction we would skip conversations.
                    val offset = (nextOffset - committedDeletes).coerceAtLeast(0)
                    val page = repo.loadPage(igId, offset, PAGE_SIZE)
                    val known = items.mapTo(HashSet()) { it.key }
                    val fresh = page.items.filter { it.key !in known && it.urls.isNotEmpty() }
                    items.addAll(fresh)
                    _hasMore.value = page.hasMore
                    // The server count already reflects everything committed so far.
                    _remaining.value = page.total

                    // A cold or stale video index answers the first request empty while the
                    // server rebuilds it in the background. Without this retry the app shows
                    // "no conversations" for a salon that has them, until it is relaunched.
                    if (initial && items.isEmpty() && warmupAttempt < INDEX_WARMUP_RETRIES) {
                        warmupAttempt++
                        delay(INDEX_WARMUP_DELAY_MS)
                        continue // re-ask from the top; nextOffset stays 0 for a still-empty feed
                    }

                    nextOffset = page.nextOffset + committedDeletes
                    if (lastSettledKey == null) lastSettledKey = items.firstOrNull()?.key
                    break
                }
            } catch (e: UnauthorizedException) {
                _events.send(GalleryEvent.SessionLost)
            } catch (e: Exception) {
                _hasMore.value = false
            } finally {
                loadingMore = false
                _loading.value = false
            }
        }
    }

    // ── swipe-to-delete ───────────────────────────────────────────────────────────

    /**
     * Called whenever the vertical pager settles. Deleting is driven purely by which
     * conversation the user *left*, compared by stable key — indices shift when items are
     * removed, so comparing them would delete the wrong customer.
     */
    fun onPageSettled(page: Int) {
        val newKey = items.getOrNull(page)?.key ?: return
        val previousKey = lastSettledKey
        lastSettledKey = newKey

        // Re-entry from our own scrollToPage after a removal: same conversation, nothing left.
        if (newKey == previousKey) {
            maybeLoadMore(page)
            return
        }

        // Swiping back onto the conversation queued for deletion is an implicit undo.
        if (pending?.conversation?.key == newKey) {
            cancelPending()
            maybeLoadMore(page)
            return
        }

        if (previousKey != null) {
            val previousIndex = items.indexOfFirst { it.key == previousKey }
            val newIndex = items.indexOfFirst { it.key == newKey }
            // Forward swipe only — going back must never delete.
            if (previousIndex != -1 && newIndex > previousIndex) {
                queueDelete(items[previousIndex])
            }
        }
        maybeLoadMore(page)
    }

    private fun maybeLoadMore(page: Int) {
        if (page >= items.size - PREFETCH_DISTANCE) loadMore()
    }

    /**
     * Holds the deletion for [UNDO_WINDOW_MS] so swiping back cancels it. Deliberately silent:
     * the swipe itself is the feedback, and a banner would only cover the action buttons.
     */
    private fun queueDelete(conversation: Conversation) {
        // Only one deletion can be undone at a time; a new one settles the previous immediately.
        commitPendingNow()
        val job = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            commit(conversation)
        }
        pending = PendingDelete(conversation, job)
    }

    private fun cancelPending() {
        pending?.job?.cancel()
        pending = null
    }

    /** Fires the queued deletion right away — used when leaving the screen or queueing another. */
    fun commitPendingNow() {
        val current = pending ?: return
        current.job.cancel()
        pending = null
        viewModelScope.launch { commit(current.conversation) }
    }

    private suspend fun commit(conversation: Conversation) {
        try {
            repo.deleteConversation(conversation.salonId, conversation.clientId)
        } catch (e: UnauthorizedException) {
            _events.send(GalleryEvent.SessionLost)
            return
        } catch (e: Exception) {
            // The conversation stays in the feed; the next swipe past it can try again.
            pending = null
            return
        }

        val index = items.indexOfFirst { it.key == conversation.key }
        if (index != -1) {
            val currentPage = currentPageProvider()
            items.removeAt(index)
            committedDeletes++
            _remaining.value = (_remaining.value - 1).coerceAtLeast(0)
            // Removing an item above the viewport pulls everything up by one, so the pager has
            // to step back in the very same frame to stay on the conversation being watched.
            if (index < currentPage) keepCurrentPage(currentPage - 1)
        }
        if (pending?.conversation?.key == conversation.key) pending = null
        if (items.size <= PREFETCH_DISTANCE) loadMore()
    }

    fun markExpired(proxyUrl: String) {
        expiredUrls[proxyUrl] = true
    }

    fun reportSessionLost() {
        viewModelScope.launch { _events.send(GalleryEvent.SessionLost) }
    }

    override fun onCleared() {
        // viewModelScope is already cancelled here, so a pending delete must have been
        // committed by the ON_STOP hook in the UI before this point.
        super.onCleared()
    }

    companion object {
        const val UNDO_WINDOW_MS = 5_000L
        const val PAGE_SIZE = 5
        const val PREFETCH_DISTANCE = 3
        /** Covers the server-side index rebuild, which the first request only triggers. */
        const val INDEX_WARMUP_RETRIES = 3
        const val INDEX_WARMUP_DELAY_MS = 1_200L
    }
}

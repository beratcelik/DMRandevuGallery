package com.dmrandevu.gallery.ui

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dmrandevu.gallery.ServiceLocator
import com.dmrandevu.gallery.data.Conversation
import com.dmrandevu.gallery.data.IhbarAccount
import com.dmrandevu.gallery.data.IhbarException
import com.dmrandevu.gallery.data.IhbarMark
import com.dmrandevu.gallery.data.IhbarPhase
import com.dmrandevu.gallery.data.IhbarRepository
import com.dmrandevu.gallery.data.IhbarTokenMissingException
import com.dmrandevu.gallery.data.UnauthorizedException
import com.dmrandevu.gallery.R
import com.dmrandevu.gallery.data.ihbarItemFor
import com.dmrandevu.gallery.data.toMark
import com.dmrandevu.gallery.media.ExportOptions
import com.dmrandevu.gallery.media.censor.CensorWindow
import com.dmrandevu.gallery.player.PlaybackFailure
import kotlinx.coroutines.CancellationException
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

    /**
     * Sunucunun KENDİ Türkçe cümlesi.
     *
     * NEDEN GEREKLİ: karar artık kaydırmayla veriliyor ve kart karar verilir
     * verilmez uçuyor — yani sunucunun cevabını gösterecek bir düğme yüzeyi
     * yok. "Bu video kayıttan çıkarıldı; ihbar kalan 2 videoyla devam ediyor"
     * gibi cümlelerin görünür tek kanalı bu.
     */
    data class ToastText(val text: String) : GalleryEvent

    /**
     * Biçimlendirilmiş dizgi kaynağı ("4 video elendi, 1 atlandı").
     *
     * NEDEN args bir LİSTE, vararg DEĞİL: vararg bir dizi üretiyor ve dizi
     * eşitliği referans eşitliği demek — aynı içerikli iki olay birbirine eşit
     * olmadığı için data class'ın equals'ı yalan söylerdi.
     */
    data class ToastFormat(val messageRes: Int, val args: List<Any>) : GalleryEvent
}

private data class PendingDelete(val conversation: Conversation, val job: Job)

/** Sunucuya henüz sorulmamış düğme: nötr ve basılabilir. */
private val UNKNOWN_MARK = IhbarMark(IhbarPhase.UNKNOWN)

/** Belirteç girilmemiş: soluk, ve dokununca ne yapılması gerektiğini anlatıyor. */
private val NO_TOKEN_MARK = IhbarMark(IhbarPhase.NO_TOKEN)

class GalleryViewModel(private val igId: String) : ViewModel() {

    private val repo = ServiceLocator.repository
    private val ihbar = ServiceLocator.ihbarRepository
    private val settings = ServiceLocator.settings
    private val marks = ServiceLocator.manualMarks

    val items = mutableStateListOf<Conversation>()

    /**
     * Akışın DÜZ sayfa listesi: her sayfa bir video.
     *
     * NEDEN TÜRETİLMİŞ (ikinci bir liste tutulmuyor): iki liste er geç ayrışır
     * ve ayrıştıkları an sayfa sırası ile konuşma sırası birbirini tutmaz —
     * yani yanlış müşteri silinir. Tek gerçek kaynak [items].
     */
    val feed: List<FeedPage> by derivedStateOf { buildFeed(items) }

    /**
     * Geri alma penceresinde bekleyen kaydırma kararları.
     *
     * Gerekçesi [DecisionLedger] başlığında: kart karar verilir verilmez uçuyor
     * ve üç saniyelik pencere, parmağın kaydığı hâlleri ağa hiç çıkmadan
     * yakalıyor.
     */
    var pendingDecisions by mutableStateOf(DecisionLedger())
        private set

    /** Bekleyen kararların zamanlayıcıları; anahtar [FeedPage.id]. */
    private val decisionJobs = mutableMapOf<String, Job>()

    /** Proxy urls that failed to play, and what kind of failure each one hit. */
    val failures = mutableStateMapOf<String, PlaybackFailure>()

    /**
     * Video başına ihlal düğmesinin durumu; anahtarı konuşma + video sırası.
     *
     * NEDEN SAYFADA DEĞİL DE BURADA: dikey çağrıcı yalnızca komşu sayfaları
     * derli tutuyor, iki konuşma aşağı kaydırıp geri dönmek sayfayı sıfırdan
     * oluşturuyor. Durum sayfada dursaydı yeşile dönmüş bir düğme her dönüşte
     * nötre döner ve sahip aynı videoyu ikinci kez işaretlerdi.
     *
     * NEDEN DİSKE YAZILMIYOR: tek doğru kaynak sunucu. Saklanan bir "yeşil",
     * yönetici konsolunda geri çekilen bir ihbarda yalan söylemeye devam
     * ederdi; uygulama yeniden açıldığında sayfa zaten tek istekte boyanıyor.
     *
     * Diğer durum alanlarıyla birlikte yukarıda duruyor, kullanıldığı bölümde
     * değil: ilk sayfa init bloğundan yükleniyor ve bu harita o akış
     * başlamadan önce var olmak zorunda.
     */
    private val ihbarMarks = mutableStateMapOf<String, IhbarMark>()

    /**
     * İhbar köprüsü bu hesapta açık mı — düğmenin çizilmesi de, ağa çıkılması
     * da yalnızca buna bakıyor.
     *
     * NEDEN YALNIZCA ÇİZİM GİZLENMİYOR: gizli bir düğme yine de sayfa başına
     * bir toplu durum isteği attırırdı. trafykamerasi'nin videolarının ihbar
     * sisteminde karşılığı HİÇ yok; o istekler her kaydırmada hiçbir şey
     * öğrenmeden sunucunun oran sınırını doldurur ve mobil bağlantıda boşuna
     * gecikme yaratırdı.
     *
     * NEDEN [ihbarMarks] İLE AYNI YERDE: ilk sayfa init bloğundan yükleniyor
     * ve o akış boyamaya kalkmadan önce bu değerin okunabilir olması gerek.
     */
    var ihbarEnabled by mutableStateOf(isIhbarAccount(igId))
        private set

    /**
     * Elde tutulan işaretlerin ait olduğu hesap; [onAccountShown] değişimi
     * bununla karşılaştırarak anlıyor.
     */
    private var ihbarAccountId: String = igId

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore

    /** How many video-carrying conversations are left for this account. */
    private val _remaining = MutableStateFlow(0)
    val remaining: StateFlow<Int> = _remaining

    /**
     * Kept here rather than read straight off [SettingsStore] because the vertical pager keeps
     * neighbouring pages composed — each one has to see the same toggle state.
     */
    private val _blurFaces = MutableStateFlow(settings.blurFaces)
    val blurFaces: StateFlow<Boolean> = _blurFaces

    private val _blurPlates = MutableStateFlow(settings.blurPlates)
    val blurPlates: StateFlow<Boolean> = _blurPlates

    private val _fastPlates = MutableStateFlow(settings.fastPlates)
    val fastPlates: StateFlow<Boolean> = _fastPlates

    private val _watermark = MutableStateFlow(settings.watermark)
    val watermark: StateFlow<Boolean> = _watermark

    private val _censorAudio = MutableStateFlow(settings.censorAudio)
    val censorAudio: StateFlow<Boolean> = _censorAudio

    private val _censorByHand = MutableStateFlow(settings.censorByHand)
    val censorByHand: StateFlow<Boolean> = _censorByHand

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
                    val endPage = feed.size
                    items.addAll(fresh)
                    // Sitting on the end page when the rescan finds more: that page is now the
                    // first new video. The pager's index did not move, so nothing settles, and
                    // leaving this customer later has to know it was here.
                    if (lastSettledKey == END_KEY && fresh.isNotEmpty() &&
                        currentPageProvider() == endPage
                    ) {
                        lastSettledKey = fresh.first().key
                    }
                    // Sayfa geldiği anda, sahip oraya kaydırmadan önce boyanıyor:
                    // düğmenin rengi videoyla birlikte hazır olmalı.
                    refreshIhbar(fresh)
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
        if (page >= feed.size && feed.isNotEmpty()) {
            onEndReached()
            return
        }
        val current = feed.getOrNull(page) ?: return

        // KARAR NE YAPILACAĞI SAF BİR KURALDA (ui/FeedPages.kt): silme, sistemin
        // geri alınamaz tek yan etkisi ve düz akışta "sayfa değişti" artık
        // "müşteri değişti" demek DEĞİL. Kuralı ViewModel'de bırakmak, hiçbir
        // testin dokunamadığı bir yerde tutmak olurdu.
        val action = onSettled(
            previousConversationKey = lastSettledKey,
            next = current,
            conversationKeys = items.map { it.key },
            pendingDeleteKey = pending?.conversation?.key,
        )
        lastSettledKey = current.conversationKey

        when (action) {
            SettleAction.None -> Unit
            SettleAction.CancelPendingDelete -> cancelPending()
            is SettleAction.QueueDelete -> {
                items.getOrNull(action.conversationIndex)?.let { queueDelete(it) }
            }
        }

        // BU SAYFAYA GERİ DÖNMEK, KARARI DA GERİ ALIYOR. İkinci geri alma yolu
        // (çipe dokunmak) ekranın altında duruyor; bu ise sahibin zaten yaptığı
        // hareketin karşılığı: "bir bakayım" diye geri kaydırmak.
        if (pendingDecisions[current.id] != null) undoDecision(current.id)

        maybeLoadMore(page)
    }

    /**
     * The operator has gone past the last video.
     *
     * LEAVING THE LAST CUSTOMER DELETES IT, like leaving any other. It used to be the one customer
     * nothing came after, so it was never passed and never deleted: the oldest conversation sat
     * at the end of the feed for days.
     *
     * The settled key becomes [END_KEY], never a conversation's key. Clearing it instead would let
     * the next load set it to the first conversation, and moving on from here to a video the
     * rescan found would then read as leaving that first conversation and delete it.
     *
     * Then the feed is read again from the top. Conversations that got a new message during the
     * session move to the top of the server's index, above the point this feed has already paged
     * past, so they were never fetched: the counter said four were left while the feed had ended.
     * Anything already in the feed is skipped, so only those come back, at the end.
     */
    private fun onEndReached() {
        if (lastSettledKey == END_KEY) return
        val last = items.lastOrNull()
        if (last != null && lastSettledKey == last.key) queueDelete(last)
        lastSettledKey = END_KEY
        // Set now rather than when the rescan starts, so the end page shows the spinner from the
        // first frame instead of flashing "all seen" first.
        _hasMore.value = true
        rescanFromTop()
    }

    private fun rescanFromTop() {
        viewModelScope.launch {
            // A load still in flight would write its own offset after this reset.
            while (loadingMore) delay(RESCAN_WAIT_MS)
            nextOffset = 0
            committedDeletes = 0
            _hasMore.value = true
            loadMore()
        }
    }

    /**
     * Sayfa DEĞİL konuşma sırasına bakıyor.
     *
     * Düz akışta sayfa sayısı konuşma sayısından çok daha büyük: tek bir
     * müşterinin yirmi videosu varken sayfa sırasını konuşma sayısıyla
     * karşılaştırmak, ilk müşteride bile "sona yaklaşıldı" der ve sunucudan
     * durmadan yeni sayfa isterdi.
     */
    private fun maybeLoadMore(page: Int) {
        val key = feed.getOrNull(page)?.conversationKey ?: return
        val index = items.indexOfFirst { it.key == key }
        if (index >= 0 && index >= items.size - PREFETCH_DISTANCE) loadMore()
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
            // SİLİNEN KONUŞMANIN KAPLADIĞI SAYFA SAYISI, silmeden ÖNCE okunuyor.
            // Düz akışta bir konuşma birden çok sayfa kaplıyor; tek sayfa geri
            // adım atmak, izlenen videonun başka bir müşterinin videosuna
            // kaymasıyla sonuçlanırdı.
            val span = pageSpan(items, conversation.key)
            val firstPage = firstPageOf(feed, conversation.key)
            items.removeAt(index)
            committedDeletes++
            _remaining.value = (_remaining.value - 1).coerceAtLeast(0)
            // Görüş alanının ÜSTÜNDEKİ sayfalar kalkınca her şey yukarı kayıyor;
            // çağrıcı aynı karede geri adım atmazsa izlenen video değişir.
            if (firstPage in 0 until currentPage) {
                keepCurrentPage((currentPage - span).coerceAtLeast(0))
            }
        }
        if (pending?.conversation?.key == conversation.key) pending = null
        if (items.size <= PREFETCH_DISTANCE) loadMore()
    }

    // ── kaydırma kararları (geri alma penceresi) ──────────────────────────────

    /**
     * Sağa/sola atışın kararı: üç saniye bekletilip sonra ağa çıkıyor.
     *
     * ─── NEDEN BEKLETİLİYOR ────────────────────────────────────────────────
     * Sağa atış bir ihbarı emniyet birimine gönderiyor ve bu tam olarak geri
     * alınamıyor: geri çekme, memura "bu ihbarı dikkate almayın" bildirimi
     * gönderiyor. Kaydırma kararı hızlandırdığı kadar yanlış kararı da
     * hızlandırıyor; üç saniyelik pencere parmağın kaydığı hâlleri ağa hiç
     * çıkmadan yakalıyor.
     *
     * ─── NEDEN KONUŞMANIN ANLIK GÖRÜNTÜSÜ SAKLANIYOR ───────────────────────
     * Karar beklerken sahip bir sonraki müşteriye geçebiliyor ve o hareket
     * geride bıraktığı konuşmayı silme sırasına alıyor (beş saniye). Karar
     * uygulanırken anahtarla aransaydı — liste artık o konuşmayı taşımıyor —
     * hiçbir şey bulunamaz ve karar sessizce kaybolurdu.
     *
     * ÜÇ SANİYE < BEŞ SANİYE bilerek: karar, konuşmanın silinmesinden önce
     * yola çıkıyor.
     */
    fun decide(conversation: Conversation, mediaIndex: Int, decision: SwipeOutcome) {
        // Kapı burada da duruyor: çizimi gizlemek bir görünüm kararı, bu istek
        // ise emniyete giden bir kayıt — çağıranın dikkatine bırakılamaz.
        if (!ihbarEnabled) return

        val page = FeedPage(conversation.key, mediaIndex)
        // Aynı sayfaya ikinci karar: öncekinin zamanlayıcısı iptal, yerine yenisi.
        // Sahip fikrini değiştirdiyse ağa yalnızca son kararı çıkmalı.
        decisionJobs.remove(page.id)?.cancel()
        pendingDecisions = pendingDecisions.put(QueuedDecision(page, conversation, decision))
        decisionJobs[page.id] = viewModelScope.launch {
            delay(DECISION_WINDOW_MS)
            fireDecision(page.id)
        }
    }

    /** Geri alma: çipe dokunmak ya da o sayfaya geri kaydırmak. */
    fun undoDecision(pageId: String) {
        val job = decisionJobs.remove(pageId) ?: return
        job.cancel()
        pendingDecisions = pendingDecisions.remove(pageId)
        viewModelScope.launch { _events.send(GalleryEvent.Toast(R.string.swipe_undone)) }
    }

    /**
     * Bekleyen kararları ANINDA gönderir — uygulamadan çıkılırken.
     *
     * Silme kuyruğundaki [commitPendingNow] ile aynı gerekçe: kaydırıp ana
     * ekrana basmak, kararı sessizce yutmamalı.
     */
    fun commitDecisionsNow() {
        val waiting = pendingDecisions.all()
        decisionJobs.values.forEach { it.cancel() }
        decisionJobs.clear()
        pendingDecisions = pendingDecisions.clear()
        waiting.forEach(::apply)
    }

    private fun fireDecision(pageId: String) {
        val queued = pendingDecisions[pageId] ?: return
        decisionJobs.remove(pageId)
        pendingDecisions = pendingDecisions.remove(pageId)
        apply(queued)
    }

    private fun apply(queued: QueuedDecision) {
        when (queued.decision) {
            SwipeOutcome.REPORT -> markViolation(queued.conversation, queued.page.mediaIndex)
            SwipeOutcome.DISMISS -> markNotViolation(queued.conversation, queued.page.mediaIndex)
        }
    }

    /**
     * Bu konuşmada HENÜZ KARAR VERİLMEMİŞ videoların sıraları.
     *
     * Toplu eleme yalnızca bunlara dokunuyor: onaylanmış, elenmiş ya da isteği
     * yolda olan bir videoyu toplu bir hareketle yeniden karara bağlamak,
     * sahibin tek tek verdiği kararları toplu bir dokunuşla ezmek olurdu.
     */
    fun undecidedIndices(conversation: Conversation): List<Int> =
        conversation.urls.indices.filter { index ->
            // Bekleyen bir karar da "karar verilmiş" sayılıyor: üç saniye sonra
            // dolacak ve toplu elemenin üstüne yazacaktı.
            if (pendingDecisions[FeedPage(conversation.key, index).id] != null) return@filter false
            when (ihbarMark(conversation.key, index).phase) {
                IhbarPhase.NOT_VIOLATION, IhbarPhase.APPROVED, IhbarPhase.VERIFIED,
                IhbarPhase.VERIFIED_PENDING, IhbarPhase.BUSY, IhbarPhase.REJECTING,
                IhbarPhase.NO_TOKEN -> false
                else -> true
            }
        }

    /**
     * Konuşmanın karar verilmemiş videolarını TEK istekte eler.
     *
     * NEDEN TEK İSTEK: iki yazma ucu tek bir oran sınırı kovasını paylaşıyor
     * (dakikada yirmi). On beş videoyu tek tek elemek o bütçenin dörtte üçünü
     * yakıyor ve aynı dakikadaki GERÇEK ihbarı da engelliyor.
     *
     * ÖNCE BEKLEYEN KARARLAR İPTAL EDİLİYOR: aksi hâlde üç saniye sonra dolan
     * bir olumlu karar toplu elemenin üstüne yazardı (sunucuda son dokunuş
     * kazanıyor) ve bir de boşuna çıkarım ısmarlardı.
     */
    fun dismissAll(conversation: Conversation) {
        if (!ihbarEnabled) return
        pendingDecisions.of(conversation.key).forEach { undoDecision(it.page.id) }

        val indices = undecidedIndices(conversation)
        if (indices.isEmpty()) return
        if (!ihbar.hasToken) {
            indices.forEach { index ->
                ihbarMarks[ihbarKey(conversation.key, index)] = NO_TOKEN_MARK
            }
            return
        }

        indices.forEach { index ->
            ihbarMarks[ihbarKey(conversation.key, index)] = IhbarMark(IhbarPhase.REJECTING)
        }

        viewModelScope.launch {
            val answers = try {
                ihbar.rejectBulk(indices.map { ihbarItemFor(conversation, it) })
            } catch (e: CancellationException) {
                throw e
            } catch (e: IhbarTokenMissingException) {
                indices.forEach { ihbarMarks[ihbarKey(conversation.key, it)] = NO_TOKEN_MARK }
                return@launch
            } catch (e: IhbarException) {
                indices.forEach {
                    ihbarMarks[ihbarKey(conversation.key, it)] =
                        IhbarMark(IhbarPhase.REJECT_ERROR, e.message)
                }
                _events.send(GalleryEvent.ToastText(e.message ?: ""))
                return@launch
            } catch (e: Exception) {
                // YARIM UYGULANMIŞ OLABİLİR: sunucu bazı öğeleri işleyip
                // düşmüş olabilir ve elimizde hangilerinin işlendiği bilgisi
                // yok. İşaretleri uydurmak yerine SUNUCUYA YENİDEN SORUYORUZ —
                // ekranın gerçeği göstermesinin tek yolu bu.
                indices.forEach { ihbarMarks.remove(ihbarKey(conversation.key, it)) }
                refreshIhbar(listOf(conversation))
                _events.send(GalleryEvent.Toast(R.string.bulk_dismiss_failed))
                return@launch
            }

            // Yanıt İSTEK SIRASIYLA dönüyor. Uzunluk tutmuyorsa HİÇBİR işaret
            // boyanmıyor ve durum yeniden soruluyor: kayan bir liste yanlış
            // videoyu elenmiş gösterir ve bu, fark edilmesi en zor hata türü.
            if (answers.items.size != indices.size) {
                indices.forEach { ihbarMarks.remove(ihbarKey(conversation.key, it)) }
                refreshIhbar(listOf(conversation))
                return@launch
            }
            indices.forEachIndexed { position, index ->
                ihbarMarks[ihbarKey(conversation.key, index)] = answers.items[position].toMark()
            }
            _events.send(
                GalleryEvent.ToastFormat(
                    R.string.bulk_dismiss_result,
                    listOf(answers.applied, answers.skipped),
                )
            )
        }
    }

    // ── ihbar köprüsü ─────────────────────────────────────────────────────────────

    /**
     * Ekranda o an hangi hesabın gezildiğini bildirir.
     *
     * NEDEN KURUCUDAKİ [igId] TEK BAŞINA YETMİYOR: bu ViewModel etkinliğin
     * deposunda yaşıyor ve `viewModel()` anahtarsız çağrıldığı için, oturum
     * kaybından sonra BAŞKA bir hesapla girildiğinde aynı örnek geri dönüyor.
     * Kurucudaki kimlik o anda bir önceki hesabı gösterir; kapı yalnızca ona
     * dayansaydı düğme trafykamerasi'nde de çizilmeye devam ederdi.
     *
     * Hesap değiştiğinde eldeki işaretler atılıyor: haritanın anahtarı
     * konuşma + video sırası, hesap değil. Eski hesaptan kalan bir "yeşil",
     * yeni hesapta aynı anahtara denk gelen bambaşka bir videonun üstünde
     * görünürdü — fark edilmesi en zor hata türü.
     */
    fun onAccountShown(accountId: String) {
        if (accountId != ihbarAccountId) {
            ihbarAccountId = accountId
            ihbarMarks.clear()
        }
        ihbarEnabled = isIhbarAccount(accountId)
    }

    /**
     * Gezilen hesap, ihbar sisteminin tanıdığı tek hesap mı?
     *
     * İKİ KAYNAK BİRDEN soruluyor: sayfaların gerçekten çekildiği sayısal
     * kimlik ve ayarda saklanan hesap adı ([SettingsStore.igUsername]).
     * İkisinin ayrışabildiği tek durumda (yukarıdaki ViewModel yeniden
     * kullanımı) yanlış hesapta düğme göstermek, doğru hesapta bir kez
     * göstermemekten çok daha pahalı: oradaki tek dokunuş, ihbar sisteminde
     * karşılığı olmayan bir kaydı onaylatmaya çalışır.
     *
     * Ayardaki ad kurucuda bir kez değil, HER çağrıda okunuyor — giriş ekranı
     * onu değiştirdiğinde bu örnek hâlâ yaşıyor olabilir.
     */
    private fun isIhbarAccount(accountId: String): Boolean =
        IhbarAccount.matches(accountId) && IhbarAccount.matches(settings.igUsername)

    /** Bir videonun düğmesinin bildiği her şey; hiç sorulmamışsa varsayılan. */
    fun ihbarMark(conversationKey: String, mediaIndex: Int): IhbarMark =
        ihbarMarks[ihbarKey(conversationKey, mediaIndex)]
            ?: if (ihbar.hasToken) UNKNOWN_MARK else NO_TOKEN_MARK

    /**
     * Bir sayfa dolusu videonun durumunu TEK istekte alır ve düğmeleri boyar.
     *
     * NEDEN VİDEO BAŞINA DEĞİL: sayfa başına beş konuşma ve her birinde birkaç
     * video var. Video başına istek, her kaydırmada onlarca istek demek olurdu;
     * mobil bağlantıda gözle görülür gecikme ve sunucudaki oran sınırının hiçbir
     * iş yapmadan dolması.
     */
    private fun refreshIhbar(conversations: List<Conversation>) {
        // Yanlış hesapta tek bir istek bile atılmıyor; gerekçesi
        // [ihbarEnabled] üzerinde.
        if (!ihbarEnabled || !ihbar.hasToken) return
        val targets = conversations.flatMap { conversation ->
            conversation.urls.indices.map { index ->
                ihbarKey(conversation.key, index) to ihbarItemFor(conversation, index)
            }
        }
        if (targets.isEmpty()) return

        viewModelScope.launch {
            for (chunk in targets.chunked(IhbarRepository.MAX_ITEMS)) {
                val answers = try {
                    ihbar.status(chunk.map { it.second })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IhbarException) {
                    // Sunucu isteği anladı ve reddetti; pratikte bu "belirteç
                    // geçersiz" demek. Yutmak, sahibin bozuk bir belirteçle
                    // düğmeye basıp durmasına yol açardı — sebebi düğmede yazsın.
                    chunk.forEach { (key, _) ->
                        ihbarMarks[key] = IhbarMark(IhbarPhase.ERROR, e.message)
                    }
                    return@launch
                } catch (e: Exception) {
                    // Ağ yok ya da sunucu kapalı: düğmeler "bilinmiyor" kalıyor ve
                    // basılabilir olmayı sürdürüyor. İzlenen videonun üstüne hata
                    // koymak, kullanıcının yapabileceği bir şey olmadığı hâlde
                    // arıza duygusu yaratırdı.
                    return@launch
                }
                // Yanıt istek sırasıyla dönüyor. Uzunluk tutmuyorsa HİÇBİR düğme
                // boyanmıyor: kayan bir liste yanlış videoyu yeşile çevirir ve bu,
                // fark edilmesi en zor hata türü.
                if (answers.size != chunk.size) return@launch
                chunk.forEachIndexed { index, (key, _) ->
                    ihbarMarks[key] = answers[index].toMark()
                }
            }
        }
    }

    /**
     * Sahibin dokunuşu: "bu görüntü gerçekten bir ihlal gösteriyor".
     *
     * Modelin asla veremeyeceği karar bu. Model ihlalin NE olduğunu çıkarıyor;
     * buradaki dokunuş İHLAL OLDUĞUNU teyit ediyor.
     */
    fun markViolation(conversation: Conversation, mediaIndex: Int) {
        // Düğme bu hesapta zaten çizilmiyor. Kapı yine de burada duruyor:
        // çizimi gizlemek bir görünüm kararı, onayı göndermek ise emniyete
        // giden bir kayıt — ikincisi çağıranın dikkatine bırakılamaz.
        if (!ihbarEnabled) return
        val key = ihbarKey(conversation.key, mediaIndex)
        val current = ihbarMark(conversation.key, mediaIndex)
        // Yeşile dönmüş ya da yolda olan düğmeye yeniden basılmaz. İkinci basış
        // sunucuda zararsız (teyit koşullu yazılıyor, dağıtım satırları tekil)
        // ama ekranda "yeniden deniyor" gibi görünürdü.
        //
        // [IhbarPhase.NOT_VIOLATION] BU LİSTEDE YOK ve olmamalı: yanlışlıkla
        // eleyen sahibin geri alma yolu tam olarak burası. Son dokunuş kazanıyor.
        // Olumsuz istek YOLDAYKEN ise susuyoruz — iki isteğin yarışması,
        // sunucuda hangisinin sonuncu sayılacağını ağ gecikmesine bırakırdı.
        // SESSİZ YUTMA YOK ARTIK. Düğme çağında yutulan dokunuşun geri bildirimi
        // düğmenin DEĞİŞMEYEN rengiydi; kaydırmada kart uçup gidiyor ve ekranda
        // hiçbir iz kalmıyor. Sahip kaydırmayı tekrarlıyor, olumsuz yönde
        // tekrarladığında ise memura geri çekme bildirimi çıkıyor.
        val blocked = when (current.phase) {
            IhbarPhase.BUSY, IhbarPhase.REJECTING -> R.string.ihbar_in_flight
            IhbarPhase.VERIFIED, IhbarPhase.APPROVED -> R.string.ihbar_already_reported
            else -> null
        }
        if (blocked != null) {
            viewModelScope.launch { _events.send(GalleryEvent.Toast(blocked)) }
            return
        }
        if (!ihbar.hasToken) {
            ihbarMarks[key] = NO_TOKEN_MARK
            return
        }

        ihbarMarks[key] = IhbarMark(IhbarPhase.BUSY)
        viewModelScope.launch {
            ihbarMarks[key] = try {
                ihbar.approve(ihbarItemFor(conversation, mediaIndex)).toMark()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IhbarTokenMissingException) {
                NO_TOKEN_MARK
            } catch (e: IhbarException) {
                IhbarMark(IhbarPhase.ERROR, e.message)
            } catch (e: Exception) {
                // Sebep sunucudan gelmedi; metni düğmenin kendisi koyuyor.
                IhbarMark(IhbarPhase.ERROR)
            }
        }
    }

    /**
     * Sahibin olumsuz dokunuşu: "bu görüntü bir trafik ihlali DEĞİL".
     *
     * NEDEN VAR: galeriye düşen her video bir ihlal değil — reklam, kaza
     * görüntüsü, alakasız bir kayıt da geliyor. Model bunu güvenilir biçimde
     * ayırt edemiyor ve ayırt edemediği her karede maliyet iki yerden birden
     * çıkıyor: memura giden geçersiz bir ihbar ve o kayıt için ikinci kez
     * ödenen model çağrısı.
     *
     * NEDEN SESSİZ SİNYAL DEĞİL: galeri Reel seçmek için de kullanılıyor,
     * kaydırıp geçmek "baktım ve eledim" demek değil. İzlenmemiş videolar yapay
     * zekâ kontrolünden geçmeye devam ediyor; yalnızca bu düğmeye BASMAK
     * olumsuz sinyal.
     */
    fun markNotViolation(conversation: Conversation, mediaIndex: Int) {
        // Kapı olumlu dokunuştaki ile aynı ve aynı sebeple burada: çizimi
        // gizlemek bir görünüm kararı, ama bu istek bir kaydı memurdan GERİ
        // ÇEKEBİLİYOR — çağıranın dikkatine bırakılamaz.
        if (!ihbarEnabled) return
        val key = ihbarKey(conversation.key, mediaIndex)
        val current = ihbarMark(conversation.key, mediaIndex)
        // Zaten elenmiş ya da yolda olan karar yeniden gönderilmez. Onaylanmış
        // (VERIFIED/APPROVED) kayıtlar bu listede YOK: bir ihbarı memurdan geri
        // çekmenin tek yolu sola atmak ve sunucu o yolu (retractViolation)
        // destekliyor.
        //
        // ARTIK SORU SORULMUYOR: onaylanmış kayda sola atış bir zamanlar onay
        // penceresi açıyordu, çünkü geri çekme memurlara ayrıca bir bildirim
        // gönderiyordu. O bildirim kaldırıldı — kayıt memurun panelinden
        // sessizce düşüyor (REJECTED, OFFICER_VISIBLE_STATUSES dışında) — yani
        // pencerenin koruduğu geri alınamaz an da kalmadı. Geriye üç saniyelik
        // geri alma çipi kaldı ve diğer her karar gibi bu da ondan geçiyor.
        val blocked = when (current.phase) {
            IhbarPhase.BUSY, IhbarPhase.REJECTING -> R.string.ihbar_in_flight
            IhbarPhase.NOT_VIOLATION -> R.string.ihbar_already_dismissed
            else -> null
        }
        if (blocked != null) {
            viewModelScope.launch { _events.send(GalleryEvent.Toast(blocked)) }
            return
        }
        if (!ihbar.hasToken) {
            ihbarMarks[key] = NO_TOKEN_MARK
            return
        }

        ihbarMarks[key] = IhbarMark(IhbarPhase.REJECTING)
        viewModelScope.launch {
            ihbarMarks[key] = try {
                ihbar.reject(ihbarItemFor(conversation, mediaIndex)).toMark()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IhbarTokenMissingException) {
                NO_TOKEN_MARK
            } catch (e: IhbarException) {
                // Hata OLUMSUZ evreye yazılıyor, [IhbarPhase.ERROR]'a değil:
                // kırmızı olumlu düğmeye düşseydi sahip tekrar denemek için
                // ona basar ve elemek istediği videoyu emniyete gönderirdi.
                IhbarMark(IhbarPhase.REJECT_ERROR, e.message)
            } catch (e: Exception) {
                // Sebep sunucudan gelmedi; metni düğmenin kendisi koyuyor.
                IhbarMark(IhbarPhase.REJECT_ERROR)
            }
        }
    }

    /** Ayar penceresine yapıştırılan belirteci saklar ve ekranı yeniden boyar. */
    fun saveIhbarToken(token: String) {
        settings.ihbarToken = token
        // Eldeki "belirteç yok" durumları artık yalan; hepsi yeniden sorulacak.
        ihbarMarks.clear()
        refreshIhbar(items.toList())
    }

    /**
     * Düğme anahtarı: konuşma + video sırası.
     *
     * Adres kullanılmıyor — sunucu bağlantıları istendiğinde yeniden imzalıyor,
     * yani adres yarın aynı değil ve ona bağlanan durum videodan sessizce
     * kopardı. elle küfür işaretleri de aynı sebeple aynı anahtarı kullanıyor.
     */
    private fun ihbarKey(conversationKey: String, mediaIndex: Int) =
        "$conversationKey#$mediaIndex"

    fun reportPlaybackFailure(proxyUrl: String, failure: PlaybackFailure) {
        failures[proxyUrl] = failure
    }

    /** Forgets a failure so the page can put the player back and give the video another go. */
    fun clearFailure(proxyUrl: String) {
        failures.remove(proxyUrl)
    }

    /**
     * Asks the server for this conversation again, to get media links that still work.
     *
     * Instagram hands out short-lived links and the server re-signs them on request, so an
     * expired video is only expired until someone asks again — but retrying the dead link itself
     * would fail forever, which is why this is a different action from [clearFailure].
     *
     * Returns false when the conversation could not be found or the links came back unchanged;
     * the caller leaves the expiry message up rather than pretending something happened.
     */
    suspend fun refreshLinks(conversation: Conversation): Boolean {
        val index = items.indexOfFirst { it.key == conversation.key }
        if (index < 0) return false
        val fresh = try {
            // Asked for from this conversation's own position, corrected for deletes the same way
            // the paging is; a page of five is wide enough to cover it landing a row either side.
            val offset = (index - committedDeletes).coerceAtLeast(0)
            repo.loadPage(igId, offset, PAGE_SIZE).items.firstOrNull { it.key == conversation.key }
        } catch (e: UnauthorizedException) {
            _events.send(GalleryEvent.SessionLost)
            return false
        } catch (e: Exception) {
            return false
        }
        if (fresh == null || fresh.urls.isEmpty() || fresh.urls == conversation.urls) return false

        // The old links are gone, and so is anything remembered about them failing.
        conversation.urls.forEach { failures.remove(repo.proxyUrl(it)) }

        // ─── VİDEO SAYISI DEĞİŞTİYSE SAYFA DÜZENİ KAYIYOR ────────────────────
        //
        // Düz akışta her video bir sayfa; taze konuşma farklı sayıda video
        // taşıyorsa ondan SONRAKİ her sayfa kayar. Ayrıca hem ihbar işaretleri
        // hem elle küfür işaretleri SIRAYA çıpalı ("anahtar#sıra") — sayı
        // değiştiğinde o işaretler başka videoların üstünde görünürdü, ki bu
        // fark edilmesi en zor hata türü.
        val countChanged = fresh.urls.size != conversation.urls.size
        if (countChanged) {
            conversation.urls.indices.forEach { ihbarMarks.remove(ihbarKey(conversation.key, it)) }
        }

        items[index] = fresh

        if (countChanged) {
            // Aynı videoda kalmaya çalışıyoruz; video artık yoksa konuşmanın
            // son videosuna düşülüyor.
            val page = currentPageProvider()
            val onThis = feed.getOrNull(page)?.conversationKey == conversation.key
            if (onThis) {
                val mediaIndex = feed[page].mediaIndex.coerceAtMost(fresh.urls.size - 1)
                val first = firstPageOf(buildFeed(items), conversation.key)
                if (first >= 0 && mediaIndex >= 0) keepCurrentPage(first + mediaIndex)
            }
            refreshIhbar(listOf(fresh))
        }
        return true
    }

    fun setBlurFaces(enabled: Boolean) {
        settings.blurFaces = enabled
        _blurFaces.value = enabled
    }

    fun setBlurPlates(enabled: Boolean) {
        settings.blurPlates = enabled
        _blurPlates.value = enabled
    }

    fun setFastPlates(enabled: Boolean) {
        settings.fastPlates = enabled
        _fastPlates.value = enabled
    }

    fun setWatermark(enabled: Boolean) {
        settings.watermark = enabled
        _watermark.value = enabled
    }

    fun setCensorAudio(enabled: Boolean) {
        settings.censorAudio = enabled
        _censorAudio.value = enabled
    }

    fun setCensorByHand(byHand: Boolean) {
        settings.censorByHand = byHand
        _censorByHand.value = byHand
    }

    /** The handle to burn in, or null when the watermark is off. Drives preview and export alike. */
    fun watermarkHandle(): String? =
        settings.igUsername.takeIf { _watermark.value && it.isNotBlank() }

    /** Every stretch marked by hand on one video. */
    fun manualMarks(conversationKey: String, mediaIndex: Int): List<CensorWindow> =
        marks.forMedia(conversationKey, mediaIndex)

    fun addMark(conversationKey: String, mediaIndex: Int, window: CensorWindow) {
        marks.add(conversationKey, mediaIndex, window)
        markRevision++
    }

    fun removeMarkAt(conversationKey: String, mediaIndex: Int, atUs: Long) {
        marks.removeAt(conversationKey, mediaIndex, atUs)
        markRevision++
    }

    /**
     * Bumped on every change so the page redraws.
     *
     * The marks live in preferences rather than in state, because losing an afternoon of them to
     * a swipe would be worse than the extra bookkeeping.
     */
    var markRevision by mutableIntStateOf(0)
        private set

    /** What the export buttons should ask for, given how the toggles are set. */
    fun exportOptions(conversationKey: String? = null, mediaIndex: Int = 0) = ExportOptions(
        blurFaces = _blurFaces.value,
        blurPlates = _blurPlates.value,
        fastPlates = _fastPlates.value,
        watermarkHandle = watermarkHandle(),
        censorAudio = _censorAudio.value,
        censorInsults = settings.censorInsults,
        censorByHand = _censorByHand.value,
        manualWindows = conversationKey?.let { marks.forMedia(it, mediaIndex) }.orEmpty()
    )

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

        /** What the end page settles as: matches no conversation, so the delete rule ignores it. */
        private const val END_KEY = "\u0000end-of-feed"
        private const val RESCAN_WAIT_MS = 200L

        /**
         * Kaydırma kararının ağa çıkmadan önce beklediği süre.
         *
         * SİLME PENCERESİNDEN (5 sn) KISA, BİLEREK: karar, konuşmanın
         * silinmesinden önce yola çıkıyor. Uzatmak sahibi bekletir, kısaltmak
         * geri alma çipini okunamayacak kadar kısa ömürlü yapar.
         */
        const val DECISION_WINDOW_MS = 3_000L
        const val PAGE_SIZE = 5
        const val PREFETCH_DISTANCE = 3
        /** Covers the server-side index rebuild, which the first request only triggers. */
        const val INDEX_WARMUP_RETRIES = 3
        const val INDEX_WARMUP_DELAY_MS = 1_200L
    }
}

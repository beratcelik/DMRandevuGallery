import Foundation
import Observation

@MainActor
@Observable
final class GalleryViewModel {

    private(set) var items: [Conversation] = []

    /// Proxy urls that failed to play, and what kind of failure each one hit.
    ///
    /// The distinction matters: a dead link is worth asking the server to re-sign, while a
    /// dropped connection is worth simply trying again. Treating every failure as an expiry —
    /// which this did until the Android build was measured and this one was not — writes off
    /// perfectly good videos for the rest of the session.
    private(set) var failures: [String: PlaybackFailure] = [:]

    /// Video başına ihlal düğmesinin durumu; anahtarı konuşma + video sırası.
    ///
    /// NEDEN SAYFADA DEĞİL DE BURADA: dikey akış yalnızca görünen sayfaları
    /// derli tutuyor, iki konuşma aşağı kaydırıp geri dönmek sayfayı sıfırdan
    /// oluşturuyor. Durum sayfada dursaydı yeşile dönmüş bir düğme her dönüşte
    /// nötre döner ve sahip aynı videoyu ikinci kez işaretlerdi.
    ///
    /// NEDEN DİSKE YAZILMIYOR: tek doğru kaynak sunucu. Saklanan bir "yeşil",
    /// yönetici konsolunda geri çekilen bir ihbarda yalan söylemeye devam
    /// ederdi; uygulama yeniden açıldığında sayfa zaten tek istekte boyanıyor.
    private(set) var ihbarMarks: [String: IhbarMark] = [:]

    private(set) var loading = true
    private(set) var hasMore = true

    /// How many video-carrying conversations are left for this account.
    private(set) var remaining = 0

    /// Raised when the server rejects the session; the UI drops back to the login screen.
    private(set) var sessionLost = false

    /// A message to flash over the video, cleared by the view once shown.
    var toast: String?

    private(set) var blurFaces: Bool
    private(set) var blurPlates: Bool
    private(set) var fastPlates: Bool
    private(set) var watermark: Bool
    private(set) var censorAudio: Bool
    private(set) var censorByHand: Bool

    private let igId: String

    /// İhbar düğmesinin bu hesapta çizilip çizilmeyeceği.
    ///
    /// İhbar sistemi tek bir Instagram hesabını dinliyor (bkz. ``IhbarAccount``),
    /// galeri ise iki hesap geziyor. Karar burada bir kez veriliyor ve hem
    /// düğmenin görünürlüğünü hem de ağa çıkan iki isteği aynı anda kesiyor;
    /// yalnızca görünümde saklamak, arka planda çalışan toplu durum sorgusunu
    /// yanlış hesapta da attırmaya devam ederdi.
    ///
    /// NEDEN ``SettingsStore/igUsername`` DEĞİL DE ``igId``: ekrandaki videolar
    /// bu kimlikle sayfalanıyor, yani gerçekten GEZİLEN hesap bu. igUsername
    /// giriş ekranına yazılmış bir metin; ikisinin ayrıştığı bir an olursa karar
    /// ekranda ne olduğuna göre verilmeli.
    ///
    /// NEDEN AYRICA "hesap değişti" DİYE ÖNBELLEK TEMİZLENMİYOR: hesap bu görünüm
    /// modelinin kimliği. ``igId`` değişmez ve hesap değiştirmenin tek yolu giriş
    /// ekranından geçmek — orada `igId` bir an nil oluyor, GalleryView `.id(igId)`
    /// ile sıfırdan kuruluyor ve ``ihbarMarks`` yeni örnekte boş başlıyor. Bir
    /// hesabın işaretlerinin diğerinde görünmesi bu yüzden mümkün değil.
    let ihbarAvailable: Bool

    private let repository = ServiceLocator.repository!
    private let ihbar = ServiceLocator.ihbarRepository!
    private let settings = ServiceLocator.settings!
    private let marks = ServiceLocator.manualMarks

    private var pending: (conversation: Conversation, job: Task<Void, Never>)?
    private var lastSettledKey: String?
    private var nextOffset = 0
    private var committedDeletes = 0
    private var loadingMore = false

    init(igId: String) {
        self.igId = igId
        ihbarAvailable = IhbarAccount.matches(igId)
        blurFaces = settings.blurFaces
        blurPlates = settings.blurPlates
        fastPlates = settings.fastPlates
        watermark = settings.watermark
        censorAudio = settings.censorAudio
        censorByHand = settings.censorByHand
    }

    // MARK: - Paging

    func loadMore(initial: Bool = false) async {
        guard !loadingMore, initial || hasMore else { return }
        loadingMore = true
        defer {
            loadingMore = false
            loading = false
        }

        do {
            var warmupAttempt = 0
            while true {
                // Deleting shrinks the server-side index, so every committed delete shifts the
                // window down by one; without this correction we would skip conversations.
                let offset = max(nextOffset - committedDeletes, 0)
                let page = try await repository.loadPage(igId: igId, offset: offset, limit: Self.pageSize)
                let known = Set(items.map(\.key))
                let fresh = page.items.filter { !known.contains($0.key) && !$0.urls.isEmpty }
                items.append(contentsOf: fresh)
                // Sayfa geldiği anda, sahip oraya kaydırmadan önce boyanıyor:
                // düğmenin rengi videoyla birlikte hazır olmalı.
                refreshIhbar(fresh)
                hasMore = page.hasMore
                // The server count already reflects everything committed so far.
                remaining = page.total

                // A cold or stale video index answers the first request empty while the server
                // rebuilds it in the background. Without this retry the app shows "no
                // conversations" for a salon that has them, until it is relaunched.
                if initial, items.isEmpty, warmupAttempt < Self.indexWarmupRetries {
                    warmupAttempt += 1
                    try await Task.sleep(for: .milliseconds(Self.indexWarmupDelayMS))
                    continue // re-ask from the top; nextOffset stays 0 for a still-empty feed
                }

                nextOffset = page.nextOffset + committedDeletes
                if lastSettledKey == nil { lastSettledKey = items.first?.key }
                break
            }
        } catch is UnauthorizedError {
            sessionLost = true
        } catch {
            hasMore = false
        }
    }

    /// Akışın DÜZ sayfa listesi: her sayfa bir video.
    ///
    /// NEDEN TÜRETİLMİŞ (ikinci bir liste tutulmuyor): iki liste er geç ayrışır
    /// ve ayrıştıkları an sayfa sırası ile konuşma sırası birbirini tutmaz —
    /// yani yanlış müşteri silinir. Tek gerçek kaynak ``items``.
    var feed: [FeedPage] { buildFeed(items) }

    /// Geri alma penceresinde bekleyen kaydırma kararları.
    private(set) var pendingDecisions = DecisionLedger()

    /// Bekleyen kararların zamanlayıcıları; anahtar ``FeedPage/id``.
    private var decisionJobs: [String: Task<Void, Never>] = [:]

    // MARK: - Swipe-to-delete

    /// Dikey akış bir SAYFADA yerleştiğinde çalışıyor.
    ///
    /// Akış düzleşti: bir sayfa artık bir VİDEO. "Sayfa değişti" bu yüzden
    /// "müşteri değişti" demek değil ve kural bunu ayırt edemezse, sahip aynı
    /// müşterinin ikinci videosuna geçtiği anda o müşteri beş saniye sonra
    /// sessizce siliniyor. Ayrımı saf kural veriyor (ui/FeedPages.swift).
    func onPageSettled(pageID: String) {
        guard let current = feed.first(where: { $0.id == pageID }) else { return }

        let action = onSettled(
            previousConversationKey: lastSettledKey,
            next: current,
            conversationKeys: items.map(\.key),
            pendingDeleteKey: pending?.conversation.key
        )
        lastSettledKey = current.conversationKey

        switch action {
        case .none:
            break
        case .cancelPendingDelete:
            cancelPending()
        case .queueDelete(let index):
            if items.indices.contains(index) { queueDelete(items[index]) }
        }

        // BU SAYFAYA GERİ DÖNMEK, KARARI DA GERİ ALIYOR. İkinci geri alma yolu
        // (çipe dokunmak) ekranın altında duruyor; bu ise sahibin zaten yaptığı
        // hareketin karşılığı: "bir bakayım" diye geri kaydırmak.
        if pendingDecisions[current.id] != nil { undoDecision(current.id) }

        maybeLoadMore(around: current.conversationKey)
    }

    private func maybeLoadMore(around key: String) {
        guard let index = items.firstIndex(where: { $0.key == key }) else { return }
        if index >= items.count - Self.prefetchDistance {
            Task { await loadMore() }
        }
    }

    /// Holds the deletion for ``undoWindow`` so swiping back cancels it. Deliberately silent: the
    /// swipe itself is the feedback, and a banner would only cover the action buttons.
    private func queueDelete(_ conversation: Conversation) {
        // Only one deletion can be undone at a time; a new one settles the previous immediately.
        commitPendingNow()
        let job = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.undoWindowSeconds))
            guard !Task.isCancelled else { return }
            await self?.commit(conversation)
        }
        pending = (conversation, job)
    }

    private func cancelPending() {
        pending?.job.cancel()
        pending = nil
    }

    /// Fires the queued deletion right away — used when leaving the screen or queueing another.
    func commitPendingNow() {
        guard let current = pending else { return }
        current.job.cancel()
        pending = nil
        Task { await commit(current.conversation) }
    }

    private func commit(_ conversation: Conversation) async {
        do {
            try await repository.deleteConversation(
                salonId: conversation.salonId,
                clientId: conversation.clientId
            )
        } catch is UnauthorizedError {
            sessionLost = true
            return
        } catch {
            // The conversation stays in the feed; the next swipe past it can try again.
            pending = nil
            return
        }

        if let index = items.firstIndex(where: { $0.key == conversation.key }) {
            items.remove(at: index)
            committedDeletes += 1
            remaining = max(remaining - 1, 0)
            // No index correction is needed here, unlike on Android: the pager is positioned by
            // conversation key, so removing one above the viewport leaves the visible one visible.
        }
        if pending?.conversation.key == conversation.key { pending = nil }
        if items.count <= Self.prefetchDistance { await loadMore() }
    }

    // MARK: - Kaydırma kararları (geri alma penceresi)

    /// Sağa/sola atışın kararı: üç saniye bekletilip sonra ağa çıkıyor.
    ///
    /// ─── NEDEN BEKLETİLİYOR ────────────────────────────────────────────────
    /// Sağa atış bir ihbarı emniyet birimine gönderiyor ve bu tam olarak geri
    /// alınamıyor: geri çekme, memura "bu ihbarı dikkate almayın" bildirimi
    /// gönderiyor. Kaydırma kararı hızlandırdığı kadar yanlış kararı da
    /// hızlandırıyor; üç saniyelik pencere parmağın kaydığı hâlleri ağa hiç
    /// çıkmadan yakalıyor.
    ///
    /// ─── NEDEN KONUŞMANIN ANLIK GÖRÜNTÜSÜ SAKLANIYOR ───────────────────────
    /// Karar beklerken sahip bir sonraki müşteriye geçebiliyor ve o hareket
    /// geride bıraktığı konuşmayı silme sırasına alıyor (beş saniye). Karar
    /// uygulanırken anahtarla aransaydı hiçbir şey bulunamaz ve karar sessizce
    /// kaybolurdu.
    func decide(_ conversation: Conversation, mediaIndex: Int, decision: SwipeOutcome) {
        // Kapı burada da duruyor: çizimi gizlemek bir görünüm kararı, bu istek
        // ise emniyete giden bir kayıt — çağıranın dikkatine bırakılamaz.
        guard ihbarAvailable else { return }

        let page = FeedPage(conversationKey: conversation.key, mediaIndex: mediaIndex)
        // Aynı sayfaya ikinci karar: öncekinin zamanlayıcısı iptal, yerine
        // yenisi. Sahip fikrini değiştirdiyse ağa yalnızca son kararı çıkmalı.
        decisionJobs.removeValue(forKey: page.id)?.cancel()
        pendingDecisions.put(
            QueuedDecision(page: page, conversation: conversation, decision: decision)
        )
        decisionJobs[page.id] = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Self.decisionWindowSeconds))
            guard !Task.isCancelled else { return }
            await self?.fireDecision(page.id)
        }
    }

    /// Geri alma: çipe dokunmak ya da o sayfaya geri kaydırmak.
    func undoDecision(_ pageID: String) {
        guard let job = decisionJobs.removeValue(forKey: pageID) else { return }
        job.cancel()
        pendingDecisions.remove(pageID)
        toast = Strings.swipeUndone
    }

    /// Bekleyen kararları ANINDA gönderir — uygulamadan çıkılırken.
    ///
    /// ``commitPendingNow()`` ile aynı gerekçe: kaydırıp ana ekrana basmak,
    /// kararı sessizce yutmamalı.
    func commitDecisionsNow() {
        let waiting = pendingDecisions.all
        for job in decisionJobs.values { job.cancel() }
        decisionJobs.removeAll()
        pendingDecisions.clear()
        for queued in waiting { applyDecision(queued) }
    }

    private func fireDecision(_ pageID: String) {
        guard let queued = pendingDecisions[pageID] else { return }
        decisionJobs.removeValue(forKey: pageID)
        pendingDecisions.remove(pageID)
        applyDecision(queued)
    }

    private func applyDecision(_ queued: QueuedDecision) {
        switch queued.decision {
        case .report:
            markViolation(queued.conversation, mediaIndex: queued.page.mediaIndex)
        case .dismiss:
            markNotViolation(queued.conversation, mediaIndex: queued.page.mediaIndex)
        }
    }

    /// Bu konuşmada HENÜZ KARAR VERİLMEMİŞ videoların sıraları.
    ///
    /// Toplu eleme yalnızca bunlara dokunuyor: onaylanmış, elenmiş ya da isteği
    /// yolda olan bir videoyu toplu bir hareketle yeniden karara bağlamak,
    /// sahibin tek tek verdiği kararları toplu bir dokunuşla ezmek olurdu.
    func undecidedIndices(_ conversation: Conversation) -> [Int] {
        conversation.urls.indices.filter { index in
            // Bekleyen bir karar da "karar verilmiş" sayılıyor: üç saniye sonra
            // dolacak ve toplu elemenin üstüne yazacaktı.
            let page = FeedPage(conversationKey: conversation.key, mediaIndex: index)
            if pendingDecisions[page.id] != nil { return false }
            switch ihbarMark(conversationKey: conversation.key, mediaIndex: index).phase {
            case .notViolation, .approved, .verified, .verifiedPending,
                 .busy, .rejecting, .noToken:
                return false
            default:
                return true
            }
        }
    }

    /// Konuşmanın karar verilmemiş videolarını TEK istekte eler.
    ///
    /// NEDEN TEK İSTEK: iki yazma ucu tek bir oran sınırı kovasını paylaşıyor
    /// (dakikada yirmi). On beş videoyu tek tek elemek o bütçenin dörtte üçünü
    /// yakıyor ve aynı dakikadaki GERÇEK ihbarı da engelliyor.
    ///
    /// ÖNCE BEKLEYEN KARARLAR İPTAL EDİLİYOR: aksi hâlde üç saniye sonra dolan
    /// bir olumlu karar toplu elemenin üstüne yazardı (sunucuda son dokunuş
    /// kazanıyor) ve bir de boşuna çıkarım ısmarlardı.
    func dismissAll(_ conversation: Conversation) {
        guard ihbarAvailable else { return }
        for queued in pendingDecisions.of(conversationKey: conversation.key) {
            undoDecision(queued.page.id)
        }

        let indices = undecidedIndices(conversation)
        guard !indices.isEmpty else { return }
        guard ihbar.hasToken else {
            for index in indices {
                ihbarMarks[Self.ihbarKey(conversation.key, index)] = IhbarMark(phase: .noToken)
            }
            return
        }

        for index in indices {
            ihbarMarks[Self.ihbarKey(conversation.key, index)] = IhbarMark(phase: .rejecting)
        }

        let items = indices.map { ihbarItem(for: conversation, mediaIndex: $0) }
        Task { [weak self] in
            guard let self else { return }
            do {
                let answers = try await ihbar.rejectBulk(items)
                // Yanıt İSTEK SIRASIYLA dönüyor. Uzunluk tutmuyorsa HİÇBİR
                // işaret boyanmıyor ve durum yeniden soruluyor: kayan bir liste
                // yanlış videoyu elenmiş gösterir ve bu, fark edilmesi en zor
                // hata türü.
                guard answers.items.count == indices.count else {
                    for index in indices {
                        ihbarMarks.removeValue(forKey: Self.ihbarKey(conversation.key, index))
                    }
                    refreshIhbar([conversation])
                    return
                }
                for (position, index) in indices.enumerated() {
                    ihbarMarks[Self.ihbarKey(conversation.key, index)] = answers.items[position].mark
                }
                toast = Strings.bulkDismissResult(answers.applied, answers.skipped)
            } catch is IhbarTokenMissingError {
                for index in indices {
                    ihbarMarks[Self.ihbarKey(conversation.key, index)] = IhbarMark(phase: .noToken)
                }
            } catch let error as IhbarError {
                for index in indices {
                    ihbarMarks[Self.ihbarKey(conversation.key, index)] =
                        IhbarMark(phase: .rejectError, detail: error.message)
                }
                toast = error.message
            } catch {
                // YARIM UYGULANMIŞ OLABİLİR: sunucu bazı öğeleri işleyip düşmüş
                // olabilir ve elimizde hangilerinin işlendiği bilgisi yok.
                // İşaretleri uydurmak yerine SUNUCUYA YENİDEN SORUYORUZ.
                for index in indices {
                    ihbarMarks.removeValue(forKey: Self.ihbarKey(conversation.key, index))
                }
                refreshIhbar([conversation])
                toast = Strings.bulkDismissFailed
            }
        }
    }

    // MARK: - İhbar köprüsü

    /// Bir videonun düğmesinin bildiği her şey; hiç sorulmamışsa varsayılan.
    func ihbarMark(conversationKey: String, mediaIndex: Int) -> IhbarMark {
        ihbarMarks[Self.ihbarKey(conversationKey, mediaIndex)]
            ?? IhbarMark(phase: ihbar.hasToken ? .unknown : .noToken)
    }

    /// Bir sayfa dolusu videonun durumunu TEK istekte alır ve düğmeleri boyar.
    ///
    /// NEDEN VİDEO BAŞINA DEĞİL: sayfa başına beş konuşma ve her birinde birkaç
    /// video var. Video başına istek, her kaydırmada onlarca istek demek olurdu;
    /// mobil bağlantıda gözle görülür gecikme ve sunucudaki oran sınırının hiçbir
    /// iş yapmadan dolması.
    private func refreshIhbar(_ conversations: [Conversation]) {
        // Yanlış hesapta ağa TEK istek bile çıkmıyor. İhbar sunucusu bu hesabın
        // videolarını tanımadığı için elli öğelik sorgu yalnızca oran sınırını
        // doldurur, sonra da her düğmeye taşıyamayacağı bir hata metni yazardı.
        guard ihbarAvailable, ihbar.hasToken else { return }
        let targets: [(key: String, item: IhbarItem)] = conversations.flatMap { conversation in
            conversation.urls.indices.map { index in
                (
                    key: Self.ihbarKey(conversation.key, index),
                    item: ihbarItem(for: conversation, mediaIndex: index)
                )
            }
        }
        guard !targets.isEmpty else { return }

        Task { [weak self] in
            guard let self else { return }
            for chunk in Self.chunked(targets, by: IhbarRepository.maxItems) {
                let answers: [IhbarStatusItem]
                do {
                    answers = try await ihbar.status(chunk.map { $0.item })
                } catch let error as IhbarError {
                    // Sunucu isteği anladı ve reddetti; pratikte bu "belirteç
                    // geçersiz" demek. Yutmak, sahibin bozuk bir belirteçle
                    // düğmeye basıp durmasına yol açardı — sebebi düğmede yazsın.
                    for target in chunk {
                        ihbarMarks[target.key] = IhbarMark(phase: .error, detail: error.message)
                    }
                    return
                } catch {
                    // Ağ yok ya da sunucu kapalı: düğmeler "bilinmiyor" kalıyor ve
                    // basılabilir olmayı sürdürüyor. İzlenen videonun üstüne hata
                    // koymak, kullanıcının yapabileceği bir şey olmadığı hâlde
                    // arıza duygusu yaratırdı.
                    return
                }
                // Yanıt istek sırasıyla dönüyor. Uzunluk tutmuyorsa HİÇBİR düğme
                // boyanmıyor: kayan bir liste yanlış videoyu yeşile çevirir ve bu,
                // fark edilmesi en zor hata türü.
                guard answers.count == chunk.count else { return }
                for (index, target) in chunk.enumerated() {
                    ihbarMarks[target.key] = answers[index].mark
                }
            }
        }
    }

    /// Sahibin dokunuşu: "bu görüntü gerçekten bir ihlal gösteriyor".
    ///
    /// Modelin asla veremeyeceği karar bu. Model ihlalin NE olduğunu çıkarıyor;
    /// buradaki dokunuş İHLAL OLDUĞUNU teyit ediyor.
    func markViolation(_ conversation: Conversation, mediaIndex: Int) {
        // Düğme yanlış hesapta zaten çizilmiyor; koruma burada da duruyor çünkü
        // onay tek yönlü bir eylem — kaydı emniyet birimine giden hatta sokuyor
        // ve tek bir görünüm koşulunun doğru yazılmış olmasına bırakılamaz.
        guard ihbarAvailable else { return }
        let key = Self.ihbarKey(conversation.key, mediaIndex)
        let current = ihbarMark(conversationKey: conversation.key, mediaIndex: mediaIndex)
        // Yeşile dönmüş ya da yolda olan düğmeye yeniden basılmaz. İkinci basış
        // sunucuda zararsız (teyit koşullu yazılıyor, dağıtım satırları tekil) ama
        // ekranda "yeniden deniyor" gibi görünürdü.
        //
        // .rejecting DE ENGELLİ: olumsuz istek yoldayken olumlu isteği de yola
        // çıkarmak, iki zıt yazmayı yarıştırmak demek. Kazananı ağ gecikmesi
        // belirlerdi ve "son dokunuş kazanır" kuralı, tam da sahibin fikrini
        // değiştirdiği anda yalan olurdu. Elenmiş kayda (.notViolation) basmak ise
        // SERBEST — fikir değiştirmenin yolu bu.
        //
        // SESSİZ YUTMA YOK ARTIK: düğme çağında yutulan dokunuşun geri bildirimi
        // düğmenin DEĞİŞMEYEN rengiydi; kaydırmada kart uçup gidiyor ve ekranda
        // hiçbir iz kalmıyor. Sahip kaydırmayı tekrarlıyor, olumsuz yönde
        // tekrarladığında ise memura geri çekme bildirimi çıkıyor.
        switch current.phase {
        case .busy, .rejecting:
            toast = Strings.ihbarInFlight
            return
        case .verified, .approved:
            toast = Strings.ihbarAlreadyReported
            return
        default:
            break
        }
        guard ihbar.hasToken else {
            ihbarMarks[key] = IhbarMark(phase: .noToken)
            return
        }

        ihbarMarks[key] = IhbarMark(phase: .busy)
        let item = ihbarItem(for: conversation, mediaIndex: mediaIndex)
        Task { [weak self] in
            guard let self else { return }
            do {
                ihbarMarks[key] = try await ihbar.approve(item).mark
            } catch is IhbarTokenMissingError {
                ihbarMarks[key] = IhbarMark(phase: .noToken)
            } catch let error as IhbarError {
                ihbarMarks[key] = IhbarMark(phase: .error, detail: error.message)
            } catch {
                // Sebep sunucudan gelmedi; metni düğmenin kendisi koyuyor.
                ihbarMarks[key] = IhbarMark(phase: .error)
            }
        }
    }

    /// Sahibin olumsuz dokunuşu: "bu görüntü bir ihlal DEĞİL".
    ///
    /// İki şeyi birden durduruyor: kayıt memura gitmiyor ve o video için modele
    /// yeniden ödenmiyor. İkisi de yalnızca AÇIK bir dokunuşla oluyor —
    /// kaydırıp geçmek hiçbir anlama gelmiyor, çünkü bu galeri aynı zamanda Reels
    /// seçmek için geziliyor ve görülmemiş video yapay zekâ kontrolüne devam
    /// etmeli.
    ///
    /// KAYIT HENÜZ YOKKEN DE ÇALIŞIYOR: sunucu, ihbarı olmayan medyada dokunuşu
    /// medyaya çıpalayıp saklıyor (bkz. PendingHumanVerification deseni). Bu dalı
    /// istemcide kapatsaydık, çıkarımın gecikmeli koştuğu sistemde elemelerin
    /// ÇOĞU kaybolurdu — sahip videoyu izlediği anda basıyor, kayıt saatler sonra
    /// açılıyor.
    func markNotViolation(_ conversation: Conversation, mediaIndex: Int) {
        // Düğme yanlış hesapta zaten çizilmiyor; koruma burada da duruyor çünkü
        // eleme, onaylanmış bir kaydı memurdan geri çektirebiliyor ve tek bir
        // görünüm koşulunun doğru yazılmış olmasına bırakılamaz.
        guard ihbarAvailable else { return }
        let key = Self.ihbarKey(conversation.key, mediaIndex)
        let current = ihbarMark(conversationKey: conversation.key, mediaIndex: mediaIndex)
        // Zaten elenmiş ya da yolda olan bir düğmeye yeniden basılmaz. Aynı
        // gerekçenin aynası: olumlu istek yoldayken (.busy) bu yol da kapalı.
        switch current.phase {
        case .busy, .rejecting:
            toast = Strings.ihbarInFlight
            return
        case .notViolation:
            toast = Strings.ihbarAlreadyDismissed
            return
        default:
            break
        }
        guard ihbar.hasToken else {
            ihbarMarks[key] = IhbarMark(phase: .noToken)
            return
        }

        ihbarMarks[key] = IhbarMark(phase: .rejecting)
        let item = ihbarItem(for: conversation, mediaIndex: mediaIndex)
        Task { [weak self] in
            guard let self else { return }
            do {
                ihbarMarks[key] = try await ihbar.reject(item).mark
            } catch is IhbarTokenMissingError {
                ihbarMarks[key] = IhbarMark(phase: .noToken)
            } catch let error as IhbarError {
                ihbarMarks[key] = IhbarMark(phase: .rejectError, detail: error.message)
            } catch {
                // Sebep sunucudan gelmedi; metni düğmenin kendisi koyuyor.
                ihbarMarks[key] = IhbarMark(phase: .rejectError)
            }
        }
    }

    /// Ayar penceresine yapıştırılan belirteci saklar ve ekranı yeniden boyar.
    func saveIhbarToken(_ token: String) {
        settings.ihbarToken = token
        // Eldeki "belirteç yok" durumları artık yalan; hepsi yeniden sorulacak.
        ihbarMarks.removeAll()
        refreshIhbar(items)
    }

    /// Düğme anahtarı: konuşma + video sırası.
    ///
    /// Adres kullanılmıyor — sunucu bağlantıları istendiğinde yeniden imzalıyor,
    /// yani adres yarın aynı değil ve ona bağlanan durum videodan sessizce
    /// kopardı; elle küfür işaretleri de aynı sebeple aynı anahtarı kullanıyor.
    private static func ihbarKey(_ conversationKey: String, _ mediaIndex: Int) -> String {
        "\(conversationKey)#\(mediaIndex)"
    }

    /// Sunucunun tek istekte kabul ettiğinden fazlasını sormamak için.
    private static func chunked<T>(_ items: [T], by size: Int) -> [[T]] {
        stride(from: 0, to: items.count, by: size).map {
            Array(items[$0..<min($0 + size, items.count)])
        }
    }

    // MARK: - Toggles

    func report(_ failure: PlaybackFailure, for proxyURL: String) {
        failures[proxyURL] = failure
    }

    /// Forgets a failure so the page can put the player back and give the video another go.
    func clearFailure(_ proxyURL: String) {
        failures.removeValue(forKey: proxyURL)
    }

    /// Asks the server for this conversation again, to get media links that still work.
    ///
    /// Instagram hands out short-lived links and the server re-signs them on request, so an
    /// expired video is only expired until someone asks again — but retrying the dead link itself
    /// would fail forever, which is why this is a different action from ``clearFailure(_:)``.
    ///
    /// Returns false when the conversation could not be found or the links came back unchanged;
    /// the caller leaves the expiry message up rather than pretending something happened.
    func refreshLinks(for conversation: Conversation) async -> Bool {
        guard let index = items.firstIndex(where: { $0.key == conversation.key }) else {
            return false
        }
        let fresh: Conversation?
        do {
            // Asked for from this conversation's own position, corrected for deletes the same way
            // the paging is; a page of five is wide enough to cover it landing a row either side.
            let offset = max(index - committedDeletes, 0)
            let page = try await repository.loadPage(
                igId: igId, offset: offset, limit: Self.pageSize
            )
            fresh = page.items.first { $0.key == conversation.key }
        } catch is UnauthorizedError {
            sessionLost = true
            return false
        } catch {
            return false
        }
        guard let fresh, !fresh.urls.isEmpty, fresh.urls != conversation.urls else { return false }

        // The old links are gone, and so is anything remembered about them failing.
        for url in conversation.urls {
            failures.removeValue(forKey: repository.proxyURL(url)?.absoluteString ?? url)
        }

        // ─── VİDEO SAYISI DEĞİŞTİYSE İŞARETLER KAYIYOR ───────────────────────
        //
        // Düz akışta her video bir sayfa; taze konuşma farklı sayıda video
        // taşıyorsa ondan SONRAKİ her sayfa kayar. Ayrıca hem ihbar işaretleri
        // hem elle küfür işaretleri SIRAYA çıpalı ("anahtar#sıra") — sayı
        // değiştiğinde o işaretler başka videoların üstünde görünürdü, ki bu
        // fark edilmesi en zor hata türü.
        let countChanged = fresh.urls.count != conversation.urls.count
        if countChanged {
            for mediaIndex in conversation.urls.indices {
                ihbarMarks.removeValue(forKey: Self.ihbarKey(conversation.key, mediaIndex))
            }
        }

        items[index] = fresh

        if countChanged { refreshIhbar([fresh]) }
        return true
    }

    func setBlurFaces(_ enabled: Bool) {
        settings.blurFaces = enabled
        blurFaces = enabled
    }

    func setBlurPlates(_ enabled: Bool) {
        settings.blurPlates = enabled
        blurPlates = enabled
    }

    func setFastPlates(_ enabled: Bool) {
        settings.fastPlates = enabled
        fastPlates = enabled
    }

    func setWatermark(_ enabled: Bool) {
        settings.watermark = enabled
        watermark = enabled
    }

    func setCensorAudio(_ enabled: Bool) {
        settings.censorAudio = enabled
        censorAudio = enabled
    }

    func setCensorByHand(_ byHand: Bool) {
        settings.censorByHand = byHand
        censorByHand = byHand
    }

    /// Every stretch marked by hand on one video.
    func manualMarks(conversationKey: String, mediaIndex: Int) -> [CensorWindow] {
        marks.forMedia(conversationKey: conversationKey, mediaIndex: mediaIndex)
    }

    func addMark(conversationKey: String, mediaIndex: Int, window: CensorWindow) {
        marks.add(conversationKey: conversationKey, mediaIndex: mediaIndex, window: window)
        markRevision += 1
    }

    func removeMark(conversationKey: String, mediaIndex: Int, atUs: Int64) {
        marks.removeAt(conversationKey: conversationKey, mediaIndex: mediaIndex, atUs: atUs)
        markRevision += 1
    }

    /// Bumped on every change so the page redraws.
    ///
    /// The marks live in preferences rather than in state, because losing an afternoon of them to
    /// a swipe would be worse than the extra bookkeeping.
    private(set) var markRevision = 0

    /// The handle to burn in, or nil when the watermark is off. Drives preview and export alike.
    func watermarkHandle() -> String? {
        let handle = settings.igUsername
        return watermark && !handle.isEmpty ? handle : nil
    }

    /// What the export buttons should ask for, given how the toggles are set.
    func exportOptions(
        conversationKey: String? = nil,
        mediaIndex: Int = 0
    ) -> ExportOptions {
        ExportOptions(
            blurFaces: blurFaces,
            blurPlates: blurPlates,
            fastPlates: fastPlates,
            watermarkHandle: watermarkHandle(),
            censorAudio: censorAudio,
            censorInsults: settings.censorInsults,
            censorByHand: censorByHand,
            manualWindows: conversationKey.map {
                marks.forMedia(conversationKey: $0, mediaIndex: mediaIndex)
            } ?? []
        )
    }

    func reportSessionLost() {
        sessionLost = true
    }

    static let undoWindowSeconds: Double = 5

    /// Kaydırma kararının ağa çıkmadan önce beklediği süre.
    ///
    /// SİLME PENCERESİNDEN (5 sn) KISA, BİLEREK: karar, konuşmanın
    /// silinmesinden önce yola çıkıyor. Uzatmak sahibi bekletir, kısaltmak geri
    /// alma çipini okunamayacak kadar kısa ömürlü yapar.
    static let decisionWindowSeconds: Double = 3
    static let pageSize = 5
    static let prefetchDistance = 3
    /// Covers the server-side index rebuild, which the first request only triggers.
    static let indexWarmupRetries = 3
    static let indexWarmupDelayMS = 1_200
}

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

    // MARK: - Swipe-to-delete

    /// Called whenever the vertical pager settles. Deleting is driven purely by which conversation
    /// the operator *left*, compared by stable key — indices shift when items are removed, so
    /// comparing them would delete the wrong customer.
    func onPageSettled(key newKey: String) {
        let previousKey = lastSettledKey
        lastSettledKey = newKey

        if newKey == previousKey {
            maybeLoadMore(around: newKey)
            return
        }

        // Swiping back onto the conversation queued for deletion is an implicit undo.
        if pending?.conversation.key == newKey {
            cancelPending()
            maybeLoadMore(around: newKey)
            return
        }

        if let previousKey,
           let previousIndex = items.firstIndex(where: { $0.key == previousKey }),
           let newIndex = items.firstIndex(where: { $0.key == newKey }),
           // Forward swipe only — going back must never delete.
           newIndex > previousIndex {
            queueDelete(items[previousIndex])
        }
        maybeLoadMore(around: newKey)
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
        guard ihbar.hasToken else { return }
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
        let key = Self.ihbarKey(conversation.key, mediaIndex)
        let current = ihbarMark(conversationKey: conversation.key, mediaIndex: mediaIndex)
        // Yeşile dönmüş ya da yolda olan düğmeye yeniden basılmaz. İkinci basış
        // sunucuda zararsız (teyit koşullu yazılıyor, dağıtım satırları tekil) ama
        // ekranda "yeniden deniyor" gibi görünürdü.
        guard current.phase != .busy, current.phase != .verified, current.phase != .approved else {
            return
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
        items[index] = fresh
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
    static let pageSize = 5
    static let prefetchDistance = 3
    /// Covers the server-side index rebuild, which the first request only triggers.
    static let indexWarmupRetries = 3
    static let indexWarmupDelayMS = 1_200
}

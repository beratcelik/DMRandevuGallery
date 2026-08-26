import Foundation
import Observation

@MainActor
@Observable
final class GalleryViewModel {

    private(set) var items: [Conversation] = []

    /// Proxy urls that failed to play — the CDN link behind them has expired.
    private(set) var expiredURLs: Set<String> = []

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

    private let igId: String
    private let repository = ServiceLocator.repository!
    private let settings = ServiceLocator.settings!

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
                items.append(contentsOf: page.items.filter { !known.contains($0.key) && !$0.urls.isEmpty })
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

    // MARK: - Toggles

    func markExpired(_ proxyURL: String) {
        expiredURLs.insert(proxyURL)
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

    /// The handle to burn in, or nil when the watermark is off. Drives preview and export alike.
    func watermarkHandle() -> String? {
        let handle = settings.igUsername
        return watermark && !handle.isEmpty ? handle : nil
    }

    /// What the export buttons should ask for, given how the toggles are set.
    func exportOptions() -> ExportOptions {
        ExportOptions(
            blurFaces: blurFaces,
            blurPlates: blurPlates,
            fastPlates: fastPlates,
            watermarkHandle: watermarkHandle()
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

import AVFoundation
import Foundation

/// Two players, so the conversation on screen keeps playing while the next one pre-buffers and a
/// swipe starts instantly. A player per page would exhaust decoders; a single player would
/// rebuffer on every swipe.
///
/// Slots are claimed by conversation key rather than page index: deleting a conversation above the
/// viewport shifts every index down, and an index-keyed pool would hand the visible conversation
/// the *other* player mid-playback, restarting the video.
@MainActor
final class PlayerManager {

    private static let poolSize = 2

    private let players: [AVPlayer]
    private let cookies: () -> [HTTPCookie]

    /// Reports a failed video by its (proxy) url, with true when the session itself is dead.
    private let onError: (_ url: String, _ unauthorized: Bool) -> Void

    private var slotKeys = [String?](repeating: nil, count: poolSize)
    private var slotURLs = [String?](repeating: nil, count: poolSize)
    private var slotUsedAt = [Int](repeating: 0, count: poolSize)
    private var clock = 0

    /// Which slot is on screen. The other one is only pre-buffering and stays unfiltered.
    private var visibleSlot = -1

    private var watermarkHandle: String?

    /// What each slot's picture was last filtered with, so it is only rebuilt when it changes.
    private var slotWatermark = [String?](repeating: nil, count: poolSize)

    private var statusObservations = [NSKeyValueObservation?](repeating: nil, count: poolSize)
    private var loopObserver: NSObjectProtocol?

    init(
        cookies: @escaping () -> [HTTPCookie],
        onError: @escaping (_ url: String, _ unauthorized: Bool) -> Void
    ) {
        self.cookies = cookies
        self.onError = onError
        players = (0..<Self.poolSize).map { _ in
            let player = AVPlayer()
            // We put the playhead back ourselves, so the player must not stop at the end.
            player.actionAtItemEnd = .none
            return player
        }

        loopObserver = NotificationCenter.default.addObserver(
            forName: AVPlayerItem.didPlayToEndTimeNotification,
            object: nil,
            queue: .main
        ) { [weak self] note in
            guard let item = note.object as? AVPlayerItem else { return }
            MainActor.assumeIsolated { self?.restart(item) }
        }
    }

    deinit {
        if let loopObserver { NotificationCenter.default.removeObserver(loopObserver) }
    }

    /// The player currently holding `key`, claiming the least recently used slot if it has none.
    func player(for key: String) -> AVPlayer {
        players[slot(for: key)]
    }

    /// The player already holding `key`, or nil. Unlike ``player(for:)`` this claims nothing, so
    /// it is safe to call from a polling loop that only wants to read the position.
    func playerHolding(_ key: String) -> AVPlayer? {
        slotKeys.firstIndex(of: key).map { players[$0] }
    }

    /// Holds or resumes the video on screen.
    func setPaused(key: String, paused: Bool) {
        guard let player = playerHolding(key) else { return }
        if paused { player.pause() } else { player.play() }
    }

    /// Plays `key` at `speed` times normal, for press-and-hold to skim through a video.
    func setSpeed(key: String, speed: Float) {
        guard let player = playerHolding(key) else { return }
        // `defaultRate` is what a later `play()` picks up; `rate` only moves a video already
        // running, so that setting a speed never starts a paused one.
        player.defaultRate = speed
        if player.rate > 0 { player.rate = speed }
    }

    /// Loads `url` on this conversation's player and starts it, pausing every other player.
    func play(key: String, url: String) {
        let index = slot(for: key)
        let player = players[index]
        if slotURLs[index] != url {
            load(url, into: index)
        }
        for (other, player) in players.enumerated() where other != index {
            player.pause()
        }
        visibleSlot = index
        applyWatermark()
        player.play()
    }

    /// Buffers the next conversation's first video without starting playback.
    func preload(key: String, url: String) {
        let index = slot(for: key)
        guard slotURLs[index] != url else { return }
        players[index].pause()
        load(url, into: index)
        applyWatermark()
    }

    /// Shows `handle`'s watermark over playback, or clears it when nil.
    ///
    /// Deliberately the same ``VideoFilterPipeline`` the export uses rather than a label drawn
    /// over the player in SwiftUI: a preview that is a re-implementation is a preview that can
    /// quietly stop matching what actually gets written to the file.
    func setWatermark(_ handle: String?) {
        watermarkHandle = handle
        applyWatermark()
    }

    func pauseAll() {
        players.forEach { $0.pause() }
    }

    func releaseAll() {
        players.forEach {
            $0.pause()
            $0.replaceCurrentItem(with: nil)
        }
        statusObservations = statusObservations.map { _ in nil }
    }

    // MARK: - Slots

    private func slot(for key: String) -> Int {
        if let existing = slotKeys.firstIndex(of: key) {
            clock += 1
            slotUsedAt[existing] = clock
            return existing
        }
        var lru = 0
        for index in 1..<Self.poolSize where slotUsedAt[index] < slotUsedAt[lru] { lru = index }
        slotKeys[lru] = key
        // Repurposed: whatever it held is no longer loaded for this key.
        slotURLs[lru] = nil
        clock += 1
        slotUsedAt[lru] = clock
        return lru
    }

    private func load(_ url: String, into index: Int) {
        guard let parsed = URL(string: url) else { return }
        // /admin/media-proxy is session-gated, and AVFoundation does its own HTTP — it never sees
        // the URLSession cookie jar, so the cookies have to be handed over with the asset.
        let asset = AVURLAsset(url: parsed, options: [AVURLAssetHTTPCookiesKey: cookies()])
        let item = AVPlayerItem(asset: asset)
        players[index].replaceCurrentItem(with: item)
        slotURLs[index] = url
        // A fresh item carries no composition, so the slot's record of one has to reset with it.
        slotWatermark[index] = nil
        observe(item, at: index, url: url)
    }

    private func observe(_ item: AVPlayerItem, at index: Int, url: String) {
        statusObservations[index] = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard item.status == .failed else { return }
            MainActor.assumeIsolated {
                guard let self else { return }
                // The HTTP status is not on the error itself; the item's own error log is the one
                // place AVFoundation writes it down, and it is what separates a dead session from
                // a CDN link that has simply expired.
                let status = item.errorLog()?.events.last?.errorStatusCode
                self.onError(url, status == 401)
            }
        }
    }

    private func restart(_ item: AVPlayerItem) {
        guard let player = players.first(where: { $0.currentItem === item }) else { return }
        item.seek(to: .zero) { finished in
            guard finished else { return }
            MainActor.assumeIsolated { if player.rate == 0 { player.play() } }
        }
    }

    /// Puts the watermark on the slot being watched and takes it off the other one. The
    /// pre-buffering player is off screen, and filtering frames nobody is looking at is a waste of
    /// battery.
    private func applyWatermark() {
        for (index, player) in players.enumerated() {
            let wanted = index == visibleSlot ? watermarkHandle : nil
            guard slotWatermark[index] != wanted else { continue }
            slotWatermark[index] = wanted
            guard let item = player.currentItem else { continue }
            guard let handle = wanted else {
                item.videoComposition = nil
                continue
            }
            Task { @MainActor in
                let composition = try? await VideoFilterPipeline.composition(
                    for: item.asset,
                    blur: nil,
                    watermark: handle
                )
                // Building it needs the asset's tracks, which for a streamed video means a round
                // trip — by the time it lands the operator may have swiped somewhere else.
                guard player.currentItem === item, self.slotWatermark[index] == handle else {
                    return
                }
                item.videoComposition = composition
            }
        }
    }
}

import AVFoundation
import SwiftUI

/// One customer, full screen. Horizontal swipes move between that customer's videos and never
/// delete anything — only the vertical swipe (handled by ``GalleryViewModel``) does.
struct ConversationPageView: View {

    let conversation: Conversation
    let isActivePage: Bool
    let isNextPage: Bool
    let playerManager: PlayerManager
    let model: GalleryViewModel

    @State private var mediaIndex: Int? = 0
    @State private var downloading = false
    @State private var sharingStory = false
    @State private var sharingReels = false
    @State private var captionForURL: String?

    /// Percentage of the running export, or nil while nothing is being processed. Only one action
    /// can run at a time, so a single holder covers all three buttons.
    @State private var exportProgress: Int?

    // Playback controls. Hidden until the screen is touched, because the video is the point.
    @State private var controlsShown = false
    /// Bumped by every interaction worth keeping the controls up for, so the hide timer restarts
    /// instead of a stale one firing early.
    @State private var controlsToken = 0
    @State private var positionMS: Int64 = 0
    @State private var durationMS: Int64 = 0
    @State private var scrubbing = false
    @State private var holding = false
    @State private var paused = false

    private let repository = ServiceLocator.repository!
    private let downloader = ServiceLocator.downloader!

    /// Exports share one cache directory and one progress readout, so they have to run one at a
    /// time — a second one starting would wipe the first one's working files out from under it.
    private var exporting: Bool { downloading || sharingStory || sharingReels }

    private var currentIndex: Int { mediaIndex ?? 0 }
    private var currentRawURL: String? {
        conversation.urls.indices.contains(currentIndex) ? conversation.urls[currentIndex] : nil
    }
    private var currentProxyURL: String? {
        currentRawURL.map { repository.proxyURL($0).absoluteString }
    }

    var body: some View {
        ZStack {
            Color.black

            videoPager
                // The gesture sits on the video area only. The controls are later siblings of
                // this ZStack, so a press on one of them hit-tests to the control and never
                // reaches here — which is what keeps holding a button from also running the video
                // fast, the exact bug the Android build had to fix.
                .simultaneousGesture(tapGesture)
                .simultaneousGesture(holdGesture)

            scrims
            centreIndicators
            header

            if controlsShown {
                VStack {
                    Spacer()
                    VideoScrubber(
                        positionMS: positionMS,
                        durationMS: durationMS,
                        onScrub: { target in
                            scrubbing = true
                            positionMS = target
                        },
                        onScrubFinished: {
                            let target = CMTime(value: positionMS, timescale: 1000)
                            playerManager.playerHolding(conversation.key)?.seek(to: target)
                            scrubbing = false
                            showControls()
                        }
                    )
                    .padding(.bottom, 92)
                }
            }

            bottomBar
        }
        .clipped()
        .task(id: TaskKey(active: isActivePage, url: currentProxyURL)) { await pollPosition() }
        .task(id: controlsToken) { await hideControlsLater() }
        .onChange(of: paused) { _, value in
            guard isActivePage else { return }
            playerManager.setPaused(key: conversation.key, paused: value)
        }
        // Holding the screen runs the video fast; letting go puts it back. Reset on leaving the
        // page too, or a video swiped away mid-hold would still be racing when it came back.
        .onChange(of: holding) { _, _ in applySpeed() }
        .onChange(of: isActivePage) { _, active in
            if !active { holding = false }
            applySpeed()
        }
        // A different video always starts playing, however the last one was left.
        .onChange(of: currentProxyURL) { _, _ in
            paused = false
            holding = false
        }
        .onChange(of: TaskKey(active: isActivePage, url: currentProxyURL)) { _, _ in loadVideo() }
        .onAppear { loadVideo() }
        .sheet(item: Binding(get: { captionForURL.map(CaptionTarget.init) },
                             set: { captionForURL = $0?.url })) { target in
            CaptionSheetView(
                conversation: conversation,
                rawMediaURL: target.url,
                onSessionLost: model.reportSessionLost,
                onToast: { model.toast = $0 }
            )
        }
    }

    // MARK: - Video

    private var videoPager: some View {
        ScrollView(.horizontal) {
            LazyHStack(spacing: 0) {
                ForEach(Array(conversation.urls.enumerated()), id: \.offset) { index, rawURL in
                    let proxyURL = repository.proxyURL(rawURL).absoluteString
                    ZStack {
                        Color.black
                        if model.expiredURLs.contains(proxyURL) {
                            Text(Strings.videoExpired)
                                .foregroundStyle(.white.opacity(0.7))
                        } else if isActivePage && currentIndex == index {
                            PlayerLayerView(player: playerManager.player(for: conversation.key))
                        } else {
                            ProgressView().tint(.white.opacity(0.35))
                        }
                    }
                    .containerRelativeFrame([.horizontal, .vertical])
                    .id(index)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $mediaIndex)
        .scrollIndicators(.hidden)
        .scrollDisabled(conversation.urls.count <= 1)
    }

    private var tapGesture: some Gesture {
        // A quick tap stops or restarts the video, and brings the controls up — stopping to look
        // at something is when the bar is wanted.
        TapGesture().onEnded {
            paused.toggle()
            showControls()
        }
    }

    private var holdGesture: some Gesture {
        // `maximumDistance` is the touch slop: a finger that travels before the press matures is
        // swiping between videos, not asking for fast playback.
        LongPressGesture(minimumDuration: Self.holdThreshold, maximumDistance: Self.touchSlop)
            .sequenced(before: DragGesture(minimumDistance: 0))
            .onChanged { value in
                if case .second(true, _) = value { holding = true }
            }
            .onEnded { _ in holding = false }
    }

    // MARK: - Chrome

    /// Scrims: white controls have to stay readable over a bright frame.
    private var scrims: some View {
        VStack {
            LinearGradient(
                colors: [.black.opacity(0.55), .clear],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 140)
            Spacer()
            LinearGradient(
                colors: [.clear, .black.opacity(0.65)],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 160)
        }
        .allowsHitTesting(false)
    }

    private var centreIndicators: some View {
        Group {
            if paused {
                Image(systemName: "play.fill")
                    .font(.system(size: 64))
                    .foregroundStyle(.white.opacity(0.75))
            } else if holding {
                SpeedBadge(speed: Int(Self.holdSpeed))
            }
        }
        .allowsHitTesting(false)
    }

    private var header: some View {
        VStack {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("@\(conversation.clientName)")
                        .font(.headline)
                        .foregroundStyle(.white)
                    // Per-video, so it follows horizontal swipes within the conversation.
                    if let sentAt = formatSentAt(conversation.sentAt(currentIndex)) {
                        Text(sentAt)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.75))
                    }
                }
                Spacer()
                filterToggles
            }
            .padding(16)
            Spacer()
        }
    }

    private var filterToggles: some View {
        HStack(spacing: 4) {
            // Up here rather than in the action row below, which is already tight on width.
            toggle(icon: model.blurFaces ? "drop.fill" : "drop", on: model.blurFaces) {
                model.setBlurFaces(!model.blurFaces)
                model.toast = model.blurFaces ? Strings.faceBlurOn : Strings.faceBlurOff
            }

            // Tap switches the filter; holding switches how hard it looks. Tucked behind a long
            // press because it is a knob to set once, not one to reach for daily.
            ZStack(alignment: .bottomTrailing) {
                Image(systemName: "car.fill")
                    .foregroundStyle(.white.opacity(model.blurPlates ? 1 : 0.45))
                    .padding(12)
                if model.fastPlates {
                    // A bolt on the corner for the quicker setting, nothing for the thorough one
                    // — so the icon says which of the two the long press left it on.
                    Image(systemName: "bolt.fill")
                        .font(.system(size: 9))
                        .foregroundStyle(.white.opacity(model.blurPlates ? 1 : 0.45))
                        .padding(.trailing, 4)
                        .padding(.bottom, 6)
                }
            }
            .contentShape(.rect)
            .onTapGesture {
                model.setBlurPlates(!model.blurPlates)
                model.toast = model.blurPlates ? Strings.plateBlurOn : Strings.plateBlurOff
            }
            .onLongPressGesture(minimumDuration: 0.5) {
                model.setFastPlates(!model.fastPlates)
                model.toast = model.fastPlates ? Strings.platesFast : Strings.platesThorough
            }

            toggle(icon: "signature", on: model.watermark) {
                model.setWatermark(!model.watermark)
                model.toast = model.watermark ? Strings.watermarkOn : Strings.watermarkOff
            }

            // How many customers are still waiting. The dots below already say how many videos
            // this one has, so the per-video position is not repeated here.
            if model.remaining > 0 {
                Text("\(model.remaining)")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .padding(.leading, 4)
            }
        }
    }

    private func toggle(icon: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon)
                .foregroundStyle(.white.opacity(on ? 1 : 0.45))
                .padding(12)
        }
        .buttonStyle(.plain)
    }

    private var bottomBar: some View {
        VStack {
            Spacer()
            HStack {
                if conversation.urls.count > 1 {
                    HStack(spacing: 6) {
                        ForEach(conversation.urls.indices, id: \.self) { index in
                            Circle()
                                .fill(.white.opacity(currentIndex == index ? 1 : 0.4))
                                .frame(width: currentIndex == index ? 8 : 6)
                        }
                    }
                }
                Spacer()
                actions
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 24)
        }
    }

    private var actions: some View {
        HStack(spacing: 2) {
            ActionButton(
                icon: "arrow.down.circle",
                label: percent(downloading, downloading ? Strings.downloading : Strings.download),
                busy: downloading,
                enabled: currentRawURL != nil && !exporting,
                action: download
            )
            // Stories carry no caption, so this is a straight hand-off of the video.
            ActionButton(
                icon: "plus.circle",
                label: percent(sharingStory, Strings.story),
                busy: sharingStory,
                enabled: currentRawURL != nil && !exporting,
                action: shareToStory
            )
            // Reels takes the video from the photo library; the caption can only ride the
            // clipboard, so it is generated first and the operator pastes it in the composer.
            ActionButton(
                icon: "film",
                label: percent(sharingReels, Strings.reels),
                busy: sharingReels,
                enabled: currentRawURL != nil && !exporting,
                action: shareToReels
            )
            ActionButton(
                icon: "sparkles",
                label: Strings.caption,
                busy: false,
                enabled: currentRawURL != nil && !exporting,
                action: { captionForURL = currentRawURL }
            )
        }
    }

    /// The export's percentage while `busy`, otherwise `fallback`. Blurring a video takes long
    /// enough that a spinner alone leaves the operator wondering whether it is stuck. The progress
    /// holder is shared, so the flag keeps the count on the one button that is actually working.
    private func percent(_ busy: Bool, _ fallback: String) -> String {
        if busy, let exportProgress { return Strings.progress(exportProgress) }
        return fallback
    }

    // MARK: - Playback plumbing

    /// Playback follows the settled vertical page; the neighbouring page only pre-buffers.
    private func loadVideo() {
        guard let proxyURL = currentProxyURL else { return }
        if isActivePage {
            playerManager.play(key: conversation.key, url: proxyURL)
        } else if isNextPage {
            playerManager.preload(key: conversation.key, url: proxyURL)
        }
    }

    private func applySpeed() {
        playerManager.setSpeed(
            key: conversation.key,
            speed: holding && isActivePage ? Self.holdSpeed : 1
        )
    }

    /// The player has no position callback we want here, so it gets read on a timer while this
    /// page is the one on screen. Paused while scrubbing, or the thumb would fight the poll for
    /// the same value.
    private func pollPosition() async {
        while isActivePage && !Task.isCancelled {
            if let player = playerManager.playerHolding(conversation.key) {
                if !scrubbing {
                    positionMS = Int64(CMTimeGetSeconds(player.currentTime()) * 1000)
                }
                let duration = player.currentItem?.duration ?? .indefinite
                durationMS = duration.isNumeric ? Int64(CMTimeGetSeconds(duration) * 1000) : 0
            }
            try? await Task.sleep(for: .milliseconds(Self.positionPollMS))
        }
    }

    /// Brings the controls up and restarts the countdown that takes them away again.
    private func showControls() {
        controlsShown = true
        controlsToken += 1
    }

    /// Anything the operator does keeps the controls up; going quiet puts them away again. A
    /// paused video is not "going quiet" — the bar is the reason it was paused.
    private func hideControlsLater() async {
        guard controlsShown, !scrubbing, !paused else { return }
        try? await Task.sleep(for: .seconds(Self.controlsLinger))
        guard !Task.isCancelled, !scrubbing, !paused else { return }
        controlsShown = false
    }

    // MARK: - Actions

    private func download() {
        guard let rawURL = currentRawURL else { return }
        downloading = true
        Task {
            defer {
                downloading = false
                exportProgress = nil
            }
            do {
                let saved = try await downloader.saveToPhotos(
                    rawURL: rawURL,
                    clientName: conversation.clientName,
                    options: model.exportOptions()
                ) { exportProgress = $0 }
                model.toast = saved ? Strings.downloadDone : Strings.downloadFailed
            } catch is UnauthorizedError {
                model.reportSessionLost()
            } catch is VideoExporter.ExportFailedError {
                model.toast = Strings.exportFailed
            } catch is PhotoLibrarySaver.DeniedError {
                model.toast = Strings.photosDenied
            } catch {
                model.toast = Strings.downloadFailed
            }
        }
    }

    private func shareToStory() {
        guard let rawURL = currentRawURL else { return }
        guard InstagramSharing.isInstalled else {
            model.toast = Strings.instagramMissing
            return
        }
        sharingStory = true
        Task {
            defer {
                sharingStory = false
                exportProgress = nil
            }
            do {
                let file = try await downloader.downloadForShare(
                    rawURL: rawURL,
                    clientName: conversation.clientName,
                    options: model.exportOptions()
                ) { exportProgress = $0 }
                if !InstagramSharing.openStoryComposer(video: file) {
                    model.toast = Strings.shareFailed
                }
            } catch is UnauthorizedError {
                model.reportSessionLost()
            } catch is VideoExporter.ExportFailedError {
                model.toast = Strings.exportFailed
            } catch {
                model.toast = Strings.shareFailed
            }
        }
    }

    private func shareToReels() {
        guard let rawURL = currentRawURL else { return }
        guard InstagramSharing.isInstalled else {
            model.toast = Strings.instagramMissing
            return
        }
        sharingReels = true
        Task {
            defer {
                sharingReels = false
                exportProgress = nil
            }
            do {
                // Saved to the photo library rather than handed over directly, because Reels can
                // only take a video the operator picks there.
                let saved = try await downloader.saveToPhotos(
                    rawURL: rawURL,
                    clientName: conversation.clientName,
                    options: model.exportOptions()
                ) { exportProgress = $0 }
                exportProgress = nil
                guard saved else {
                    model.toast = Strings.downloadFailed
                    return
                }
                let caption = try? await repository.generateCaption(
                    salonId: conversation.salonId,
                    clientId: conversation.clientId,
                    rawMediaURL: rawURL
                )
                let hasCaption = !(caption ?? "").isEmpty
                if let caption, hasCaption { InstagramSharing.copyCaption(caption) }
                model.toast = hasCaption ? Strings.reelsReady : Strings.reelsReadyNoCaption
                _ = InstagramSharing.openInstagram()
            } catch is UnauthorizedError {
                model.reportSessionLost()
            } catch is VideoExporter.ExportFailedError {
                model.toast = Strings.exportFailed
            } catch is PhotoLibrarySaver.DeniedError {
                model.toast = Strings.photosDenied
            } catch {
                model.toast = Strings.shareFailed
            }
        }
    }

    /// How long the controls stay up once nothing is happening.
    private static let controlsLinger: Double = 3

    /// How often the player is asked where it has got to.
    private static let positionPollMS = 120

    /// Past this, a press is a hold rather than a tap.
    private static let holdThreshold: Double = 0.25

    /// How far a finger may travel before a press is read as a swipe instead.
    private static let touchSlop: CGFloat = 10

    /// How much faster a held-down video runs.
    private static let holdSpeed: Float = 3
}

/// Groups the two things that together mean "a different video is on screen now", so the effects
/// keyed on it restart exactly when they should.
private struct TaskKey: Equatable {
    let active: Bool
    let url: String?
}

/// `sheet(item:)` wants something identifiable; the url is the identity.
private struct CaptionTarget: Identifiable {
    let url: String
    var id: String { url }
}

/// One compact action in the bottom bar. Four of these have to share the width, so the label sits
/// under the icon and the busy state replaces the icon rather than adding to the row.
private struct ActionButton: View {

    let icon: String
    let label: String
    let busy: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                if busy {
                    ProgressView()
                        .tint(.white)
                        .frame(width: 22, height: 22)
                } else {
                    Image(systemName: icon)
                        .font(.system(size: 20))
                        .foregroundStyle(.white)
                        .frame(width: 22, height: 22)
                }
                Text(label)
                    .font(.caption2)
                    .foregroundStyle(.white)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
        }
        .buttonStyle(.plain)
        .disabled(!enabled || busy)
        .opacity(enabled ? 1 : 0.4)
    }
}

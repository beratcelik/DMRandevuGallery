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
    /// Non-nil only while the models are coming down, which is a one-off on first use.
    @State private var censorDownload: Int?
    /// True only while the server is being asked for a fresh link.
    @State private var refreshing = false
    /// Where the video was when the mark button went down, or nil when nothing is being marked.
    @State private var markingFrom: Int64?
    /// The censor tone, played over the video while a marked stretch goes past.
    @State private var beeps = BeepPlayer()

    /// Counts out the press before fast playback starts, and is cancelled if the finger lifts or
    /// wanders first.
    @State private var holdTimer: Task<Void, Never>?
    @State private var paused = false

    /// The video runs edge to edge; the controls over it must not.
    @Environment(\.chromeInsets) private var chromeInsets

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
        currentRawURL.flatMap { repository.proxyURL($0)?.absoluteString }
    }

    var body: some View {
        ZStack {
            Color.black

            videoPager
                // The gesture sits on the video area only. The controls are later siblings of
                // this ZStack, so a press on one of them hit-tests to the control and never
                // reaches here — which is what keeps holding a button from also running the video
                // fast, the exact bug the Android build had to fix.
                // A plain tap composes with the scroll views; a DragGesture does not. The first
                // version paired the long press with `DragGesture(minimumDistance: 0)` to learn
                // when the finger lifted, and that drag quietly claimed every vertical swipe —
                // the feed stopped scrolling altogether. `onPressingChanged` reports the lift
                // without a drag in the way.
                // `gesture`, not `simultaneousGesture`: a simultaneous tap here recognised at the
                // same time as the header buttons above it and swallowed their taps, so pressing a
                // filter toggle only ever paused the video. A plain gesture yields to whatever is
                // nearer the finger and still composes with the scroll views.
                .gesture(tapGesture)
                .onLongPressGesture(
                    // Deliberately never reached. `perform` turned out to fire on *release*, not
                    // when the press matured, so the video only started running fast once the
                    // finger came off — and nothing was left to stop it again. Holding is timed
                    // here instead, off the press-and-release signal, which is honest about when
                    // the finger is actually down.
                    minimumDuration: .infinity,
                    // The touch slop: a finger that travels before the press matures is swiping
                    // between videos, not asking for fast playback. Exceeding it fails the
                    // gesture, which arrives here as the press ending.
                    maximumDistance: Self.touchSlop,
                    perform: {},
                    onPressingChanged: { pressing in
                        holdTimer?.cancel()
                        guard pressing else {
                            holding = false
                            return
                        }
                        holdTimer = Task {
                            try? await Task.sleep(for: .seconds(Self.holdThreshold))
                            guard !Task.isCancelled else { return }
                            holding = true
                        }
                    }
                )

            scrims
            centreIndicators
            header

            // The scrubber stays up while the filter is on. Marking is aiming at a moment, and
            // a bar that hides itself three seconds in is no use for that.
            if controlsShown || model.censorAudio {
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
                        },
                        marks: marks
                    )
                    .padding(.bottom, 92 + chromeInsets.bottom)
                }
            }

            if model.censorAudio {
                VStack {
                    Spacer()
                    MarkButton(
                        marking: markingFrom != nil,
                        onPress: {
                            // Where the video is now, not where the finger went down on screen.
                            markingFrom = positionMS
                            showControls()
                        },
                        onRelease: {
                            let from = markingFrom
                            markingFrom = nil
                            if let from, positionMS > from {
                                model.addMark(
                                    conversationKey: conversation.key,
                                    mediaIndex: currentIndex,
                                    window: CensorWindow(
                                        startUs: from * 1_000, endUs: positionMS * 1_000
                                    )
                                )
                            }
                            showControls()
                        },
                        onRemove: {
                            model.removeMark(
                                conversationKey: conversation.key,
                                mediaIndex: currentIndex,
                                atUs: positionMS * 1_000
                            )
                            showControls()
                        }
                    )
                    .padding(.bottom, 140 + chromeInsets.bottom)
                }
            }

            bottomBar
        }
        .clipped()
        .task(id: TaskKey(active: isActivePage, url: currentProxyURL)) { await pollPosition() }
        .task(id: controlsToken) { await hideControlsLater() }
        .onChange(of: insideMark) { _, inside in
            playerManager.setDucked(key: conversation.key, ducked: inside)
            if inside { beeps.start() } else { beeps.stop() }
        }
        .onDisappear { beeps.stop() }
        .onChange(of: paused) { _, value in
            guard isActivePage else { return }
            playerManager.setPaused(key: conversation.key, paused: value)
        }
        // Holding the screen runs the video fast; letting go puts it back. Reset on leaving the
        // page too, or a video swiped away mid-hold would still be racing when it came back.
        .onChange(of: holding) { _, _ in applySpeed() }
        .onChange(of: isActivePage) { _, active in
            if !active {
                holdTimer?.cancel()
                holding = false
            }
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

    /// Every stretch marked by hand on the video on screen.
    private var marks: [CensorWindow] {
        // markRevision is read so the view redraws when a mark is added or taken off; the marks
        // themselves live in preferences rather than in state.
        _ = model.markRevision
        return model.manualMarks(
            conversationKey: conversation.key, mediaIndex: currentIndex
        )
    }

    /// Whether the playhead is inside something marked — including the mark being made right now,
    /// which is the moment the operator most wants to hear.
    private var insideMark: Bool {
        guard model.censorAudio, isActivePage, !paused else { return false }
        if markingFrom != nil { return true }
        let now = positionMS * 1_000
        return marks.contains { now >= $0.startUs && now <= $0.endUs }
    }

    private var videoPager: some View {
        ScrollView(.horizontal) {
            LazyHStack(spacing: 0) {
                ForEach(Array(conversation.urls.enumerated()), id: \.offset) { index, rawURL in
                    // Only an identity for the expired-video marker, so the raw url does just as
                    // well when the server address will not form one.
                    let proxyURL = repository.proxyURL(rawURL)?.absoluteString ?? rawURL
                    ZStack {
                        Color.black
                        switch model.failures[proxyURL] {
                        case .linkDead:
                            // The link is dead, so trying it again would fail the same way — but
                            // the server re-signs these on request, so asking for the
                            // conversation again gets one that works. That is what this retry
                            // does, unlike the transient one below.
                            PlaybackRetry(message: Strings.videoExpired, busy: refreshing) {
                                refreshing = true
                                Task {
                                    let renewed = await model.refreshLinks(for: conversation)
                                    refreshing = false
                                    if !renewed { model.toast = Strings.videoRefreshFailed }
                                }
                            }

                        case .transient, .sessionLost:
                            // Nothing about this one says the video itself is bad, so it keeps
                            // the offer of another go instead of being written off for the rest
                            // of the session.
                            PlaybackRetry(message: Strings.videoFailed) {
                                model.clearFailure(proxyURL)
                                playerManager.play(key: conversation.key, url: proxyURL)
                            }

                        case .none:
                            if isActivePage && currentIndex == index {
                                PlayerLayerView(
                                    player: playerManager.player(for: conversation.key)
                                )
                            } else {
                                ProgressView().tint(.white.opacity(0.35))
                            }
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
                    .accessibilityIdentifier("pausedIndicator")
            } else if holding {
                SpeedBadge(speed: Int(Self.holdSpeed))
                    .accessibilityIdentifier("speedBadge")
            }
        }
        .allowsHitTesting(false)
    }

    private var header: some View {
        VStack {
            HStack(alignment: .center, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    // A customer handle can be longer than the space beside three toggles. Left
                    // to itself it pushed them off the row entirely — the face filter disappeared
                    // — so the name is the part that gives way, and says so with an ellipsis.
                    Text("@\(conversation.clientName)")
                        .font(.headline)
                        .foregroundStyle(.white)
                        .lineLimit(1)
                        .truncationMode(.tail)
                        .accessibilityIdentifier("customerName")
                    // Per-video, so it follows horizontal swipes within the conversation.
                    if let sentAt = formatSentAt(conversation.sentAt(currentIndex)) {
                        Text(sentAt)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.75))
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                filterToggles
                    // The toggles are the fixed furniture of this row; whatever is left over is
                    // the name's.
                    .fixedSize()
                    .layoutPriority(1)
            }
            .padding(16)
            .padding(.top, chromeInsets.top)
            Spacer()
        }
    }

    /// Switches the censor on, downloading the speech models the first time.
    ///
    /// Only switched on once every model is present and verified: a half-downloaded one would
    /// fail every export rather than censor anything.
    private func toggleCensor() {
        if model.censorAudio {
            model.setCensorAudio(false)
            model.toast = Strings.censorAudioOff
            return
        }
        censorDownload = 0
        Task {
            defer { censorDownload = nil }
            do {
                try await ServiceLocator.censorModels.ensureAvailable { fraction in
                    Task { @MainActor in censorDownload = Int(fraction * 100) }
                }
                model.setCensorAudio(true)
                model.toast = Strings.censorAudioOn
            } catch is CancellationError {
                // The page went away; nothing to report.
            } catch {
                model.toast = Strings.censorModelsFailed
            }
        }
    }

    private var filterToggles: some View {
        HStack(spacing: 4) {
            // Up here rather than in the action row below, which is already tight on width.
            // A face rather than the blur droplet SF Symbols offers: this sits next to a car for
            // plates, and the pair reads at a glance as "people / vehicles".
            toggle(
                icon: model.blurFaces ? "face.smiling.inverse" : "face.smiling",
                on: model.blurFaces
            ) {
                model.setBlurFaces(!model.blurFaces)
                model.toast = model.blurFaces ? Strings.faceBlurOn : Strings.faceBlurOff
            }
            .accessibilityIdentifier("toggleFaces")

            // Tap switches the filter; holding switches how hard it looks. Tucked behind a long
            // press because it is a knob to set once, not one to reach for daily.
            ZStack(alignment: .bottomTrailing) {
                Image(systemName: "car.fill")
                    .font(.system(size: Self.toggleGlyph))
                    .foregroundStyle(.white.opacity(model.blurPlates ? 1 : 0.45))
                    .frame(width: Self.toggleTouch, height: Self.toggleTouch)
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
            .accessibilityIdentifier("togglePlates")
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
            .accessibilityIdentifier("toggleWatermark")

            if let percent = censorDownload {
                Text("%\(percent)")
                    .font(.caption2)
                    .foregroundStyle(.white)
                    .frame(width: Self.toggleTouch, height: Self.toggleTouch)
                    .accessibilityIdentifier("censorDownload")
            } else {
                // Tap switches the filter; holding switches whether it listens to the video or
                // only beeps what was marked by hand — the same shape as the plate toggle,
                // because it is the same kind of choice: a knob to set, not one to reach for.
                ZStack(alignment: .bottomTrailing) {
                    toggle(
                        icon: model.censorAudio ? "speaker.slash.fill" : "speaker.wave.2",
                        on: model.censorAudio,
                        action: toggleCensor
                    )
                    if model.censorAudio && model.censorByHand {
                        // A hand on the corner for the by-hand setting, nothing for automatic —
                        // so the icon says which of the two the long press left it on.
                        Image(systemName: "hand.tap.fill")
                            .font(.system(size: 11))
                            .foregroundStyle(.white)
                            .padding(.trailing, 4)
                            .padding(.bottom, 6)
                            .allowsHitTesting(false)
                    }
                }
                .onLongPressGesture(minimumDuration: Self.modeHold) {
                    model.setCensorByHand(!model.censorByHand)
                    model.toast = model.censorByHand ? Strings.censorByHand : Strings.censorAuto
                }
                .accessibilityIdentifier("toggleCensor")
            }

            // How many customers are still waiting. The dots below already say how many videos
            // this one has, so the per-video position is not repeated here.
            if model.remaining > 0 {
                Text("\(model.remaining)")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .padding(.leading, 4)
                    .accessibilityIdentifier("remainingCount")
            }
        }
    }

    /// One filter toggle.
    ///
    /// A tap gesture on a plain image rather than a `Button`: over a full-screen video that has
    /// its own tap handling, SwiftUI's arbitration between a button and the gesture underneath is
    /// not reliably won by the button, and the leftmost toggle lost every tap to the video. This
    /// is the same construct the plate toggle uses, and it wins consistently.
    private func toggle(icon: String, on: Bool, action: @escaping () -> Void) -> some View {
        Image(systemName: icon)
            // Left to themselves the glyphs come out at wildly different widths; a fixed size and
            // a square frame keep the row evenly spaced whatever symbols it holds.
            .font(.system(size: Self.toggleGlyph))
            .foregroundStyle(.white.opacity(on ? 1 : 0.45))
            .frame(width: Self.toggleTouch, height: Self.toggleTouch)
            .contentShape(.rect)
            .onTapGesture(perform: action)
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
                    .accessibilityIdentifier("mediaDots")
                    .accessibilityValue("\(conversation.urls.count)")
                }
                Spacer()
                actions
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 24)
            .padding(.bottom, chromeInsets.bottom)
        }
    }

    private var actions: some View {
        HStack(spacing: 2) {
            ActionButton(
                identifier: "actionDownload",
                icon: "arrow.down.circle",
                label: percent(downloading, downloading ? Strings.downloading : Strings.download),
                busy: downloading,
                enabled: currentRawURL != nil && !exporting,
                action: download
            )
            // Stories carry no caption, so this is a straight hand-off of the video.
            ActionButton(
                identifier: "actionStory",
                icon: "plus.circle",
                label: percent(sharingStory, Strings.story),
                busy: sharingStory,
                enabled: currentRawURL != nil && !exporting,
                action: shareToStory
            )
            // Reels takes the video from the photo library; the caption can only ride the
            // clipboard, so it is generated first and the operator pastes it in the composer.
            ActionButton(
                identifier: "actionReels",
                icon: "film",
                label: percent(sharingReels, Strings.reels),
                busy: sharingReels,
                enabled: currentRawURL != nil && !exporting,
                action: shareToReels
            )
            ActionButton(
                identifier: "actionCaption",
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
                // Both of these are routinely not real instants yet — a player answers with an
                // invalid time until its item is ready. Holding the last known value beats
                // flashing a zero into the bar every time a video is swapped in.
                if !scrubbing, let position = player.currentTime().milliseconds {
                    positionMS = position
                }
                durationMS = player.currentItem?.duration.milliseconds ?? 0
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

    /// The glyph size shared by every filter toggle, and the square each one sits in.
    private static let toggleGlyph: CGFloat = 20
    /// Long enough not to fire on a tap that switches the filter, short enough to find.
    private static let modeHold = 0.5

    private static let toggleTouch: CGFloat = 40

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

    let identifier: String
    let icon: String
    let label: String
    let busy: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        // Deliberately always enabled, with the action refusing instead.
        //
        // A disabled SwiftUI button does not swallow the tap — it lets it through to whatever is
        // behind, which here is the video and its tap-to-pause. So every tap on İndir while an
        // export was already running paused the video instead of doing nothing, and a censored
        // export runs for a minute and a half.
        Button {
            guard enabled, !busy else { return }
            action()
        } label: {
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
        // The shape, not the glyph: without it the gaps between icon and label are holes the tap
        // falls through.
        .contentShape(.rect)
        .opacity(enabled ? 1 : 0.4)
        .accessibilityIdentifier(identifier)
    }
}

/// What a video that would not play offers instead.
private struct PlaybackRetry: View {

    let message: String
    var busy = false
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            Text(message)
                .foregroundStyle(.white.opacity(0.7))
            if busy {
                ProgressView().tint(.white)
            } else {
                Button(action: onRetry) {
                    Text(Strings.videoRetry).foregroundStyle(.white)
                }
                .accessibilityIdentifier("videoRetry")
            }
        }
    }
}

/// Hold while the swearing plays; let go when it stops.
///
/// The obvious alternative was dragging a range along the scrubber, which means finding a moment
/// you have already heard go past. Holding is how the operator experiences the problem: the word
/// arrives, the thumb goes down, the word ends, the thumb comes up.
///
/// Deliberately not the video surface, which already means run-at-triple-speed while held.
private struct MarkButton: View {

    let marking: Bool
    let onPress: () -> Void
    let onRelease: () -> Void
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text(marking ? Strings.markHolding : Strings.markHint)
                .font(.callout.weight(.medium))
                .foregroundStyle(.white)
                .padding(.horizontal, 18)
                .padding(.vertical, 10)
                .background(
                    marking ? VideoScrubber.markColour : Color.black.opacity(0.55),
                    in: Capsule()
                )
                // Whether the finger lifted or slid off, the mark ends here — one left open would
                // keep growing for the rest of the video.
                .onLongPressGesture(
                    minimumDuration: .infinity,
                    perform: {},
                    onPressingChanged: { pressing in
                        if pressing { onPress() } else { onRelease() }
                    }
                )
                .accessibilityIdentifier("markButton")

            Button(action: onRemove) {
                Text(Strings.markRemove).foregroundStyle(.white.opacity(0.8))
            }
            .accessibilityIdentifier("markRemove")
        }
    }
}

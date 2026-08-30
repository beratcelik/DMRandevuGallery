import SwiftUI

struct GalleryView: View {

    let igId: String
    let onSessionLost: () -> Void

    @State private var model: GalleryViewModel
    @State private var playerManager: PlayerManager?
    @State private var currentKey: String?

    /// Read once the window exists; the controls sit inside these while the video ignores them.
    @State private var insets = EdgeInsets()

    @Environment(\.scenePhase) private var scenePhase

    init(igId: String, onSessionLost: @escaping () -> Void) {
        self.igId = igId
        self.onSessionLost = onSessionLost
        _model = State(initialValue: GalleryViewModel(igId: igId))
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if model.loading && model.items.isEmpty {
                ProgressView().tint(.white)
            } else if model.items.isEmpty {
                Text(Strings.emptyGallery)
                    .foregroundStyle(.white)
            } else if let playerManager {
                pager(playerManager)
            }

            ToastView(message: $model.toast)
                .padding(.bottom, insets.bottom)
        }
        .ignoresSafeArea()
        .environment(\.chromeInsets, insets)
        .task {
            insets = ScreenInsets.current
            if playerManager == nil { playerManager = makePlayerManager() }
            await model.loadMore(initial: true)
        }
        .onChange(of: model.items.first?.key) { _, first in
            // The very first page has arrived; put the pager on it so the settle handler has a
            // key to compare against.
            if currentKey == nil { currentKey = first }
        }
        .onChange(of: model.sessionLost) { _, lost in
            guard lost else { return }
            ServiceLocator.repository.clearSession()
            playerManager?.releaseAll()
            onSessionLost()
        }
        .onChange(of: model.watermark) { _, _ in
            // Preview the watermark on the players themselves, so what plays here is what gets
            // exported.
            playerManager?.setWatermark(model.watermarkHandle())
        }
        .onChange(of: scenePhase) { _, phase in
            // Leaving the app settles the pending deletion — otherwise a swipe followed by a home
            // press would silently keep the conversation the operator meant to discard.
            guard phase != .active else { return }
            model.commitPendingNow()
            playerManager?.pauseAll()
        }
        .onDisappear { playerManager?.releaseAll() }
    }

    private func pager(_ playerManager: PlayerManager) -> some View {
        ScrollView(.vertical) {
            LazyVStack(spacing: 0) {
                ForEach(Array(model.items.enumerated()), id: \.element.key) { index, conversation in
                    ConversationPageView(
                        conversation: conversation,
                        isActivePage: currentKey == conversation.key,
                        isNextPage: nextKey == conversation.key,
                        playerManager: playerManager,
                        model: model
                    )
                    .containerRelativeFrame([.horizontal, .vertical])
                    .id(conversation.key)
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $currentKey)
        .scrollIndicators(.hidden)
        // Only a settled page counts. Reacting to the position as it changes would read a swipe
        // the operator dragged halfway and let go of as a page they left, and delete a customer
        // they never meant to pass.
        .onScrollPhaseChange { _, phase in
            guard phase == .idle, let currentKey else { return }
            model.onPageSettled(key: currentKey)
        }
    }

    /// The conversation after the one on screen — the one worth pre-buffering.
    private var nextKey: String? {
        guard let currentKey,
              let index = model.items.firstIndex(where: { $0.key == currentKey }),
              model.items.indices.contains(index + 1) else { return nil }
        return model.items[index + 1].key
    }

    private func makePlayerManager() -> PlayerManager {
        let manager = PlayerManager(
            cookies: { ServiceLocator.repository.sessionCookies() },
            onError: { url, failure in
                switch failure {
                case .sessionLost: model.reportSessionLost()
                case .linkDead, .transient: model.report(failure, for: url)
                }
            }
        )
        manager.setWatermark(model.watermarkHandle())
        return manager
    }
}

/// A message over the video that takes itself away again — the Android build's toast, which iOS
/// has no equivalent of.
struct ToastView: View {

    @Binding var message: String?

    var body: some View {
        VStack {
            Spacer()
            if let message {
                Text(message)
                    .font(.callout)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
                    .background(.black.opacity(0.82), in: .rect(cornerRadius: 12))
                    .padding(.horizontal, 32)
                    .padding(.bottom, 120)
                    .transition(.opacity)
                    .task(id: message) {
                        try? await Task.sleep(for: .seconds(3))
                        guard !Task.isCancelled else { return }
                        self.message = nil
                    }
            }
        }
        .animation(.easeInOut(duration: 0.2), value: message)
        .allowsHitTesting(false)
    }
}

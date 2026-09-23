import SwiftUI

struct GalleryView: View {

    let igId: String
    let onSessionLost: () -> Void

    @State private var model: GalleryViewModel
    @State private var playerManager: PlayerManager?
    /// Ekrandaki SAYFA kimliği ("konuşma#sıra").
    ///
    /// Eskiden konuşma anahtarıydı: akış düzleşti ve bir sayfa artık bir VİDEO,
    /// çünkü yatay eksen karara ayrıldı (sağa at = ihbar, sola at = ihlal değil).
    @State private var currentPageID: String?

    /// Read once the window exists; the controls sit inside these while the video ignores them.
    @State private var insets = EdgeInsets()

    /// Tanıtım kurulum başına bir kez. Karar AÇILIŞTA bir kez okunuyor: her yeniden
    /// çizimde ayarları okumak, "Anladım"a basıldıktan sonra aynı karede bayrağın
    /// yazılmasıyla okunması arasında yarış açardı.
    @State private var showTour = ServiceLocator.settings.tourShownBuild != currentBuildTag()
    private let buildTag = currentBuildTag()

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

            // ─── AKIŞ TANITIMI ──────────────────────────────────────────────
            //
            // BURADA, sayfanın içinde değil: sayfalar ForEach içinde her biri için
            // yeniden kuruluyor ve orada duran bir tanıtım her videoda bir kez
            // çizilirdi. ZStack'in SON çocuğu olduğu için akışın tamamının üstünde.
            //
            // AKIŞ GELDİKTEN SONRA: boş bir ekranın üstünde hareketleri anlatmak,
            // anlatılan şeyin arkasında hiçbir şey yokken anlatmak olurdu. Bir de
            // ihbar hesabı olup olmadığı ancak hesap yüklendikten sonra belli.
            if showTour && !model.items.isEmpty {
                OrientationOverlay(ihbarEnabled: model.ihbarAvailable) {
                    ServiceLocator.settings.tourShownBuild = buildTag
                    showTour = false
                }
            }
        }
        .ignoresSafeArea()
        .environment(\.chromeInsets, insets)
        .task {
            insets = ScreenInsets.current
            if playerManager == nil { playerManager = makePlayerManager() }
            await model.loadMore(initial: true)
        }
        .onChange(of: model.feed.first?.id) { _, first in
            // The very first page has arrived; put the pager on it so the settle handler has a
            // key to compare against.
            if currentPageID == nil { currentPageID = first }
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
            // ─── CAPTION'I GERİ KOY ─────────────────────────────────────────────
            //
            // Reels'e devredilen caption Instagram'a kadar gidemiyor: videoyu besteciye
            // taşıyan şey panonun kendisi ve Instagram onu tükettikten sonra panoda ne
            // kaldığı bizim elimizde değil. Öğenin içine koymak da, devirden sonra
            // gecikmeli yazmak da denendi; ikisi de yetmedi.
            //
            // Buradan sonrası garanti: uygulama ÖNE geldiğinde panoya yazmak her zaman
            // çalışıyor. Operatör Reels'te yapıştıramazsa uygulamaya bir saniyeliğine
            // dönüyor, caption panoya geri konuyor ve söyleniyor.
            if phase == .active {
                if InstagramSharing.restoreCaptionOnReturn() != nil {
                    model.toast = Strings.captionRestored
                }
                return
            }

            // Leaving the app settles the pending deletion — otherwise a swipe followed by a home
            // press would silently keep the conversation the operator meant to discard.
            model.commitPendingNow()
            // Bekleyen kaydırma kararları da anında gönderiliyor: kaydırıp ana
            // ekrana çıkmak kararı sessizce yutmamalı.
            model.commitDecisionsNow()
            playerManager?.pauseAll()
        }
        .onDisappear { playerManager?.releaseAll() }
    }

    private func pager(_ playerManager: PlayerManager) -> some View {
        ScrollView(.vertical) {
            LazyVStack(spacing: 0) {
                ForEach(model.feed) { page in
                    if let conversation = model.items.first(
                        where: { $0.key == page.conversationKey }
                    ) {
                        VideoPageView(
                            conversation: conversation,
                            page: page,
                            isActivePage: currentPageID == page.id,
                            isNextPage: nextPageID == page.id,
                            playerManager: playerManager,
                            model: model,
                            onAdvance: { advance(from: page) }
                        )
                        .containerRelativeFrame([.horizontal, .vertical])
                        .id(page.id)
                    }
                }

                // SON VİDEODAN SONRA "hepsini gördün" sayfası. Olmadığında akış son videoda
                // duruyordu ve operatör bitip bitmediğini anlamak için boşuna kaydırıyordu.
                EndOfFeedPage(
                    isActivePage: currentPageID == Self.endPageID,
                    stillLoading: model.loading || model.hasMore,
                    playerManager: playerManager,
                    onNeedMore: { await model.loadMore() }
                )
                .containerRelativeFrame([.horizontal, .vertical])
                .id(Self.endPageID)
            }
            .scrollTargetLayout()
        }
        // Yeni müşteriler son sayfadayken gelirse ilk yeni videoya geçiliyor. Konum kimlikle
        // tutulduğu için, yoksa görünüm "son" sayfayı izler ve yeni videoların hepsinin üstünden
        // atlardı. Android bunu son sayfanın anahtarını sırası yaparak çözüyor.
        .onChange(of: model.feed.count) { oldCount, newCount in
            guard currentPageID == Self.endPageID, newCount > oldCount,
                  model.feed.indices.contains(oldCount) else { return }
            currentPageID = model.feed[oldCount].id
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $currentPageID)
        .scrollIndicators(.hidden)
        // Only a settled page counts. Reacting to the position as it changes would read a swipe
        // the operator dragged halfway and let go of as a page they left, and delete a customer
        // they never meant to pass.
        .onScrollPhaseChange { _, phase in
            guard phase == .idle, let currentPageID else { return }
            model.onPageSettled(pageID: currentPageID)
        }
    }

    /// Ekrandakinden sonraki SAYFA — ön belleğe alınmaya değer olan.
    ///
    /// Düz akışta bu çoğu zaman AYNI müşterinin bir sonraki videosu; eskiden
    /// her zaman bir sonraki müşteriydi.
    private var nextPageID: String? {
        guard let currentPageID,
              let index = model.feed.firstIndex(where: { $0.id == currentPageID }),
              model.feed.indices.contains(index + 1) else { return nil }
        return model.feed[index + 1].id
    }

    /// Karar verildikten sonra bir sonraki videoya geçiş; son videodan sonra
    /// "hepsini gördün" sayfasına.
    private func advance(from page: FeedPage) {
        guard let index = model.feed.firstIndex(of: page) else { return }
        let next = model.feed.indices.contains(index + 1) ? model.feed[index + 1].id : Self.endPageID
        withAnimation(.easeOut(duration: 0.25)) {
            currentPageID = next
        }
    }

    /// Never a video page's id, which is always "conversation#index".
    private static let endPageID = "end-of-feed"

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

/// The page after the last video.
///
/// While the server may still have more customers it only shows a spinner, and keeps asking for
/// them. Saying "all seen" and then having new videos appear under it would be wrong. When
/// nothing more is coming, it says so.
///
/// It keeps asking rather than asking once because a batch can arrive with nothing new in it
/// (customers the app already holds) while the server still says there is more. A single request
/// would then leave the spinner up for good. ``GalleryViewModel/loadMore(initial:)`` ignores calls
/// while one is in flight, so repeating it is harmless.
///
/// Pauses every player on arrival. No video page is active here, so none of them would pause
/// itself, and the last video would keep playing behind this page. Android twin: `EndOfFeedPage`
/// in GalleryScreen.kt.
private struct EndOfFeedPage: View {
    let isActivePage: Bool
    let stillLoading: Bool
    let playerManager: PlayerManager
    let onNeedMore: () async -> Void

    var body: some View {
        ZStack {
            Color.black
            if stillLoading {
                ProgressView().tint(.white)
            } else {
                VStack(spacing: 0) {
                    Image(systemName: "checkmark.circle.fill")
                        .font(.system(size: 56))
                        .foregroundStyle(.white.opacity(0.85))
                    Text(Strings.allSeen)
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.top, 16)
                    Text(Strings.endOfFeed)
                        .font(.subheadline)
                        .foregroundStyle(.white.opacity(0.6))
                        .padding(.top, 6)
                }
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
                .accessibilityIdentifier("endOfFeed")
            }
        }
        .onChange(of: isActivePage, initial: true) { _, active in
            if active { playerManager.pauseAll() }
        }
        .task(id: isActivePage && stillLoading) {
            while isActivePage && stillLoading && !Task.isCancelled {
                await onNeedMore()
                try? await Task.sleep(for: Self.moreRetry)
            }
        }
    }

    /// How often the page asks again while the server still says there is more.
    private static let moreRetry = Duration.milliseconds(1_500)
}

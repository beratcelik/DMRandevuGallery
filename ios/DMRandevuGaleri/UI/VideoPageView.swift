import AVFoundation
import SwiftUI

/// TEK video, tam ekran.
///
/// ─── İKİ EKSEN DE YENİ ─────────────────────────────────────────────────────
/// DİKEY: bir sonraki video. Aynı müşterinin videoları bittiğinde sonraki
/// müşteriye geçiliyor ve geride bırakılan müşteri silme sırasına giriyor
/// (karar ``GalleryViewModel/onPageSettled(pageID:)``'de, kuralı
/// ui/FeedPages.swift'te).
/// YATAY: KARAR. Sağa at = "bu bir ihlal, ihbar et", sola at = "ihlal değil".
///
/// Eskiden dikey eksen müşteriler, yatay eksen o müşterinin videoları arasında
/// geziniyordu ve karar ekranın köşesindeki iki kapsül düğmeyle veriliyordu.
/// Sahip yüzlerce video geziyor; kaydırma aynı kararı parmağın zaten bulunduğu
/// yerde veriyor.
struct VideoPageView: View {

    let conversation: Conversation
    let page: FeedPage
    let isActivePage: Bool
    let isNextPage: Bool
    let playerManager: PlayerManager
    let model: GalleryViewModel
    /// Karar verildikten sonra bir sonraki videoya geçiş.
    ///
    /// NEDEN ÇAĞIRANDAN GELİYOR: akışın konumu ``GalleryView``'da ve orada
    /// kalmalı. Sayfayı buradan sürmek, her videonun kendi akışını
    /// kaydırabilmesi demekti.
    let onAdvance: () -> Void

    // ─── KARARIN KARTI ───────────────────────────────────────────────────────
    //
    // Parmağın taşıdığı yol iki eksende de izleniyor: yatay karar, dikey ise
    // kartın "savrulması". Dikeyi hiç izlemeseydik kart bir rayda kayan panel
    // gibi dururdu.
    @State private var dragX: CGFloat = 0
    @State private var dragY: CGFloat = 0
    /// Parmağın karta DOKUNDUĞU yükseklik üst yarıda mı: kartın hangi yöne
    /// devrileceğini bu belirliyor (masadaki bir kartı itmek gibi).
    @State private var grabbedAbove = true
    /// Kart uçarken ikinci bir hareket alınmıyor: uçuş sırasında yapılan yeni
    /// bir sürükleme, henüz gönderilmemiş kararı ikinci kez tetiklerdi.
    @State private var flying = false
    /// Eşik titreşimi sürükleme başına BİR KEZ.
    @State private var buzzed = false
    @State private var cardWidth: CGFloat = 0
    @State private var cardHeight: CGFloat = 0
    @State private var downloading = false
    @State private var sharingStory = false
    @State private var sharingReels = false
    /// Reels akışının caption aşamasında mıyız: yüzde bittikten sonraki uzun bekleme.
    @State private var captioning = false
    @State private var captionForURL: String?
    /// Belirteci yapıştırma penceresi açık mı, ve içine yazılan metin.
    @State private var ihbarTokenPrompt = false
    @State private var ihbarTokenDraft = ""
    /// Toplu eleme onayı istenirken kaç video elenecek (nil: pencere kapalı).
    @State private var bulkDismissCount: Int?

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

    private var currentIndex: Int { page.mediaIndex }
    private var currentRawURL: String? {
        conversation.urls.indices.contains(currentIndex) ? conversation.urls[currentIndex] : nil
    }
    private var currentProxyURL: String? {
        currentRawURL.flatMap { repository.proxyURL($0)?.absoluteString }
    }

    var body: some View {
        ZStack {
            Color.black

            decisionCard
                // Dokunma ve basılı tutma jestleri KARTI SARAN görünümde, kartın
                // üstündeki sürükleme katmanının içinde değil. Hareket tanıyıcılar
                // dokunuşun düştüğü görünümün ATALARINA da ulaştığı için ikisi
                // birlikte çalışıyor; denetimler ise bu yığının üstünde ayrı
                // kardeşler olduğundan dokunuşu önce onlar alıyor — bir düğmeye
                // basmanın videoyu da hızlandırmasını engelleyen şey bu.
                //
                // NEDEN BURADA HÂLÂ DragGesture YOK: düz bir dokunuş kaydırma
                // görünümleriyle uyumlu, DragGesture değil. İlk sürüm basılı
                // tutmayı `DragGesture(minimumDistance: 0)` ile eşleştirmişti ve o
                // sürükleme her dikey kaydırmayı sessizce sahiplendi — akış tümden
                // kaymaz oldu. Yatay karar hareketi bu yüzden SwiftUI'de değil,
                // eksen kilitli bir UIKit tanıyıcısında (bkz. CardPanCatcher).
                //
                // `gesture`, `simultaneousGesture` DEĞİL: eşzamanlı bir dokunuş
                // başlıktaki düğmelerle aynı anda tanınıp onların dokunuşlarını
                // yutuyordu, yani filtre düğmesine basmak yalnızca videoyu
                // duraklatıyordu.
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
            railLayer

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
                            playerManager.playerHolding(page.id)?.seek(to: target)
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

            ihbarChip
            undoChip
            bottomBar
        }
        .clipped()
        .task(id: TaskKey(active: isActivePage, url: currentProxyURL)) { await pollPosition() }
        .task(id: controlsToken) { await hideControlsLater() }
        .onChange(of: insideMark) { _, inside in
            playerManager.setDucked(key: page.id, ducked: inside)
            if inside { beeps.start() } else { beeps.stop() }
        }
        .onDisappear { beeps.stop() }
        .onChange(of: paused) { _, value in
            guard isActivePage else { return }
            playerManager.setPaused(key: page.id, paused: value)
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
        // NEDEN GİRİŞ EKRANINDAKİ ALAN TEK BAŞINA YETMİYOR: sunucu oturumu yedi gün
        // yaşıyor ve uygulama açık oturumla açıldığında giriş ekranı HİÇ görünmüyor.
        // Belirteci yalnızca oraya koysaydık, sahip düğmenin neden çalışmadığını
        // anlatan bir yazıya bakıp ona ulaşamayacağı bir alana yönlendirilirdi.
        .alert(Strings.ihbarTokenTitle, isPresented: $ihbarTokenPrompt) {
            TextField(Strings.ihbarTokenHint, text: $ihbarTokenDraft)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            Button(Strings.ihbarTokenSave) {
                let token = ihbarTokenDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                ihbarTokenDraft = ""
                // Boş kaydetmek, çalışan bir belirteci silmek demek olurdu.
                if !token.isEmpty { model.saveIhbarToken(token) }
            }
            Button(Strings.cancel, role: .cancel) { ihbarTokenDraft = "" }
        } message: {
            Text(Strings.ihbarTokenExplain)
        }
        // Toplu eleme onayı.
        .confirmationDialog(
            Strings.bulkDismissTitle,
            isPresented: Binding(
                get: { bulkDismissCount != nil },
                set: { if !$0 { bulkDismissCount = nil } }
            ),
            presenting: bulkDismissCount
        ) { count in
            Button(Strings.bulkDismissConfirm, role: .destructive) {
                model.dismissAll(conversation)
            }
            Button(Strings.cancel, role: .cancel) {}
        } message: { count in
            Text(Strings.bulkDismissExplain(count))
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

    /// Karar yüzeyi: parmağı izleyen bir KART.
    ///
    /// ─── NEDEN KART ────────────────────────────────────────────────────────
    /// Sahip yüzlerce video geziyor ve karar hızlı olmalı. Elle tutulan bir kart
    /// duygusu bunu iki yönden veriyor: hareket parmağı birebir izlediği için
    /// karar "verilmiş" hissediliyor, ve kart eşiğe yaklaştıkça damga belirdiği
    /// için karar parmak kalkmadan ÖNCE görülüyor — yanlış kararı ağa hiç
    /// çıkmadan engelleyen tek fırsat bu.
    ///
    /// ─── JEST NEREDE ───────────────────────────────────────────────────────
    /// Sürükleme, kartın üstünde duran görünmez bir UIKit katmanında
    /// (``CardPanCatcher``) ve orada olmasının sebebi yazılı: SwiftUI'nin
    /// sürükleme jesti yön bilmiyor, bu ekranda bir kez denenip dikey kaydırmayı
    /// tümden çalmıştı. Dokunma ve basılı tutma jestleri bu yığını SARAN
    /// görünümde duruyor ve çalışmaya devam ediyor — hareket tanıyıcılar
    /// dokunuşun düştüğü görünümün atalarına da ulaşıyor.
    private var decisionCard: some View {
        ZStack {
            Color.black

            videoSurface
                // Damga KARTIN İÇİNDE: kartla birlikte eğiliyor ve onunla
                // uçuyor. Dışında, sabit dururken bir arayüz etiketi gibi
                // görünüyordu; üstüne yapıştırılmış bir mühür gibi durması,
                // kararın karta ait olduğunu söyleyen şey.
                .overlay(alignment: .top) { stampOverlay }
                // KÖŞELER YALNIZCA SÜRÜKLERKEN YUVARLANIYOR.
                //
                // Durgun hâlde video tam ekran olmak zorunda: sahip plakayı,
                // şeridi, ışığı o karede arıyor ve kırpılmış bir köşe delilin
                // kendisini götürebilir. Parmak değdiği anda ise kenarların
                // yuvarlanması, ekranı bir anda ELLE TUTULAN bir nesneye
                // çeviriyor — kartın "fiziksel" duygusunun yarısı bu kenar.
                .clipShape(RoundedRectangle(cornerRadius: cardCornerRadius))
                // KART MASADAN KALKIYOR: parmak değdiği an hafifçe küçülüyor ve
                // kenarları ekranın kenarından ayrılıyor. Yuvarlatılmış köşeyle
                // birlikte, ekranı bir anda ELLE TUTULAN bir nesneye çeviren şey
                // bu ikisi.
                //
                // KÜÇÜK (%4): daha fazlası videoyu belirgin biçimde küçültüyor ve
                // sahip tam da o anda görüntüye bakarak karar veriyor.
                //
                // NOT — ANDROID'DE KÖŞE YOK, YALNIZCA BU ÖLÇEK: orada video kendi
                // donanım katmanında çiziliyor ve üst katmanın dönüşünü alıyor
                // ama kırpma yolunu almıyor. iOS'ta oynatıcı sıradan bir katman
                // olduğu için köşe de yuvarlanabiliyor.
                .scaleEffect(1 - cardLift)
                .offset(x: dragX, y: dragY)
                // EĞİM, PARMAĞIN TUTTUĞU YERE GÖRE TERS DÖNÜYOR: üstten tutulan
                // kart bir yöne, alttan tutulan öteki yöne deviriliyor —
                // masadaki bir kartı iterken olan şey. Sabit yönlü bir eğim,
                // kartı elle tutulan bir şey değil bir animasyon gibi
                // gösteriyordu.
                .rotationEffect(.degrees(tiltDegrees))

            CardPanCatcher(
                enabled: model.ihbarAvailable && !flying,
                onBegan: { location in
                    grabbedAbove = location.y < cardHeight / 2
                },
                onChanged: { translation in
                    dragX = translation.width
                    // DİKEYİ SÖNÜMLEYEREK İZLİYORUZ: kart parmağı birebir takip
                    // etseydi dikey sayfalayıcıyla yarışıyormuş gibi görünürdü.
                    // Üçte bir, "kart biraz savruldu" demeye yetiyor.
                    dragY = translation.height * Self.verticalFollow
                    if !buzzed, abs(dragX) >= cardWidth * swipeDistanceFraction {
                        buzzed = true
                        // Eşik geçildi: kararın verileceğini parmak kalkmadan
                        // önce bildiren tek sinyal.
                        UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    }
                },
                onEnded: { translation, velocity in
                    buzzed = false
                    settleCard(translation: translation, velocity: velocity)
                }
            )
            .allowsHitTesting(model.ihbarAvailable && !flying)
        }
        .background {
            // Kartın ölçüsü: eşik ve eğim hesabı buna dayanıyor. Ölçüm gelmeden
            // karar verilmiyor (bkz. decisionFor).
            GeometryReader { proxy in
                Color.clear
                    .onAppear {
                        cardWidth = proxy.size.width
                        cardHeight = proxy.size.height
                    }
                    .onChange(of: proxy.size) { _, size in
                        cardWidth = size.width
                        cardHeight = size.height
                    }
            }
        }
    }

    /// Sürükleme mesafesiyle artan kalkma oranı; durgun kartta sıfır.
    private var cardLift: CGFloat {
        min(1, abs(dragX) / Self.liftAt) * Self.lift
    }

    /// Sürükleme mesafesiyle büyüyen köşe yarıçapı; durgun kartta sıfır.
    private var cardCornerRadius: CGFloat {
        min(1, abs(dragX) / Self.cornerFullAt) * Self.cornerRadius
    }

    /// Kartın eğimi. Genişlik ölçülmeden sıfır: bölme hatası ve anlamsız açı yok.
    private var tiltDegrees: Double {
        guard cardWidth > 0 else { return 0 }
        return Double(dragX / cardWidth) * Self.cardTiltDegrees * (grabbedAbove ? 1 : -1)
    }

    /// Sürüklenen yöne göre beliren damga.
    ///
    /// Damga, KARARIN kendisine değil parmağın YÖNÜNE bakıyor ve eşikten ÖNCE
    /// beliriyor. Karara bağlasaydık ancak eşik geçildikten sonra görünürdü —
    /// yani sahip kararının ne olacağını, kararı verdikten sonra öğrenirdi.
    @ViewBuilder
    private var stampOverlay: some View {
        if model.ihbarAvailable, cardWidth > 0, abs(dragX) > Self.stampAppears {
            let report = dragX > 0
            HStack {
                if report {
                    stamp(.report)
                    Spacer()
                } else {
                    Spacer()
                    stamp(.dismiss)
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 120 + chromeInsets.top)
        }
    }

    private func stamp(_ decision: SwipeDecision) -> some View {
        SwipeStamp(
            decision: decision,
            opacity: Double(min(1, abs(dragX) / (cardWidth * Self.stampFullAt)))
        )
        // Mühür gibi eğik durması, ekrana yapıştırılmış bir etiket olmadığını
        // söylüyor.
        .rotationEffect(.degrees(decision == .report ? -14 : 14))
    }

    private var videoSurface: some View {
        ZStack {
            Color.black
            let rawURL = currentRawURL
            let proxyURL = rawURL.flatMap { repository.proxyURL($0)?.absoluteString } ?? rawURL
            if let proxyURL {
                switch model.failures[proxyURL] {
                case .linkDead:
                    // The link is dead, so trying it again would fail the same way — but the
                    // server re-signs these on request, so asking for the conversation again
                    // gets one that works. That is what this retry does, unlike the transient
                    // one below.
                    PlaybackRetry(message: Strings.videoExpired, busy: refreshing) {
                        refreshing = true
                        Task {
                            let renewed = await model.refreshLinks(for: conversation)
                            refreshing = false
                            if !renewed { model.toast = Strings.videoRefreshFailed }
                        }
                    }

                case .transient, .sessionLost:
                    // Nothing about this one says the video itself is bad, so it keeps the offer
                    // of another go instead of being written off for the rest of the session.
                    PlaybackRetry(message: Strings.videoFailed) {
                        model.clearFailure(proxyURL)
                        playerManager.play(key: page.id, url: proxyURL)
                    }

                case .none:
                    if isActivePage {
                        PlayerLayerView(player: playerManager.player(for: page.id))
                    } else {
                        ProgressView().tint(.white.opacity(0.35))
                    }
                }
            } else {
                ProgressView().tint(.white.opacity(0.35))
            }
        }
    }

    // MARK: - Kartın fiziği

    /// Parmak kalktığında: kart ya yerine OTURUYOR ya ekrandan UÇUYOR.
    private func settleCard(translation: CGSize, velocity: CGSize) {
        let verdict = decisionFor(
            translation: translation.width,
            velocity: velocity.width,
            width: cardWidth
        )

        guard let verdict else { return settleBack() }

        if ihbarMark.phase == .noToken {
            // Belirteç yokken kaydırma ağa çıkmıyor; eksik olanı sormak tek
            // makul davranış.
            settleBack()
            ihbarTokenPrompt = true
            return
        }

        flingAway(verdict)
    }

    /// Karar verilmedi: kart yaylanarak yerine oturuyor.
    ///
    /// YAY, SÖNÜMÜ DÜŞÜK (0,6): bir kez hafifçe geri sekiyor — elden bırakılan
    /// bir kartın masaya oturması bu. Kritik sönüm teknik olarak "doğru" ama
    /// cansız; sekme, kartın bir ağırlığı olduğunu söyleyen tek şey.
    private func settleBack() {
        withAnimation(.spring(response: 0.34, dampingFraction: 0.6)) {
            dragX = 0
            dragY = 0
        }
    }

    /// Karar verildi: kart atıldığı yönde ekrandan çıkıyor, sonra akış ilerliyor.
    ///
    /// KARAR UÇUŞ BİTTİKTEN SONRA VERİLİYOR: kart hâlâ ekrandayken kuyruğa
    /// yazsaydık geri alma çipi kart uçarken belirir ve sahip "neyi geri
    /// alıyorum" diye bakacağı videoyu görmeden karar vermiş olurdu.
    private func flingAway(_ verdict: SwipeDecision) {
        flying = true
        // Kararın kendisi: eşikteki hafif titreşimden AYRI ve daha ağır.
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()

        withAnimation(.easeOut(duration: Self.flyDuration)) {
            dragX = (verdict == .report ? 1 : -1) * cardWidth * Self.flyDistance
            // Uçarken hafifçe aşağı düşüyor: düz bir yatay kayma, kartı bir
            // rayda gidiyormuş gibi gösteriyor.
            dragY += cardWidth * Self.flyDrop
        }

        Task { @MainActor in
            try? await Task.sleep(for: .seconds(Self.flyDuration))
            model.decide(
                conversation,
                mediaIndex: page.mediaIndex,
                decision: verdict == .report ? .report : .dismiss
            )
            onAdvance()
            // Kart ekran dışındayken bekliyoruz: hemen sıfırlamak, akış bir
            // sonraki videoya kayarken kartın ortaya geri zıpladığını göstermek
            // olurdu.
            try? await Task.sleep(for: .seconds(Self.cardResetDelay))
            dragX = 0
            dragY = 0
            flying = false
        }
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
                speedBadge
            }
        }
        .allowsHitTesting(false)
    }

    /// KENARDA, ORTADA DEĞİL: hızlı oynatma tam da operatörün videoya dikkatle baktığı an;
    /// ortadaki rozet bakılan ayrıntının üstüne oturuyordu. Sol alt köşe, geri alma çipiyle aynı
    /// katman kuralı; çip oradaysa onun üstüne çıkıyor, altına girmiyor. Android ikiziyle aynı.
    private var speedBadge: some View {
        VStack {
            Spacer()
            HStack {
                SpeedBadge(speed: Int(Self.holdSpeed))
                    .accessibilityIdentifier("speedBadge")
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(
                .bottom,
                ihbarBottomPadding + chromeInsets.bottom
                    + (model.pendingDecisions.latest == nil ? 0 : 56)
            )
        }
    }

    private var header: some View {
        VStack {
            HStack(alignment: .center, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    // Uzun bir kullanıcı adı, yanındakini satırdan itiyor. Filtreler sağ
                    // raya indikten sonra itilecek tek şey sıradaki müşteri sayısı kaldı,
                    // ama kural aynı: yol veren isim, ve elipsle bunu söylüyor.
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

                // Sıradaki müşteri sayısı. Düğme DEĞİL, o yüzden raya inmedi — ve
                // başlıkta kalması, uzun bir kullanıcı adının yaslanacağı sabit bir
                // şey bırakıyor: yoksa elips hiç devreye girmezdi.
                remainingCount
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

    /// Filtre rayı: sağ kenarda dikey sütun.
    ///
    /// Instagram Reels'in beğen/yorum sütunuyla aynı yerde ve aynı ritimde. Başlıkta yatay bir
    /// sıraydılar; orada uzun bir kullanıcı adı son düğmeyi satırdan itiyordu ve sütun
    /// başparmağın durduğu yerde değildi.
    ///
    /// KONUMU `railLayer` veriyor; burada yalnızca içerik ve arka plan var.
    private var filterToggleStack: some View {
        VStack(spacing: Self.railGap) {
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

            // TOPLU ELEME: bu müşterinin karar verilmemiş videoları çoksa hepsini tek
            // istekte elemek. İki yazma ucu tek bir oran sınırı kovasını paylaşıyor
            // (dakikada yirmi); on beş videoyu tek tek elemek o bütçenin dörtte üçünü
            // yakar ve aynı dakikadaki GERÇEK ihbarı da engellerdi.
            //
            // EN AZ İKİ VİDEO ŞARTI: tek video için toplu bir hareket, kaydırmanın
            // zaten yaptığı işi ikinci bir yüzeyden tekrar sunmak olurdu.
            if model.ihbarAvailable {
                let undecided = model.undecidedIndices(conversation)
                if undecided.count >= 2 {
                    toggle(icon: "nosign", on: false) { bulkDismissCount = undecided.count }
                        .accessibilityIdentifier("bulkDismiss")
                }
            }

        }
        .padding(.leading, 28)
        .padding(.top, 20)
        .padding(.trailing, Self.railEdge)
        .padding(.bottom, 12)
        // SAĞ KENARDA SCRIM YOK: üstteki 140 ve alttaki 160 yalnızca tam genişlik
        // bantları, arası çıplak video. Köşegen geçiş, dikdörtgenin açıkta kalan iki
        // kenarını (sol ve üst) saydam bırakıyor. Dokunuş yutmaması şart: SwiftUI'de
        // arka plan görünümü de vuruş testine giriyor ve tüm şeridi yutardı.
        .background {
            LinearGradient(
                colors: [.clear, .black.opacity(0.34)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .allowsHitTesting(false)
        }
    }

    /// Kaç müşteri sırada bekliyor.
    @ViewBuilder
    private var remainingCount: some View {
        if model.remaining > 0 {
            Text("\(model.remaining)")
                .font(.headline)
                .foregroundStyle(.white)
                .accessibilityIdentifier("remainingCount")
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

    /// Rayın konumu: alttan çıpalı, sağ kenara yaslı.
    ///
    /// ALTTAN ÇIPALI, ORTADAN DEĞİL: Instagram'da da sütun alt bloğun hizasından yukarı
    /// diziliyor. Merdiven GERİ AL çipiyle birebir aynı (`ihbarBottomPadding`), biri solda biri
    /// sağda aynı satırda dursun diye.
    ///
    /// YUKARIYA TAŞMIYOR: damga (kararın adı) sağ üstte 120'de duruyor ve kart uçarken onun
    /// üstüne çizilen bir ray, kararın parmak kalkmadan okunmasını engellerdi. Ray alt üçte
    /// birde kaldığı sürece ikisi hiç karşılaşmıyor.
    private var railLayer: some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                filterToggleStack
            }
            .padding(.bottom, ihbarBottomPadding + chromeInsets.bottom)
        }
    }

    private var bottomBar: some View {
        VStack {
            Spacer()
            HStack {
                // NOKTA DİZİSİ KALKTI, YERİNE SAYI: noktalar yatay bir
                // sayfalayıcıyı anlatıyordu ("sağa kaydır, sonraki video").
                // Videolar artık dikey eksende ve yatay eksen KARAR demek; aynı
                // noktalar şimdi hem var olmayan bir hareketi öğretir hem de
                // kaydırmanın ne yaptığı hakkında yanlış bir söz verirdi.
                if conversation.urls.count > 1 {
                    Text(Strings.videoPosition(currentIndex + 1, conversation.urls.count))
                        .font(.caption)
                        .foregroundStyle(.white.opacity(0.75))
                        .accessibilityIdentifier("videoPosition")
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
                // DIŞA AKTARMA BİTİNCE ETİKET SUSMUYOR: caption çağrısı 90 saniyeye kadar
            // sürebiliyor ve yüzde sıfırlandığı an düğme "Reels" yazan boş bir dönece
            // düşüyordu — en uzun aşama, hakkında en az şey söylenen aşamaydı.
            label: captioning ? Strings.captionGenerating : percent(sharingReels, Strings.reels),
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

    // MARK: - İhbar yüzeyleri

    /// O an ekrandaki videonun ihbar durumu — SALT OKUNUR.
    ///
    /// ÜSTTE, başlığın altında: ekranın altı zaten katmanlı ve karar kaydırması
    /// kartın TAMAMINI hareket ettiriyor — karar yüzeyiyle aynı yerde duran bir
    /// gösterge her kaydırmada parmağın altında kalırdı.
    ///
    /// NEDEN GİZLEMEK, "DEVRE DIŞI BIRAKMAK" DEĞİL: ihbar hattı tek bir hesabı
    /// dinliyor (bkz. ``IhbarAccount``) ve öteki hesabın videosu orada hiçbir
    /// kayıtla eşleşmiyor. Soluk ama duran bir gösterge, sahibi "neden
    /// çalışmıyor" diye uğraştırırdı; olmayan gösterge doğru cümleyi kuruyor.
    @ViewBuilder
    private var ihbarChip: some View {
        // Çip YALNIZCA söyleyecek bir şeyi varken çiziliyor; gerekçesi
        // IhbarMark.saysSomething başlığında.
        if model.ihbarAvailable, ihbarMark.saysSomething {
            VStack {
                HStack {
                    IhbarStatusChip(
                        mark: ihbarMark,
                        onTap: { if ihbarMark.phase == .noToken { ihbarTokenPrompt = true } },
                        onLongPress: { ihbarTokenPrompt = true }
                    )
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.top, 72 + chromeInsets.top)
                Spacer()
            }
        }
    }

    /// Bekleyen kararın geri alma çipi.
    ///
    /// NEDEN GEREKLİ: karar verildiği anda kart uçuyor ve akış bir sonraki
    /// videoya geçiyor — sahip kararını verdiği videoyu ARTIK GÖRMÜYOR. Geri
    /// alma yolunun kararla aynı anda ve aynı ekranda durması gerekiyor.
    /// (İkinci yol: o sayfaya geri kaydırmak.)
    ///
    /// Ekranın altı katmanlı: eylem şeridi 0-92pt, oynatma çubuğu 92pt, küfür
    /// işaretleme düğmesi 140pt. Çip o an açık olan en üst katmanın üstüne
    /// çıkıyor; sabit bir yükseklik, çubuk açıldığı anda üst üste binme demekti.
    @ViewBuilder
    private var undoChip: some View {
        if let queued = model.pendingDecisions.latest {
            VStack {
                Spacer()
                HStack {
                    UndoChip(
                        decision: queued.decision,
                        onUndo: { model.undoDecision(queued.page.id) }
                    )
                    Spacer()
                }
                .padding(.horizontal, 12)
                .padding(.bottom, ihbarBottomPadding + chromeInsets.bottom)
            }
        }
    }

    private var ihbarMark: IhbarMark {
        model.ihbarMark(conversationKey: conversation.key, mediaIndex: currentIndex)
    }

    private var ihbarBottomPadding: CGFloat {
        if model.censorAudio { return 200 }
        if controlsShown { return 148 }
        return 92
    }

    // MARK: - Playback plumbing

    /// Playback follows the settled vertical page; the neighbouring page only pre-buffers.
    private func loadVideo() {
        guard let proxyURL = currentProxyURL else { return }
        if isActivePage {
            playerManager.play(key: page.id, url: proxyURL)
        } else if isNextPage {
            playerManager.preload(key: page.id, url: proxyURL)
        }
    }

    private func applySpeed() {
        playerManager.setSpeed(
            key: page.id,
            speed: holding && isActivePage ? Self.holdSpeed : 1
        )
    }

    /// The player has no position callback we want here, so it gets read on a timer while this
    /// page is the one on screen. Paused while scrubbing, or the thumb would fight the poll for
    /// the same value.
    private func pollPosition() async {
        while isActivePage && !Task.isCancelled {
            if let player = playerManager.playerHolding(page.id) {
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
                    // KUSUR DÜZELTİLDİ: argümansız çağrı, elle konan küfür
                    // işaretlerinin dışa aktarıma hiç ulaşmaması demekti
                    // (CaptionSheetView doğru yapıyordu, bu satırlar değil).
                    options: model.exportOptions(
                        conversationKey: conversation.key, mediaIndex: currentIndex
                    )
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
                    // KUSUR DÜZELTİLDİ: argümansız çağrı, elle konan küfür
                    // işaretlerinin dışa aktarıma hiç ulaşmaması demekti
                    // (CaptionSheetView doğru yapıyordu, bu satırlar değil).
                    options: model.exportOptions(
                        conversationKey: conversation.key, mediaIndex: currentIndex
                    )
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
                captioning = false
                exportProgress = nil
            }
            do {
                // SIRA: hazırla, caption'ı panoya koy, SONRA devret. Caption hazır
                // olmadan Instagram'a geçmek, yapıştıracak bir şey olmadan geçmek demek.
                let file = try await downloader.downloadForShare(
                    rawURL: rawURL,
                    clientName: conversation.clientName,
                    // KUSUR DÜZELTİLDİ: argümansız çağrı, elle konan küfür
                    // işaretlerinin dışa aktarıma hiç ulaşmaması demekti
                    // (CaptionSheetView doğru yapıyordu, bu satırlar değil).
                    options: model.exportOptions(
                        conversationKey: conversation.key, mediaIndex: currentIndex
                    )
                ) { exportProgress = $0 }
                exportProgress = nil

                // FOTOĞRAFLARA DA BIRAKILIYOR, ikinci bir dışa aktarma olmadan: besteci
                // açıldıktan sonra Instagram kimliği reddederse bunu bize SÖYLEMİYOR ve
                // fotoğraflardaki kopya o sessiz reddin tek telafisi. İzin verilmemişse
                // akış durmuyor — besteci videoyu zaten kendisi taşıyor.
                var saved = true
                do { try await PhotoLibrarySaver.save(file) } catch { saved = false }

                captioning = true
                let caption = try await captionOrNil(rawURL)
                captioning = false
                let hasCaption = !(caption ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                if let caption, hasCaption { InstagramSharing.copyCaption(caption) }

                // ÖNCE BESTECİ, sonra uygulama.
                //
                // CAPTION BESTECİYE AYRICA VERİLİYOR: yukarıdaki copyCaption yedek yol
                // (uygulamanın düz açılması) için duruyor, ama besteci panoyu komple
                // değiştiriyor — caption oraya aynı öğenin içinde girmezse siliniyor.
                let inComposer = InstagramSharing.openReelComposer(
                    video: file,
                    caption: hasCaption ? caption : nil
                )
                let opened = inComposer || InstagramSharing.openInstagram()

                // MESAJ YALNIZCA SÖYLEYECEK BİR ŞEY VARSA. iOS'un uygulama üstü bildirimi
                // yok; ToastView uygulamanın İÇİNDE çiziliyor ve bir sonraki satır
                // uygulamayı arka plana atıyor, yani devir başarılıysa kurulan mesaj hiç
                // okunmuyordu. Android'in sistem Toast'ı Instagram'ın üstünde durduğu için
                // orada aynı sorun yok. Geriye okunabilecek tek hâl kalıyor: devredilemedi.
                if !opened {
                    model.toast = Strings.instagramMissing
                } else if !inComposer && !hasCaption {
                    // Uygulamaya düşüldü ve pano boş: operatörün Reels'te ne yapacağını
                    // bilmesi gerekiyor, ve bu mesaj geri döndüğünde hâlâ duruyor olabilir.
                    model.toast = saved ? Strings.reelsReadyNoCaption : Strings.downloadFailed
                }
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

    /// Caption'ı getirir, üretilemezse nil döner.
    ///
    /// OTURUM KAYBI YUKARI GEÇİYOR: eskiden `try?` ile çağrılıyordu ve yetkisizlik hatası da
    /// yutuluyordu — operatöre, oturumu bittiği hâlde "caption üretilemedi" deniyordu.
    /// Paylaşımın kendisi caption'sız bilerek sürüyor, video zaten kaydedilmiş oluyor.
    private func captionOrNil(_ rawURL: String) async throws -> String? {
        do {
            return try await repository.generateCaption(
                salonId: conversation.salonId,
                clientId: conversation.clientId,
                rawMediaURL: rawURL
            )
        } catch is UnauthorizedError {
            throw UnauthorizedError()
        } catch {
            // Android ikizi bunu logcat'e yazıyor; burada da bir iz kalsın, yoksa
            // "caption üretilemedi" cümlesinin sebebi iki platformda iki ayrı yerde
            // aranır ve birinde hiç bulunmaz.
            print("GalleryCaption: reels caption failed — \(error)")
            return nil
        }
    }

    /// The glyph size shared by every filter toggle, and the square each one sits in.
    private static let toggleGlyph: CGFloat = 20
    /// Long enough not to fire on a tap that switches the filter, short enough to find.
    private static let modeHold = 0.5

    private static let toggleTouch: CGFloat = 40

    /// Filtre rayındaki simgeler arası dikey boşluk.
    ///
    /// 40pt kutu + 10pt = 50pt adım. Instagram'ın sütunu 68pt adımla diziliyor ama orada her
    /// simgenin ALTINDA bir sayı satırı var; sayıyı çıkarınca kalan ritim bu.
    private static let railGap: CGFloat = 10

    /// Rayın sağ kenara uzaklığı. Ölçü kutunun değil SİMGENİN kenara uzaklığından geliyor:
    /// Instagram'da simge mürekkebi kenardan 14-15pt içeride, 40pt kutunun içindeki 20pt simge
    /// ise her yanından 10pt boşluk taşıyor. 4 + 10 = 14pt, aynı hiza.
    private static let railEdge: CGFloat = 4

    /// How long the controls stay up once nothing is happening.
    private static let controlsLinger: Double = 3

    /// How often the player is asked where it has got to.
    private static let positionPollMS = 120

    /// Past this, a press is a hold rather than a tap.
    private static let holdThreshold: Double = 0.25

    /// How far a finger may travel before a press is read as a swipe instead.
    private static let touchSlop: CGFloat = 10

    /// How much faster a held-down video runs.
    // ─── kartın fiziği ───────────────────────────────────────────────────────

    /// Dikey hareketin karta yansıyan oranı; birebir izlemek kartı savruk gösterir.
    private static let verticalFollow: CGFloat = 0.34
    /// Kartın tam genişlikte eğildiği açı. Küçük tutuluyor — video izlenirken
    /// okunaklı kalmalı.
    private static let cardTiltDegrees: Double = 12
    /// Damganın belirmeye başladığı yatay yol (nokta).
    private static let stampAppears: CGFloat = 8
    /// Damganın tam görünür olduğu mesafe — genişliğin oranı. Karar eşiğinden
    /// (%25) KÜÇÜK: damga kararın verileceğini önceden söylemeli.
    private static let stampFullAt: CGFloat = 0.18
    /// Kartın tam kalktığı yatay yol (nokta).
    private static let liftAt: CGFloat = 90
    /// Kalkan kartın küçülme oranı.
    private static let lift: CGFloat = 0.04
    /// Köşelerin tam yuvarlandığı yatay yol (nokta). Kısa: kart hemen "ele geçmeli".
    private static let cornerFullAt: CGFloat = 90
    /// Sürüklenen kartın köşe yarıçapı.
    private static let cornerRadius: CGFloat = 28
    /// Kartın uçarken gittiği yol, genişliğin katı olarak — ekranı tam terk etmeli.
    private static let flyDistance: CGFloat = 1.6
    /// Uçarken düştüğü mesafe (genişliğin oranı): düz bir kayma ray gibi görünüyor.
    private static let flyDrop: CGFloat = 0.12
    /// Uçuş süresi. Uzatmak kararı yavaşlatıyor, kısaltmak hareketi görünmez kılıyor.
    private static let flyDuration: Double = 0.26
    /// Uçuştan sonra kartın ortaya alınması için beklenen süre; akışın bir
    /// sonraki videoya kayması bu kadar sürüyor.
    private static let cardResetDelay: Double = 0.16

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

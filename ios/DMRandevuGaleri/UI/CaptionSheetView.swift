import SwiftUI

struct CaptionSheetView: View {

    let conversation: Conversation
    let rawMediaURL: String
    let onSessionLost: () -> Void
    let onToast: (String) -> Void

    @Environment(\.dismiss) private var dismiss

    @State private var caption: String?
    @State private var explanation = ""
    @State private var generating = true
    @State private var failed = false
    // Sunucunun gerekçesi. "Caption üretilemedi" tek başına hiçbir şey söylemiyor.
    @State private var failureReason: String?
    @State private var sharing = false
    @State private var shareProgress: Int?
    @State private var shareFile: URL?
    // "Kopyalandı" görünürken true; iki saniye sonra düğme eski hâline dönüyor.
    @State private var justCopied = false

    private let repository = ServiceLocator.repository!

    /// Caption'ın hemen altındaki büyük "Kopyala" düğmesi.
    ///
    /// NEDEN BÜYÜK VE NEDEN BURADA: iPhone'da caption Instagram'a kendiliğinden ulaşmıyor —
    /// devirle birlikte panoya konan metin Reels'te çoğu zaman yapıştırılamıyor ve devirden
    /// sonraki yedek yazma arka planda iOS tarafından reddediliyor. Operatör bu yüzden HER
    /// paylaşımda buraya dönüp kopyalıyor. Düğme eskiden "Kapat"ın yanında küçük bir yazıydı ve
    /// sayfanın en altındaydı; uzun bir caption'da yarım yükseklikte açılan sayfanın dışında kalıyordu.
    /// Önde yazmak her zaman çalışıyor, yani bu dokunuş güvenilir yol — en kolay bulunan yer
    /// olmalı: metnin hemen altı.
    ///
    /// NEDEN "Kopyalandı" DURUMU: bildirim videonun üstünde, sayfanın arkasında çıkıyor. Dokunuşun
    /// işe yaradığını düğmenin kendisi söylemeli.
    private var copyButton: some View {
        Button {
            guard let caption else { return }
            InstagramSharing.copyCaption(caption)
            onToast(Strings.captionCopied)
            justCopied = true
            Task {
                try? await Task.sleep(for: .seconds(2))
                justCopied = false
            }
        } label: {
            Label(
                justCopied ? Strings.captionCopiedShort : Strings.captionCopy,
                systemImage: justCopied ? "checkmark" : "doc.on.doc"
            )
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.bordered)
        .controlSize(.large)
        .tint(justCopied ? .green : .accentColor)
        .disabled(caption == nil)
        .accessibilityIdentifier("caption-copy")
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(Strings.captionTitle)
                    .font(.headline)

                if generating {
                    HStack(spacing: 12) {
                        ProgressView()
                        Text(Strings.captionGenerating)
                    }
                    .padding(.vertical, 24)
                } else if failed {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(Strings.captionFailed)
                        if let failureReason {
                            Text(failureReason)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                        .foregroundStyle(.red)
                        .padding(.vertical, 16)
                } else {
                    Text(caption ?? "")
                        .font(.body)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)

                    copyButton
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(Strings.captionExplanationHint)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    TextField("", text: $explanation, axis: .vertical)
                        .lineLimit(2...4)
                        .textFieldStyle(.roundedBorder)
                        .disabled(generating)
                }
                .padding(.top, 8)

                Button(Strings.captionRegenerate) {
                    Task { await generate(explanation.isEmpty ? nil : explanation) }
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
                .disabled(generating)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 16)
            .padding(.top, 20)
        }
        // NEDEN ALTA SABİT: sayfa yarım yükseklikte açılıyor ve büyük "Kopyala" düğmesi gelince
        // "Reels olarak paylaş" ile "Kapat" sayfanın altından taştı — görünmek için kaydırmak
        // gerekiyordu. Sabit şeritte caption ne kadar uzun olursa olsun, sayfa hangi yükseklikte
        // durursa dursun ve klavye açıkken bile ikisi de ekranda.
        .safeAreaInset(edge: .bottom) { actionBar }
        .presentationDetents([.medium, .large])
        .task { await generate(nil) }
        .sheet(item: Binding(get: { shareFile.map(ShareTarget.init) },
                             set: { shareFile = $0?.url })) { target in
            ShareSheet(items: [target.url])
        }
    }

    /// "Kapat" ve "Reels olarak paylaş", sayfanın altında sabit bir şeritte.
    private var actionBar: some View {
        HStack(spacing: 12) {
            Button(Strings.close) { dismiss() }
                .buttonStyle(.bordered)
                .controlSize(.large)
                .accessibilityIdentifier("caption-close")

            Button {
                share()
            } label: {
                HStack {
                    if sharing {
                        ProgressView().padding(.trailing, 8)
                        Text(shareProgress.map(Strings.progress) ?? Strings.sharePreparing)
                    } else {
                        Text(Strings.captionShare)
                    }
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(generating || sharing || caption == nil)
            .accessibilityIdentifier("caption-share")
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 10)
        .background(.bar)
    }

    private func generate(_ manualExplanation: String?) async {
        generating = true
        failed = false
        failureReason = nil
        defer { generating = false }
        do {
            caption = try await repository.generateCaption(
                salonId: conversation.salonId,
                clientId: conversation.clientId,
                rawMediaURL: rawMediaURL,
                manualExplanation: manualExplanation
            )
        } catch is UnauthorizedError {
            onSessionLost()
            dismiss()
        } catch let error as CaptionFailedError {
            failed = true
            failureReason = error.serverMessage ?? "HTTP \(error.status)"
        } catch {
            failed = true
            failureReason = error.localizedDescription
        }
    }

    private func share() {
        guard let text = caption else { return }
        sharing = true
        Task {
            defer {
                sharing = false
                shareProgress = nil
            }
            do {
                // Read at click time rather than observed: the sheet has no view model, and the
                // toggles write through synchronously.
                let settings = ServiceLocator.settings!
                let file = try await ServiceLocator.downloader.downloadForShare(
                    rawURL: rawMediaURL,
                    clientName: conversation.clientName,
                    options: ExportOptions(
                        blurFaces: settings.blurFaces,
                        blurPlates: settings.blurPlates,
                        fastPlates: settings.fastPlates,
                        watermarkHandle: settings.watermark && !settings.igUsername.isEmpty
                            ? settings.igUsername
                            : nil,
                        censorAudio: settings.censorAudio,
                        censorInsults: settings.censorInsults,
                        censorByHand: settings.censorByHand,
                        // The sheet has no view model, so the marks are read the same way the
                        // toggles are — straight from the store.
                        manualWindows: ServiceLocator.manualMarks.forMedia(
                            conversationKey: conversation.key,
                            mediaIndex: max(conversation.urls.firstIndex(of: rawMediaURL) ?? 0, 0)
                        )
                    )
                ) { shareProgress = $0 }
                // Instagram accepts no caption on any hand-off, so it travels via the clipboard —
                // the same trick the web gallery uses.
                InstagramSharing.copyCaption(text)
                onToast(Strings.captionCopied)

                // REELS BESTECİSİ ÖNCE. Caption'ı bu sayfada ürettiysek gidilecek yer bellidir:
                // haber metniyle paylaşılan bir trafik videosu Reels'e gidiyor.
                //
                // ALTTAKİ YEDEK NE İŞE YARIYOR: paylaşım sayfası (UIActivityViewController)
                // Instagram'a ulaşıyor ama hangi yüzeye düşeceğini söylemiyor. Yedek yine de
                // duruyor: besteci kapalıysa ya da Instagram adresi karşılamazsa elde bir şey
                // kalması, hiç açılmamasından iyi.
                if InstagramSharing.reelsComposerEnabled,
                   InstagramSharing.openReelComposer(video: file, caption: text) {
                    dismiss()
                } else {
                    shareFile = file
                }
            } catch is UnauthorizedError {
                onSessionLost()
                dismiss()
            } catch is VideoExporter.ExportFailedError {
                onToast(Strings.exportFailed)
            } catch {
                onToast(Strings.shareFailed)
            }
        }
    }
}

private struct ShareTarget: Identifiable {
    let url: URL
    var id: String { url.path }
}

/// The system share sheet, which is how a file reaches Instagram (or anywhere else) when there is
/// no dedicated composer to hand it to.
private struct ShareSheet: UIViewControllerRepresentable {

    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

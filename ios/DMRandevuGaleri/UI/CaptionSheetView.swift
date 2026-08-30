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
    @State private var sharing = false
    @State private var shareProgress: Int?
    @State private var shareFile: URL?

    private let repository = ServiceLocator.repository!

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
                    Text(Strings.captionFailed)
                        .foregroundStyle(.red)
                        .padding(.vertical, 16)
                } else {
                    Text(caption ?? "")
                        .font(.body)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
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
                    .padding(.vertical, 4)
                }
                .buttonStyle(.borderedProminent)
                .disabled(generating || sharing || caption == nil)

                HStack {
                    Spacer()
                    Button(Strings.captionCopy) {
                        guard let caption else { return }
                        InstagramSharing.copyCaption(caption)
                        onToast(Strings.captionCopied)
                    }
                    .disabled(caption == nil)
                    Button(Strings.close) { dismiss() }
                }
                .padding(.top, 4)
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 32)
            .padding(.top, 20)
        }
        .presentationDetents([.medium, .large])
        .task { await generate(nil) }
        .sheet(item: Binding(get: { shareFile.map(ShareTarget.init) },
                             set: { shareFile = $0?.url })) { target in
            ShareSheet(items: [target.url])
        }
    }

    private func generate(_ manualExplanation: String?) async {
        generating = true
        failed = false
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
        } catch {
            failed = true
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
                        censorInsults: settings.censorInsults
                    )
                ) { shareProgress = $0 }
                // Instagram accepts no caption on any hand-off, so it travels via the clipboard —
                // the same trick the web gallery uses.
                InstagramSharing.copyCaption(text)
                onToast(Strings.captionCopied)
                shareFile = file
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

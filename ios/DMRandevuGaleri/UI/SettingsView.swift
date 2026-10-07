import SwiftUI

/// Ayarlar: hesap bilgisi, şifre değiştirme, çıkış ve sürüm.
///
/// NEDEN ALT SAYFA: uygulamanın başka hiçbir ayar ekranı yok ve tek amacı videoları gezmek olan bir
/// ekranın üstüne tam sayfa bir ekran eklemek gezinmeyi bozardı. Sağ üstteki dişli açıyor.
///
/// ŞİFRE: kişi yalnızca KENDİ şifresini değiştiriyor (sunucu hangi şifre olduğunu oturumdan biliyor).
/// Başarıda alanlar boşalıyor ve şifre hiçbir yerde saklanmıyor. Bir 401, kişinin kendi oturumunun
/// bittiği anlamına geliyor ve giriş ekranına dönülüyor; "mevcut şifre yanlış" ise sunucudan 400 ile
/// geliyor ve burada metniyle görünüyor.
///
/// ÇIKIŞ: önce sorulur, çünkü yanlışlıkla dokunulabilir ve geri dönüşü yeniden şifre girmektir.
struct SettingsView: View {

    let onLoggedOut: () -> Void
    let onSessionLost: () -> Void

    /// Yeni şifrenin asgari uzunluğu; sunucudaki kuralın (AdminCredentialsManager) aynısı.
    private static let minPasswordLength = 10

    private let settings = ServiceLocator.settings!
    private let repository = ServiceLocator.repository!

    @State private var changing = false
    @State private var current = ""
    @State private var new = ""
    @State private var repeatNew = ""
    @State private var busy = false
    @State private var message: String?
    @State private var messageIsError = false
    @State private var confirmLogout = false

    @Environment(\.dismiss) private var dismiss

    private var mismatch: Bool { !repeatNew.isEmpty && new != repeatNew }
    private var canSubmit: Bool {
        !busy && !current.isEmpty && new.count >= Self.minPasswordLength && new == repeatNew
    }
    private var version: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent(Strings.settingsUser, value: settings.adminUsername)
                    LabeledContent(Strings.settingsInstagram, value: "@\(settings.igUsername)")
                }

                Section {
                    if !changing {
                        Button(Strings.settingsChangePassword) {
                            changing = true
                            message = nil
                        }
                        .accessibilityIdentifier("changePasswordButton")
                    } else {
                        SecureField(Strings.settingsCurrentPassword, text: $current)
                            .textContentType(.password)
                            .accessibilityIdentifier("currentPassword")
                        SecureField(Strings.settingsNewPassword, text: $new)
                            .textContentType(.newPassword)
                            .accessibilityIdentifier("newPassword")
                        SecureField(Strings.settingsRepeatPassword, text: $repeatNew)
                            .textContentType(.newPassword)
                            .accessibilityIdentifier("repeatPassword")
                        if mismatch {
                            Text(Strings.settingsPasswordMismatch)
                                .font(.footnote)
                                .foregroundStyle(.red)
                        }
                        HStack {
                            Button(Strings.cancel, role: .cancel) {
                                changing = false
                                current = ""; new = ""; repeatNew = ""
                            }
                            .disabled(busy)
                            Spacer()
                            Button(Strings.settingsChangePassword) { submit() }
                                .buttonStyle(.borderedProminent)
                                .disabled(!canSubmit)
                                .accessibilityIdentifier("submitPassword")
                        }
                    }
                    if let message {
                        Text(message)
                            .foregroundStyle(messageIsError ? Color.red : Color.accentColor)
                            .accessibilityIdentifier("settingsMessage")
                    }
                }

                Section {
                    Button(Strings.logout, role: .destructive) { confirmLogout = true }
                        .accessibilityIdentifier("logoutButton")
                }

                if !version.isEmpty {
                    Section {
                        LabeledContent(Strings.settingsVersion, value: version)
                    }
                }
            }
            // Kaydırınca klavye kapanıyor; altta duran düğmelere her zaman erişilebilsin.
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle(Strings.settings)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button(Strings.cancel) { dismiss() }
                }
            }
            .confirmationDialog(
                Strings.settingsLogoutTitle,
                isPresented: $confirmLogout,
                titleVisibility: .visible
            ) {
                Button(Strings.logout, role: .destructive) {
                    Task {
                        await repository.logout()
                        onLoggedOut()
                    }
                }
                Button(Strings.cancel, role: .cancel) {}
            } message: {
                Text(Strings.settingsLogoutBody)
            }
        }
    }

    private func submit() {
        // Klavye kapanıyor: açık kalırsa altındaki "Çıkış yap" düğmesine dokunulamıyordu (şifre
        // alanları kalktığı hâlde klavye yerinde kalıyor).
        UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil
        )
        busy = true
        message = nil
        Task {
            defer { busy = false }
            do {
                try await repository.changePassword(current: current, new: new)
                current = ""; new = ""; repeatNew = ""
                changing = false
                message = Strings.settingsPasswordChanged
                messageIsError = false
            } catch is UnauthorizedError {
                // Oturum bitti: şifreyi değiştirmenin anlamı kalmadı, giriş ekranına dönülüyor.
                onSessionLost()
            } catch let error as PasswordChangeError {
                message = error.message
                messageIsError = true
            } catch {
                // Ağ yok: şifrenin değişip değişmediği belli değil, ama değişmediğini varsaymak güvenli.
                message = Strings.settingsPasswordFailed
                messageIsError = true
            }
        }
    }
}

import SwiftUI

struct LoginView: View {

    let onAuthenticated: (String) -> Void

    @State private var model = LoginViewModel()

    var body: some View {
        Group {
            if model.probing {
                VStack(spacing: 16) {
                    ProgressView().tint(.white)
                    Text(Strings.checkingSession).foregroundStyle(.white)
                }
            } else {
                form
            }
        }
        .task { await model.probeExistingSession() }
        .onChange(of: model.authenticated) { _, igId in
            if let igId { onAuthenticated(igId) }
        }
    }

    private var form: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(Strings.loginTitle)
                    .font(.largeTitle.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.bottom, 12)

                field(Strings.loginServer, text: $model.baseURL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                field(Strings.loginUsername, text: $model.username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                field(Strings.loginPassword, text: $model.password, secure: true)

                field(Strings.loginAccount, text: $model.igUsername, prefix: "@")
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()

                if let error = model.error {
                    Text(message(for: error))
                        .foregroundStyle(.red)
                        .padding(.top, 4)
                }

                Button {
                    Task { await model.submit() }
                } label: {
                    HStack {
                        if model.submitting {
                            ProgressView().tint(.white).padding(.trailing, 8)
                        }
                        Text(Strings.loginSubmit)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.submitting || model.username.isEmpty || model.password.isEmpty)
                .padding(.top, 12)
            }
            .padding(24)
        }
        .scrollDismissesKeyboard(.interactively)
    }

    @ViewBuilder
    private func field(
        _ label: String,
        text: Binding<String>,
        secure: Bool = false,
        prefix: String? = nil
    ) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.white.opacity(0.6))
            HStack(spacing: 2) {
                if let prefix {
                    Text(prefix).foregroundStyle(.white.opacity(0.5))
                }
                Group {
                    if secure {
                        SecureField("", text: text)
                    } else {
                        TextField("", text: text)
                    }
                }
                .foregroundStyle(.white)
                .onChange(of: text.wrappedValue) { _, _ in model.clearError() }
            }
            .padding(12)
            .background(Color.white.opacity(0.08), in: .rect(cornerRadius: 10))
        }
    }

    private func message(for error: LoginError) -> String {
        switch error {
        case .credentials: Strings.loginFailed
        case .accountNotFound: Strings.loginAccountNotFound
        case .network: Strings.loginNetworkError
        }
    }
}

import Foundation
import Observation

enum LoginError {
    case credentials
    case accountNotFound
    case serverAddress
    case network
}

@MainActor
@Observable
final class LoginViewModel {

    var baseURL: String
    var username: String
    var password: String = ""
    var igUsername: String

    private(set) var probing = true
    private(set) var submitting = false
    private(set) var error: LoginError?

    /// Set once the session is known good; carries the resolved Instagram id.
    private(set) var authenticated: String?

    private let repository = ServiceLocator.repository!
    private let settings = ServiceLocator.settings!

    init() {
        baseURL = settings.baseURL
        username = settings.adminUsername
        igUsername = settings.igUsername
    }

    func clearError() {
        error = nil
    }

    /// A stored cookie may still be valid (7-day server session). Resolving the account both
    /// proves that and produces the id the gallery needs, so it replaces a separate ping.
    func probeExistingSession() async {
        guard probing else { return }
        guard !username.trimmingCharacters(in: .whitespaces).isEmpty else {
            probing = false
            return
        }
        // Resolving may not touch the network (numeric ids and known handles are answered
        // locally), so the session has to be proven with a real authenticated call.
        let igId = try? await repository.resolveAccount(igUsername).igId
        if let igId, await repository.isSessionValid(igId: igId) {
            authenticated = igId
        } else {
            probing = false
        }
    }

    func submit() async {
        guard !submitting else { return }
        submitting = true
        error = nil
        do {
            let ok = try await repository.login(
                baseURL: baseURL.trimmingCharacters(in: .whitespaces),
                username: username.trimmingCharacters(in: .whitespaces),
                password: password
            )
            guard ok else {
                submitting = false
                error = .credentials
                return
            }
            settings.adminUsername = username
            settings.igUsername = igUsername
            authenticated = try await repository.resolveAccount(igUsername).igId
        } catch is AccountNotFoundError {
            submitting = false
            error = .accountNotFound
        } catch is InvalidServerAddressError {
            submitting = false
            error = .serverAddress
        } catch is UnauthorizedError {
            submitting = false
            error = .credentials
        } catch {
            submitting = false
            self.error = .network
        }
    }
}

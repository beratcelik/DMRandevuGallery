import Foundation

/// Keeps the admin session cookie across app launches, so a returning operator skips login for
/// the full 7-day server-side session lifetime.
///
/// `HTTPCookieStorage.shared` already persists on its own, so this exists for the two things it
/// does not give us: a way to hand the live cookies to AVFoundation, which does its own HTTP and
/// will not read the shared jar, and a single place to drop the session when the server rejects it.
final class CookieStore {

    private let storage: HTTPCookieStorage

    init(storage: HTTPCookieStorage = .shared) {
        self.storage = storage
        // Without this the jar is per-request-only for tasks we create from a custom session.
        storage.cookieAcceptPolicy = .always
    }

    /// Cookies to attach to a request for `url`, in the form AVFoundation's asset options want.
    func cookies(for url: URL) -> [HTTPCookie] {
        storage.cookies(for: url) ?? []
    }

    func clear() {
        for cookie in storage.cookies ?? [] {
            storage.deleteCookie(cookie)
        }
    }
}

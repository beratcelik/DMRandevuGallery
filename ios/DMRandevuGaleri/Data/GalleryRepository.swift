import Foundation

/// Every call the app makes against the DMRandevu admin API. All endpoints are gated by the
/// express-session cookie, which `URLSession`'s shared cookie jar carries automatically.
final class GalleryRepository {

    /// The login route rejects bot-shaped User-Agents (curl/python/java/…), so we announce
    /// ourselves explicitly instead of relying on the default.
    static let userAgent = "DMRandevuGaleri/1.0 (iOS)"

    private let session: URLSession
    private let settings: SettingsStore
    private let cookies: CookieStore
    private let decoder = JSONDecoder()

    /// Blocks the redirect on the login route. Kept as one long-lived object because a task
    /// delegate is held weakly for the life of the task only.
    private let redirectBlocker = RedirectBlocker()

    init(session: URLSession, settings: SettingsStore, cookies: CookieStore) {
        self.session = session
        self.settings = settings
        self.cookies = cookies
    }

    private var base: String {
        var url = settings.baseURL
        while url.hasSuffix("/") { url.removeLast() }
        return url
    }

    /// URL that streams a CDN video through the server-side proxy (Range-capable).
    func proxyURL(_ rawURL: String) -> URL {
        var components = URLComponents(string: "\(base)/admin/media-proxy")!
        components.queryItems = [URLQueryItem(name: "url", value: rawURL)]
        return components.url!
    }

    /// The login route answers with a 302 either way, so success is decided by the Location
    /// header — redirects must stay off or we would follow it and lose that signal.
    func login(baseURL: String, username: String, password: String) async throws -> Bool {
        settings.baseURL = baseURL
        var trimmed = baseURL
        while trimmed.hasSuffix("/") { trimmed.removeLast() }

        var request = URLRequest(url: URL(string: "\(trimmed)/admin/auth/login")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.httpBody = formBody(["username": username, "password": password])

        // The Set-Cookie on this very 302 is what the cookie jar stores.
        let (_, response) = try await session.data(for: request, delegate: redirectBlocker)
        guard let http = response as? HTTPURLResponse else { return false }
        let location = http.value(forHTTPHeaderField: "Location") ?? ""
        let redirected = (300..<400).contains(http.statusCode)
        return redirected && !location.contains("/admin/login")
    }

    /// Account identifier → Instagram id.
    ///
    /// A numeric entry is already an id and is used as-is — the same passthrough the server does.
    /// That also means the app can talk to a server that does not carry the resolve endpoint yet,
    /// as long as the id is typed instead of the @handle.
    func resolveAccount(_ igUsername: String) async throws -> ResolveResponse {
        var account = igUsername.trimmingCharacters(in: .whitespaces)
        if account.hasPrefix("@") { account.removeFirst() }
        if !account.isEmpty, account.allSatisfy(\.isNumber) {
            return ResolveResponse(igId: account, username: account)
        }

        var components = URLComponents(string: "\(base)/admin/media-gallery-resolve")!
        components.queryItems = [URLQueryItem(name: "username", value: account)]
        let (data, response) = try await session.data(from: components.url!)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 404 {
            // Either the account is unknown, or this server predates the resolve endpoint. Fall
            // back to the handles we already know the id for, so the app keeps working against a
            // server that has not been updated yet.
            if let known = Self.knownAccounts[account.lowercased()] {
                return ResolveResponse(igId: known, username: account)
            }
            throw AccountNotFoundError()
        }
        try check(status)
        return try decoder.decode(ResolveResponse.self, from: data)
    }

    /// Cheapest authenticated call that proves the stored session cookie is still good.
    func isSessionValid(igId: String) async -> Bool {
        do {
            _ = try await loadPage(igId: igId, offset: 0, limit: 1)
            return true
        } catch {
            return false
        }
    }

    func loadPage(igId: String, offset: Int, limit: Int) async throws -> GalleryPage {
        var components = URLComponents(string: "\(base)/admin/media-gallery-page")!
        components.queryItems = [
            URLQueryItem(name: "igId", value: igId),
            URLQueryItem(name: "offset", value: String(offset)),
            URLQueryItem(name: "limit", value: String(limit))
        ]
        let (data, response) = try await session.data(from: components.url!)
        try check((response as? HTTPURLResponse)?.statusCode ?? 0)
        return try decoder.decode(GalleryPage.self, from: data)
    }

    /// Deletes the whole conversation. A 404 means it is already gone — same end state.
    func deleteConversation(salonId: String, clientId: String) async throws {
        var request = URLRequest(url: URL(string: "\(base)/admin/conversation/\(salonId)/\(clientId)")!)
        request.httpMethod = "DELETE"
        let (_, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 { throw UnauthorizedError() }
        if !(200..<300).contains(status) && status != 404 { throw HTTPStatusError(code: status) }
    }

    /// OpenAI-backed caption; slow enough (10-30 s) to need its own read timeout.
    func generateCaption(
        salonId: String,
        clientId: String,
        rawMediaURL: String,
        manualExplanation: String? = nil
    ) async throws -> String {
        var payload: [String: String] = [
            "salonId": salonId,
            "clientId": clientId,
            "mediaUrl": rawMediaURL
        ]
        if let manualExplanation, !manualExplanation.trimmingCharacters(in: .whitespaces).isEmpty {
            payload["manualExplanation"] = manualExplanation
        }

        var request = URLRequest(url: URL(string: "\(base)/admin/generate-caption")!)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        request.timeoutInterval = 90

        let (data, response) = try await session.data(for: request)
        try check((response as? HTTPURLResponse)?.statusCode ?? 0)
        return try decoder.decode(CaptionResponse.self, from: data).caption
    }

    /// Cookies for the proxy host, so AVFoundation can stream a video the session gates.
    func sessionCookies() -> [HTTPCookie] {
        cookies.cookies(for: URL(string: base) ?? URL(string: Self.defaultHost)!)
    }

    func clearSession() {
        cookies.clear()
    }

    /// Maps the server's 401 onto a typed failure the UI reacts to; everything else is generic.
    private func check(_ status: Int) throws {
        if status == 401 { throw UnauthorizedError() }
        if !(200..<300).contains(status) { throw HTTPStatusError(code: status) }
    }

    private func formBody(_ fields: [String: String]) -> Data {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return fields
            .map { key, value in
                let encoded = value.addingPercentEncoding(withAllowedCharacters: allowed) ?? ""
                return "\(key)=\(encoded)"
            }
            .joined(separator: "&")
            .data(using: .utf8) ?? Data()
    }

    private static let defaultHost = SettingsStore.defaultBaseURL

    /// Handle → Instagram id, for servers without /admin/media-gallery-resolve.
    private static let knownAccounts = [
        "trafik_cezasi": "17841468848724091",
        "trafykamerasi": "17841472755272054"
    ]
}

/// Refuses every redirect, turning the login route's 302 into a response we can read.
private final class RedirectBlocker: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest
    ) async -> URLRequest? {
        nil
    }
}

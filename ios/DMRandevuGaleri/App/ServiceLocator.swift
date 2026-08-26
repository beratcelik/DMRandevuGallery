import Foundation

/// Hand-rolled singletons. One user, one screen pair, a handful of dependencies — a DI framework
/// would cost more than it saves here.
@MainActor
enum ServiceLocator {

    private(set) static var settings: SettingsStore!
    private(set) static var cookies: CookieStore!
    private(set) static var session: URLSession!
    private(set) static var repository: GalleryRepository!
    private(set) static var exporter: VideoExporter!
    private(set) static var downloader: Downloader!

    static func start() {
        guard repository == nil else { return }

        settings = SettingsStore()
        cookies = CookieStore()

        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 30
        configuration.httpCookieStorage = .shared
        configuration.httpCookieAcceptPolicy = .always
        configuration.httpShouldSetCookies = true
        // The login route rejects bot-shaped User-Agents, so we announce ourselves explicitly
        // instead of relying on the default.
        configuration.httpAdditionalHeaders = ["User-Agent": GalleryRepository.userAgent]
        session = URLSession(configuration: configuration)

        repository = GalleryRepository(session: session, settings: settings, cookies: cookies)
        exporter = VideoExporter()
        downloader = Downloader(session: session, repository: repository, exporter: exporter)
    }
}

import Foundation

/// Remembers everything needed to log back in except the password.
final class SettingsStore {

    static let defaultBaseURL = "https://dmrandevu.com"
    static let defaultIGAccount = "trafik_cezasi"

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        // UserDefaults answers `false` for an absent Bool, which happens to be the wanted
        // default for all three filters — but not for the plate speed, which starts on.
        defaults.register(defaults: [Key.fastPlates: true])
    }

    var baseURL: String {
        get { defaults.string(forKey: Key.baseURL) ?? Self.defaultBaseURL }
        set {
            var trimmed = newValue
            while trimmed.hasSuffix("/") { trimmed.removeLast() }
            defaults.set(trimmed, forKey: Key.baseURL)
        }
    }

    var adminUsername: String {
        get { defaults.string(forKey: Key.admin) ?? "" }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespaces), forKey: Key.admin) }
    }

    var igUsername: String {
        get { defaults.string(forKey: Key.ig) ?? Self.defaultIGAccount }
        set {
            let handle = newValue.trimmingCharacters(in: .whitespaces)
            defaults.set(handle.hasPrefix("@") ? String(handle.dropFirst()) : handle, forKey: Key.ig)
        }
    }

    /// Blur faces in every exported video. Off by default: it re-encodes, which takes a while.
    var blurFaces: Bool {
        get { defaults.bool(forKey: Key.blurFaces) }
        set { defaults.set(newValue, forKey: Key.blurFaces) }
    }

    /// Blur licence plates in every exported video. Off by default, like the face filter.
    var blurPlates: Bool {
        get { defaults.bool(forKey: Key.blurPlates) }
        set { defaults.set(newValue, forKey: Key.blurPlates) }
    }

    /// Run the plate detector at the smaller input size: quicker, and it finds fewer of the
    /// smaller plates. On by default while the trade is being lived with.
    var fastPlates: Bool {
        get { defaults.bool(forKey: Key.fastPlates) }
        set { defaults.set(newValue, forKey: Key.fastPlates) }
    }

    /// Drift the account handle across every exported video, so a repost still shows whose it is.
    var watermark: Bool {
        get { defaults.bool(forKey: Key.watermark) }
        set { defaults.set(newValue, forKey: Key.watermark) }
    }

    private enum Key {
        static let baseURL = "base_url"
        static let admin = "admin_username"
        static let ig = "ig_username"
        static let blurFaces = "blur_faces"
        static let blurPlates = "blur_plates"
        static let fastPlates = "fast_plates"
        static let watermark = "watermark"
    }
}

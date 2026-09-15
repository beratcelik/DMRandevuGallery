import Foundation

/// Remembers everything needed to log back in except the password.
final class SettingsStore {

    static let defaultBaseURL = "https://dmrandevu.com"
    static let defaultIhbarBaseURL = "https://ihbar.lega.digital"
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

    /// Trafik İhbar sunucusunun adresi.
    ///
    /// DMRandevu'dan AYRI bir sistem ve ayrı bir sunucu; ``baseURL`` ile
    /// karıştırılmamalı. Ayrı durmasının sebebi yalnızca düzen değil: galeri
    /// sunucusu değiştiğinde (yerel bir kopyaya bakarken) ihbar sunucusunun
    /// onunla birlikte kaymaması gerekiyor.
    var ihbarBaseURL: String {
        get { defaults.string(forKey: Key.ihbarBaseURL) ?? Self.defaultIhbarBaseURL }
        set {
            var trimmed = newValue.trimmingCharacters(in: .whitespaces)
            while trimmed.hasSuffix("/") { trimmed.removeLast() }
            defaults.set(trimmed, forKey: Key.ihbarBaseURL)
        }
    }

    /// İhbar sunucusunun cihaz belirteci ("tid_…").
    ///
    /// Yönetici konsolunda bir kez üretiliyor ve bir daha gösterilmiyor, o yüzden
    /// buraya bir kez yapıştırılıp saklanıyor. Boşken ihlal düğmesi ağa hiç
    /// çıkmıyor ve bunu kullanıcıya söylüyor.
    var ihbarToken: String {
        get { defaults.string(forKey: Key.ihbarToken) ?? "" }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespaces), forKey: Key.ihbarToken) }
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

    /// Akış tanıtımının en son gösterildiği yapı ("1.0+1"); boş ise hiç gösterilmedi.
    ///
    /// BAYRAK DEĞİL YAPI ADI: hareketler değiştiğinde yapı numarası artırılarak tanıtım bir kez
    /// daha gösterilebiliyor. Düz bir Bool olsaydı, değişen bir hareketi öğrenmenin tek yolu
    /// uygulamayı silip yeniden kurmak olurdu.
    var tourShownBuild: String {
        get { defaults.string(forKey: Key.tourBuild) ?? "" }
        set { defaults.set(newValue, forKey: Key.tourBuild) }
    }

    /// Beep over Turkish swearing in every exported video. Off by default: it needs a quarter of
    /// a gigabyte of models downloaded before it can do anything.
    var censorAudio: Bool {
        get { defaults.bool(forKey: Key.censorAudio) }
        set { defaults.set(newValue, forKey: Key.censorAudio) }
    }

    /// Whether the censor listens to the video or only beeps what was marked by hand.
    ///
    /// Automatic on a clip the recognizer manages; by hand on the ones it does not, where running
    /// it costs minutes to be told what the operator has already said.
    var censorByHand: Bool {
        get { defaults.bool(forKey: Key.censorByHand) }
        set { defaults.set(newValue, forKey: Key.censorByHand) }
    }

    /// Beep milder insults too, not only outright profanity. Off by default — "manyak" fired on a
    /// clip where nobody swore.
    var censorInsults: Bool {
        get { defaults.bool(forKey: Key.censorInsults) }
        set { defaults.set(newValue, forKey: Key.censorInsults) }
    }

    private enum Key {
        static let baseURL = "base_url"
        static let ihbarBaseURL = "ihbar_base_url"
        static let ihbarToken = "ihbar_token"
        static let admin = "admin_username"
        static let ig = "ig_username"
        static let blurFaces = "blur_faces"
        static let blurPlates = "blur_plates"
        static let fastPlates = "fast_plates"
        static let watermark = "watermark"
        static let censorAudio = "censor_audio"
        static let censorInsults = "censor_insults"
        static let censorByHand = "censor_by_hand"
        static let tourBuild = "tour_shown_build"
    }
}

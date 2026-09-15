import Foundation
import UIKit

/// Hands a video straight to one of Instagram's composers.
///
/// On iOS the hand-off is a URL scheme plus a pasteboard item rather than an intent, but the shape
/// of the problem is the same as on Android: Stories takes a video directly, Reels does not.
///
/// Note on captions: no Instagram entry point accepts one. The caption can only travel via the
/// clipboard for the operator to paste.
enum InstagramSharing {

    /// Meta uygulama kimliğimiz. Instagram gelen paylaşımı BUNUNLA tanıyor.
    ///
    /// Bir süre burada Meta'nın örnek belgelerinden gelen herkese açık bir kimlik duruyordu ve
    /// bu uygulama adına kayıtlı olmadığı için Instagram paylaşımı kendi penceresinde
    /// reddediyordu. Ayrıntısı Android ikizinde: `InstagramSharing.META_APP_ID`.
    static let metaAppID = "1059486250258693"

    /// Reels bestecisini doğrudan açmayı dene. AÇIK; bilinen riski Android ikizindeki
    /// `REELS_COMPOSER_ENABLED` başlığında yazıyor.
    static let reelsComposerEnabled = true

    private static let storiesScheme = "instagram-stories://share?source_application="
    private static let reelsScheme = "instagram-reels://share"
    private static let appScheme = "instagram://app"

    /// The pasteboard key Instagram reads a Story's background video from.
    private static let backgroundVideoKey = "com.instagram.sharedSticker.backgroundVideo"

    /// Reels aynı anahtarı okuyor, kimliği ise ayrı bir anahtardan alıyor — Hikaye'deki gibi
    /// adresin içinden değil.
    private static let appIDKey = "com.instagram.sharedSticker.appID"

    @MainActor
    static var isInstalled: Bool {
        guard let url = URL(string: appScheme) else { return false }
        return UIApplication.shared.canOpenURL(url)
    }

    /// Opens the Stories composer with `video` already loaded.
    ///
    /// The video travels on the pasteboard rather than in the URL, which is how Instagram's
    /// documented Stories hand-off works. It is given a short expiry so a large video is not left
    /// sitting in the system pasteboard afterwards.
    @MainActor
    static func openStoryComposer(video: URL) -> Bool {
        guard let url = URL(string: storiesScheme + metaAppID),
              UIApplication.shared.canOpenURL(url),
              let data = try? Data(contentsOf: video) else { return false }

        UIPasteboard.general.setItems(
            [[backgroundVideoKey: data]],
            options: [.expirationDate: Date().addingTimeInterval(pasteboardLifetime)]
        )
        UIApplication.shared.open(url)
        return true
    }

    /// Reels bestecisini videoyla açar. Yalnızca `reelsComposerEnabled` açıkken çağrılmalı.
    ///
    /// `false` dönmesi "Instagram bu adresi karşılamadı" demek; kimliği reddetmesi bundan ayrı
    /// bir şey ve buradan görünmüyor — çağıran her hâlükârda fotoğraflara kaydeden yolu elinde
    /// tutmalı.
    @MainActor
    static func openReelComposer(video: URL) -> Bool {
        guard let url = URL(string: reelsScheme),
              UIApplication.shared.canOpenURL(url),
              let data = try? Data(contentsOf: video) else { return false }

        UIPasteboard.general.setItems(
            [[backgroundVideoKey: data, appIDKey: metaAppID]],
            options: [.expirationDate: Date().addingTimeInterval(pasteboardLifetime)]
        )
        UIApplication.shared.open(url)
        return true
    }

    /// Instagram'ı açar; video zaten fotoğraflarda, caption panoda bekliyor.
    ///
    /// Reels bestecisi bu yoldan AÇILMIYOR ve sebebi bir Meta onayı değil: giriş noktası kendi
    /// Meta uygulama kimliğimizi istiyor, elimizdeki kimlik bizim değil. Bu yüzden video önce
    /// fotoğraflara kaydediliyor ve Reels'te oradan seçiliyor.
    @MainActor
    static func openInstagram() -> Bool {
        guard let url = URL(string: appScheme), UIApplication.shared.canOpenURL(url) else {
            return false
        }
        UIApplication.shared.open(url)
        return true
    }

    @MainActor
    static func copyCaption(_ caption: String) {
        UIPasteboard.general.string = caption
    }

    /// Long enough for the operator to finish the post, short enough that a video is not left on
    /// the pasteboard for the rest of the day.
    private static let pasteboardLifetime: TimeInterval = 5 * 60
}

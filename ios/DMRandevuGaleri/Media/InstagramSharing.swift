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

    /// Caption'ın pano gösterimi.
    ///
    /// NEDEN AYNI ÖĞENİN İÇİNDE: iOS'ta videoyu besteciye taşıyan şey panonun kendisi ve
    /// `setItems` panoyu KOMPLE değiştiriyor. Caption'ı ayrıca `UIPasteboard.general.string`
    /// ile yazmak işe yaramıyordu — bir satır sonraki `setItems` onu siliyordu ve operatör
    /// Reels'e boş panoyla varıyordu. Aynı öğeye düz metin gösterimi eklendiğinde panonun
    /// `string` değeri caption oluyor, Instagram ise videoyu kendi anahtarından okumaya
    /// devam ediyor.
    private static let plainTextKey = "public.utf8-plain-text"

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
    static func openReelComposer(video: URL, caption: String? = nil) -> Bool {
        guard let url = URL(string: reelsScheme),
              UIApplication.shared.canOpenURL(url),
              let data = try? Data(contentsOf: video) else { return false }

        var item: [String: Any] = [backgroundVideoKey: data, appIDKey: metaAppID]
        // Caption AYNI ÖĞEDE de gidiyor; gerekçesi [plainTextKey] başlığında. Tek başına
        // yetmiyor, aşağıdaki gecikmeli yazma onun yedeği.
        if let caption, !caption.isEmpty { item[plainTextKey] = caption }

        UIPasteboard.general.setItems(
            [item],
            options: [.expirationDate: Date().addingTimeInterval(pasteboardLifetime)]
        )
        UIApplication.shared.open(url)

        if let caption, !caption.isEmpty { writeCaptionAfterHandoff(caption) }
        return true
    }

    /// Caption'ı, Instagram videoyu panodan ALDIKTAN SONRA tek başına panoya yazar.
    ///
    /// NEDEN GECİKMELİ, NEDEN İKİNCİ KEZ: devir anında panoya konan metnin Reels'te
    /// yapıştırılamadığı görüldü ve bunun iki makul sebebi var, ikisi de buradan
    /// görünmüyor. (1) Instagram videoyu tükettikten sonra panoyu temizliyor olabilir —
    /// Meta'nın kendi belgeleri bile paylaşan uygulamaya "cihazda bıraktığın geçici
    /// dosyaları temizle" diyor. (2) Öğeye konan son kullanma tarihi caption'ı da
    /// kapsıyor: video ilk saniyede tüketiliyor ama caption ancak kırpma ve "İleri"den
    /// SONRA gerekiyor, yani beş dakika gerçekçi bir düzenlemeye yetmeyebiliyor.
    ///
    /// Bu yazma ikisini birden kapatıyor: video alındıktan sonra çalışıyor, düz metin
    /// olarak yazıyor ve son kullanma tarihi taşımıyor.
    ///
    /// NEDEN ARKA PLAN GÖREVİ: `open` çağrısından hemen sonra uygulama arka plana
    /// düşüyor ve iOS birkaç saniye içinde askıya alıyor. Görev, bekleme boyunca
    /// süreci ayakta tutuyor. Panoya YAZMAK arka planda serbest; kısıtlı olan okumak.
    @MainActor
    private static func writeCaptionAfterHandoff(_ caption: String) {
        Task { @MainActor in
            var task = UIBackgroundTaskIdentifier.invalid
            task = UIApplication.shared.beginBackgroundTask(withName: "reels-caption") {
                UIApplication.shared.endBackgroundTask(task)
                task = .invalid
            }
            try? await Task.sleep(nanoseconds: UInt64(captionHandoffDelay * 1_000_000_000))
            UIPasteboard.general.string = caption
            if task != .invalid { UIApplication.shared.endBackgroundTask(task) }
        }
    }

    /// Instagram'ın videoyu panodan almasına bırakılan süre.
    ///
    /// Erken yazmak videoyu panodan silip devri bozardı; geç yazmak arka plan süresini
    /// tüketirdi. Üç saniye, besteci açılırken video zaten okunmuş oluyor.
    private static let captionHandoffDelay: Double = 3

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

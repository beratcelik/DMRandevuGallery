import Foundation

/// Every piece of text the operator sees. The app ships in Turkish only, exactly as the Android
/// build does, so these sit here rather than in a string catalogue that would have one language
/// in it.
enum Strings {

    static let appName = "DMRandevu Galeri"

    // MARK: - Login

    static let loginTitle = "DMRandevu Galeri"
    static let loginServer = "Sunucu adresi"
    static let loginUsername = "Yönetici kullanıcı adı"
    static let loginPassword = "Şifre"
    static let loginAccount = "Instagram hesabı"
    static let loginSubmit = "Giriş Yap"
    static let loginFailed = "Giriş başarısız. Bilgileri kontrol edin."
    static let loginAccountNotFound = "Instagram hesabı bulunamadı"
    static let loginNetworkError = "Sunucuya ulaşılamadı"
    static let loginBadServer = "Sunucu adresi geçersiz"
    static let checkingSession = "Oturum kontrol ediliyor…"

    // MARK: - Gallery

    static let emptyGallery = "Videolu konuşma bulunamadı"
    static let videoExpired = "Video süresi doldu"
    static let videoFailed = "Video yüklenemedi"
    static let videoRetry = "Tekrar dene"
    static let videoRefreshFailed =
        "Yeni bağlantı alınamadı — video sunucuda da yok olabilir"

    // MARK: - Actions

    static let download = "İndir"
    static let downloading = "İndiriliyor…"
    static let downloadDone = "Galeriye kaydedildi"
    static let downloadFailed = "İndirme başarısız"
    static let caption = "Caption"
    static let story = "Hikaye"
    static let reels = "Reels"
    static let instagramMissing = "Instagram yüklü değil"
    static let reelsReady = "Video galeriye kaydedildi, caption kopyalandı — Reels'te videoyu seçip caption'ı yapıştırın"
    static let reelsReadyNoCaption = "Video galeriye kaydedildi — caption üretilemedi, Reels'te videoyu seçin"
    static let photosDenied = "Fotoğraflar erişimi yok — Ayarlar'dan izin verin"

    // MARK: - Filters

    static let faceBlurToggle = "Yüz filtresi"
    static let faceBlurOn = "Yüz filtresi açık — dışa aktarılan videolarda yüzler gizlenecek"
    static let faceBlurOff = "Yüz filtresi kapalı"
    static let plateBlurToggle = "Plaka filtresi"
    static let plateBlurOn = "Plaka filtresi açık — dışa aktarılan videolarda plakalar gizlenecek"
    static let plateBlurOff = "Plaka filtresi kapalı"
    static let platesFastBadge = "Hızlı tarama"
    static let platesFast = "Plaka taraması: hızlı — daha çabuk biter, uzaktaki plakaları daha sık kaçırır"
    static let platesThorough = "Plaka taraması: titiz — daha çok plaka bulur, yaklaşık iki kat sürer"
    static let watermarkToggle = "Filigran"
    static let watermarkOn = "Filigran açık — hesap adı videonun üzerinde gezinecek"
    static let watermarkOff = "Filigran kapalı"
    static let censorAudioToggle = "Küfür filtresi"
    static let censorAudioOn =
        "Küfür filtresi açık — küfürler bip sesiyle kapatılacak, arka plandaki ses devam edecek"
    static let censorAudioOff = "Küfür filtresi kapalı"
    static func censorModelsDownloading(_ percent: Int) -> String {
        "Küfür filtresi hazırlanıyor — %\(percent)"
    }
    static let censorModelsFailed =
        "Küfür filtresi indirilemedi — bağlantıyı kontrol edip tekrar deneyin"
    static let exportFailed = "Video işlenemedi — aktarılmadı. Ham haliyle aktarmak için filtreleri kapatın."

    static func progress(_ percent: Int) -> String { "%\(percent)" }

    // MARK: - Playback

    static func playbackSpeed(_ times: Int) -> String { "\(times)×" }
    static let resume = "Devam et"

    // MARK: - Caption sheet

    static let captionTitle = "✨ Instagram Caption"
    static let captionGenerating = "Caption üretiliyor…"
    static let captionFailed = "Caption üretilemedi"
    static let captionExplanationHint = "Videoyu açıklayın (isteğe bağlı)"
    static let captionRegenerate = "Açıklamayla yeniden üret"
    static let captionShare = "Instagram'da paylaş"
    static let captionCopied = "Caption panoya kopyalandı"
    static let captionCopy = "Kopyala"
    static let sharePreparing = "Video hazırlanıyor…"
    static let shareFailed = "Paylaşım başarısız"
    static let close = "Kapat"
}

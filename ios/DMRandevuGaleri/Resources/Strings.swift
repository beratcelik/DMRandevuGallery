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
    static let loginIhbarServer = "İhbar sunucusu"
    static let loginIhbarToken = "İhbar cihaz belirteci (isteğe bağlı)"
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
    static let reelsComposerReady = "Reels açılıyor — caption panoda, yapıştırmanız yeterli"
    static let reelsComposerNoCaption = "Reels açılıyor — caption üretilemedi"
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
    static let censorAuto = "Küfür filtresi: otomatik — konuşma taranıp küfürler bulunacak"
    static let censorByHand =
        "Küfür filtresi: elle — sadece sizin işaretledikleriniz bipleniyor, video taranmıyor"
    static let markHint = "Küfrü işaretle (basılı tut)"
    static let markHolding = "İşaretleniyor…"
    static let markRemove = "Buradakini sil"
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

    // MARK: - Trafik İhbar köprüsü

    static let ihbarMark = "İhlal olarak işaretle"
    static let ihbarMarking = "Gönderiliyor…"
    static let ihbarMarked = "İhlal olarak işaretlendi"
    static let ihbarApproved = "İhbar onaylandı"
    static let ihbarNeedsInfo = "İşaretlendi — bilgi eksik"
    static let ihbarBlocked = "İşaretlendi — gönderilemiyor"
    static let ihbarPending = "İhbar kaydı hazır değil"
    static let ihbarError = "İşaretlenemedi — dokunup tekrar deneyin"
    static let ihbarNetworkError = "İhbar sunucusuna ulaşılamadı"
    static let ihbarNoToken = "İhbar belirteci yok"
    static let ihbarNoTokenDetail = "Dokunun ve cihaz belirtecini yapıştırın"
    static func ihbarMissingFields(_ fields: String) -> String { "\(fields) eksik" }
    // Olumsuz dokunuşun KENDİ metinleri — olumlu yoldan ödünç ALINMIYOR.
    // Bir süre alınıyordu; gerekçe "hangi düğmenin konuştuğunu rengiyle konumu
    // söyler"di. Söylemiyor: hata hâlinde İKİ düğme de kırmızıya dönüyor, yani
    // rengin ayırt ediciliği tam da metnin en çok gerektiği anda kayboluyor.
    // Üstelik ödünç alınan metin ("İşaretlenemedi") olumsuz düğmede BAŞARISIZ
    // OLANIN TERSİNİ söylüyordu — sahip, elemenin değil işaretlemenin
    // patladığını sanıp öteki düğmeye basar ve ihbarı emniyete gönderirdi.
    // Android tarafı bu ayrımı zaten yapıyor; metinler onunla birebir.
    static let ihbarNotViolation = "İhlal değil"
    static let ihbarNotViolationSending = "Eleniyor…"
    static let ihbarNotViolationMarked = "İhlal değil — elendi"
    static let ihbarNotViolationError = "Elenemedi — dokunup tekrar deneyin"
    static let ihbarTokenTitle = "İhbar cihaz belirteci"
    static let ihbarTokenExplain =
        "Yönetici konsolunda Cihazlar sayfasından üretilen belirteç. Bir kez yapıştırmanız yeterli."
    static let ihbarTokenHint = "tid_…"
    static let ihbarTokenSave = "Kaydet"
    static let cancel = "Vazgeç"

    // MARK: - Kaydırarak karar
    //
    // Android ile BİREBİR aynı metinler: aynı kararı iki telefonda iki farklı
    // cümleyle anlatmak, sahibin hangi uygulamada ne yaptığını hatırlamasını
    // gerektirirdi.

    static let swipeReport = "İHBAR ET"
    static let swipeDismiss = "İHLAL DEĞİL"
    static let swipeReported = "İhbar ediliyor"
    static let swipeDismissed = "Eleniyor"
    static let swipeUndo = "Geri al"
    static let swipeUndone = "Karar geri alındı"
    static func videoPosition(_ index: Int, _ total: Int) -> String { "\(index)/\(total)" }
    static let ihbarVerifiedPending = "Teyit alındı"
    static let ihbarVerifiedPendingDetail = "Kayıt açılınca ihbar edilecek"
    static func ihbarMediaCount(_ count: Int) -> String { "\(count) videoluk ihbar" }
    static let ihbarAlreadyReported = "Bu video zaten ihbar edildi"
    static let ihbarAlreadyDismissed = "Bu video zaten elendi"
    static let ihbarInFlight = "İstek yolda — bekleyin"

    // MARK: - Toplu eleme

    static let bulkDismiss = "Tümünü ele"
    static let bulkDismissTitle = "Bu müşterinin videoları elensin mi?"
    static func bulkDismissExplain(_ count: Int) -> String {
        "\(count) video \"ihlal değil\" olarak işaretlenecek. Onaylanmış ihbarlar atlanır; "
        + "onları geri çekmek için tek tek elemeniz gerekir."
    }
    static let bulkDismissConfirm = "Hepsini ele"
    static func bulkDismissResult(_ applied: Int, _ skipped: Int) -> String {
        "\(applied) video elendi, \(skipped) atlandı"
    }
    static let bulkDismissFailed = "Toplu eleme başarısız — tekrar deneyin"

    // MARK: - Playback

    static func playbackSpeed(_ times: Int) -> String { "\(times)×" }
    static let resume = "Devam et"

    // MARK: - Caption sheet

    static let captionTitle = "✨ Instagram Caption"
    static let captionGenerating = "Caption üretiliyor…"
    static let captionFailed = "Caption üretilemedi"
    static let captionExplanationHint = "Videoyu açıklayın (isteğe bağlı)"
    static let captionRegenerate = "Açıklamayla yeniden üret"
    static let captionShare = "Reels olarak paylaş"
    static let captionCopied = "Caption panoya kopyalandı"
    static let captionCopy = "Kopyala"
    static let sharePreparing = "Video hazırlanıyor…"
    static let shareFailed = "Paylaşım başarısız"
    static let close = "Kapat"

    // MARK: - Akış tanıtımı (kurulum başına bir kez)

    static let tourTitle = "Nasıl kullanılır"
    static let tourGestures = "Hareketler"
    static let tourButtons = "Düğmeler"
    static let tourDone = "Anladım"

    static let tourRightTitle = "Sağa at · İhbar et"
    static let tourRightDetail = "Videoda kural ihlali var. Karar üç saniye bekliyor; o sürede \"Geri al\"a basmak ya da videoya geri kaydırmak iptal ediyor."
    static let tourLeftTitle = "Sola at · İhbar etme"
    static let tourLeftDetail = "Video ihbardan düşüyor. Birden çok videolu bir ihbarda yalnızca bu video ayrılıyor, kalanı memurda duruyor."
    static let tourUpTitle = "Yukarı kaydır · Sonraki video"
    static let tourUpDetail = "Aynı müşterinin sıradaki videosu. Videoları bitince sonraki müşteriye geçiyor ve geride kalan konuşma siliniyor."
    static let tourBackTitle = "Aşağı kaydır · Vazgeç"
    static let tourBackDetail = "Karar verdiğin videoya geri dönmek kararı iptal ediyor; ayrıldığın müşteriye dönmek silmeyi iptal ediyor."
    static let tourBackDetailPlain = "Ayrıldığın müşteriye beş saniye içinde geri dönmek silmeyi iptal ediyor."
    static let tourTapTitle = "Dokun · Duraklat"
    static let tourTapDetail = "Bir daha dokunmak devam ettiriyor. Dokunuş aynı zamanda ilerleme çubuğunu getiriyor."
    static let tourHoldTitle = "Basılı tut · 3× hızlı"
    static let tourHoldDetail = "Parmağını kaldırınca normal hıza dönüyor."

    static let tourFaceDetail = "Dışa aktarılan videoda yüzleri bulanıklaştırır."
    static let tourPlateDetail = "Plakaları bulanıklaştırır. Uzun basmak hızlı ile titiz arasında geçiş yapar; köşedeki şimşek hızlı olduğunu gösterir."
    static let tourWatermarkDetail = "Videoya hesap filigranı basar. Oynatıcıda da görünür: burada ne görüyorsan dışa aktarılan da odur."
    static let tourCensorDetail = "Küfürleri bipler. Uzun basmak otomatik ile elle arasında geçiş yapar; ilk açılışta model dosyalarını indirir."
    static let tourBulkDetail = "Bu müşterinin karar verilmemiş videolarının hepsini tek seferde eler. Yalnızca en az iki video varken çıkar."
    static let tourDownloadDetail = "Videoyu seçili filtrelerle telefona kaydeder."
    static let tourStoryDetail = "Videoyu doğrudan Instagram Hikaye düzenleyicisine verir."
    static let tourReelsDetail = "Videoyu fotoğraflara kaydeder ve caption'ı panoya kopyalar; Reels'te videoyu seçip yapıştırırsın."
    static let tourCaptionDetail = "Konuşmadan yapay zekâ ile haber metni üretir."
}

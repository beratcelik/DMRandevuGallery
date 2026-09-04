import Foundation

// Trafik İhbar galeri köprüsünün tel üzerindeki sözleşmesi.
//
// NEDEN AYRI BİR DOSYA (Models.swift'e eklenmedi): bu alanlar BAŞKA bir
// sunucuya ait — https://ihbar.lega.digital, DMRandevu'dan tamamen ayrı bir
// sistem ve ayrı bir kimlik doğrulama. İkisini tek dosyada tutmak, hangi alanın
// hangi sunucuyla değiştiğini bir sonraki okuyucuya sildirirdi.
//
// Alan adları sunucudaki karşılıklarıyla BİREBİR aynı; hiçbiri burada
// "düzeltilmedi". Yeniden adlandırma, iki tarafın sözleşmesini tek taraflı
// değiştirmek olurdu ve derleyici bunu yakalayamaz.

// MARK: - İstek

/// Bir videoyu ihbar sisteminde tanımlayan anahtar kümesi.
///
/// Hiçbir alan zorunlu değil ve elde ne varsa HEPSİ gönderiliyor: sunucu en
/// güvenilir anahtardan başlayıp sırayla deniyor (mid → clientId+sentAt → adres
/// → sıra) ve hangisiyle eşleştiğini ``IhbarStatusItem/matchedBy`` alanında geri
/// söylüyor. Elimizdekinin tamamını göndermek, ileride galeri `mid` taşımaya
/// başladığında sunucu tarafında hiçbir değişiklik gerektirmemesi demek.
///
/// nil alanlar kodlanmıyor (Encodable sentezi Optional'lar için
/// `encodeIfPresent` kullanıyor), yani gövdede yalnızca gerçekten bilinenler
/// yer alıyor.
struct IhbarItem: Encodable {
    /// Gönderenin Instagram kimliği; sunucuda Reporter.igSenderId'nin ta kendisi.
    var clientId: String?
    /// Videonun kendi gönderim zamanı, ISO 8601. Sunucu epoch biçimlerini de kabul ediyor.
    var sentAt: String?
    /// HAM CDN adresi — vekil (media-proxy) adresi değil; gerekçesi ``ihbarItem(for:mediaIndex:)``'de.
    var url: String?
    /// Meta mesaj kimliği. Galeri bugün taşımıyor; sunucu yarın taşırsa diye duruyor.
    var mid: String?
    /// urls dizisindeki sıra. Sunucu bunu yalnızca durum sorgusunda kabul ediyor;
    /// onay ucunda bilerek yok sayıyor, o yüzden orada da gitmesi zararsız.
    var index: Int?
}

struct IhbarStatusRequest: Encodable {
    let items: [IhbarItem]
}

// MARK: - Yanıt

// Sunucu aşağıdaki iki yanıtta gösterdiğimizden fazlasını taşıyor: il, ilçe,
// plaka, güven puanı, videodaki konuşmanın metni. Burada YALNIZCA düğmenin
// okuduğu alanlar duruyor.
//
// İki sebebi var. Taşınan her fazla alan, bir sonraki okuyucuya "bu nerede
// gösteriliyor?" diye arattıracak ölü ağırlık; ve konuşma metni MUHABİRİN KENDİ
// SESİ — adını, adresini, telefonunu içerebiliyor ve bir galeri uygulamasının
// belleğinde durmasının hiçbir gerekçesi yok.
//
// Her yanıt tipi kendi `init(from:)`'ını yazıyor: Swift'in sentezlediği
// çözücü, varsayılan değeri olan alanlarda bile eksik anahtarda HATA fırlatır.
// Sunucu alanların bir kısmını yalnızca ilgili dalda gönderiyor (örneğin
// blockingFields sadece eksik bilgi dalında), yani sentezlenmiş çözücü sağlam
// bir yanıtı çözemezdi.

/// Toplu durum yanıtı. Öğeler İSTEK SIRASIYLA dönüyor, yani istemci kendi
/// listesiyle konumdan eşleştirebilir.
struct IhbarStatusResponse: Decodable {

    let items: [IhbarStatusItem]

    private enum CodingKeys: String, CodingKey {
        case items
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        items = try container.decodeIfPresent([IhbarStatusItem].self, forKey: .items) ?? []
    }
}

/// Tek bir videonun durumu.
///
/// Düğme yalnızca `state` alanına bakıyor, ham veritabanı durumuna değil:
/// sunucu bu kümeyi bilerek dar tutuyor, tam da mobil istemci sekiz durumluk bir
/// enum'u yeniden yorumlamak zorunda kalmasın diye.
struct IhbarStatusItem: Decodable {

    let state: String
    let stateLabel: String
    /// YEŞİL DÜĞMENİN dayandığı tek alan.
    let humanVerified: Bool
    let matchedBy: String?
    let violationCode: String?
    /// Eksik alanların onayı gerçekten ENGELLEYEN alt kümesi (il / tarih / medya).
    let blockingFields: [String]

    private enum CodingKeys: String, CodingKey {
        case state, stateLabel, humanVerified, matchedBy, violationCode, blockingFields
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        state = try container.decodeIfPresent(String.self, forKey: .state) ?? ""
        stateLabel = try container.decodeIfPresent(String.self, forKey: .stateLabel) ?? ""
        humanVerified = try container.decodeIfPresent(Bool.self, forKey: .humanVerified) ?? false
        matchedBy = try container.decodeIfPresent(String.self, forKey: .matchedBy)
        violationCode = try container.decodeIfPresent(String.self, forKey: .violationCode)
        blockingFields = try container.decodeIfPresent([String].self, forKey: .blockingFields) ?? []
    }
}

/// Onay dokunuşunun sonucu. Bulunamayan video da 200 döner; hata değil, cevaptır.
struct IhbarApproveResponse: Decodable {

    let state: String
    let stateLabel: String
    let matchedBy: String?
    let humanVerified: Bool
    let blockingFields: [String]
    /// Onayı durduran somut sebepler ("Açıklama çok kısa"); `message`'dan daha kesin.
    let problems: [String]
    /// Sunucunun kendi Türkçe cümlesi ("İhbar onaylandı ve 3 memura iletiliyor.").
    /// Kaç memura gittiği de, hangi alanın eksik olduğu da bunun içinde — o yüzden
    /// o alanlar ayrıca taşınmıyor.
    let message: String

    private enum CodingKeys: String, CodingKey {
        case state, stateLabel, matchedBy, humanVerified, blockingFields, problems, message
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        state = try container.decodeIfPresent(String.self, forKey: .state) ?? ""
        stateLabel = try container.decodeIfPresent(String.self, forKey: .stateLabel) ?? ""
        matchedBy = try container.decodeIfPresent(String.self, forKey: .matchedBy)
        humanVerified = try container.decodeIfPresent(Bool.self, forKey: .humanVerified) ?? false
        blockingFields = try container.decodeIfPresent([String].self, forKey: .blockingFields) ?? []
        problems = try container.decodeIfPresent([String].self, forKey: .problems) ?? []
        message = try container.decodeIfPresent(String.self, forKey: .message) ?? ""
    }
}

/// Sunucunun her hata gövdesi bu biçimde: { ok:false, code, error }.
struct IhbarErrorResponse: Decodable {

    let code: String
    /// Zaten Türkçe ve kullanıcıya gösterilmek üzere yazılmış.
    let error: String

    private enum CodingKeys: String, CodingKey {
        case code, error
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        code = try container.decodeIfPresent(String.self, forKey: .code) ?? ""
        error = try container.decodeIfPresent(String.self, forKey: .error) ?? ""
    }
}

/// Sunucunun gövdede anlattığı ret. `message` doğrudan ekrana yazılabilir —
/// Türkçe ve kullanıcı için yazılmış.
struct IhbarError: Error {
    let code: String
    let message: String
}

/// Cihaz belirteci girilmemiş; ağa çıkmadan önce durduran hata.
struct IhbarTokenMissingError: Error {}

// MARK: - Durum sözlüğü

/// Sunucunun döndürdüğü durum adları.
private enum IhbarState {
    static let bilinmiyor = "bilinmiyor"
    static let beklemede = "beklemede"
    static let inceleniyor = "inceleniyor"
    static let eksikBilgi = "eksik_bilgi"
    static let onaylandi = "onaylandi"
    static let kapandi = "kapandi"
    static let reddedildi = "reddedildi"
    static let onaylanamaz = "onaylanamaz"
}

// MARK: - Düğmenin durumu

/// İhlal düğmesinin görünümü. Her değer ayrı bir renk ve ayrı bir metin demek.
enum IhbarPhase {
    /// Sunucuya henüz sorulmadı. Nötr ve basılabilir.
    case unknown
    /// İstek yolda. Çember döner, düğme basılmaz.
    case busy
    /// Kayıt var, insan teyidi yok. Basılmayı bekliyor.
    case markable
    /// İnsan teyidi kaydedildi. YEŞİL.
    case verified
    /// İhbar onaylandı ve memurlara gidiyor. YEŞİL.
    case approved
    /// Teyit alındı ama kritik alan eksik. AMBER.
    case needsInfo
    /// Teyit alındı ama kayıt bu hâliyle gönderilemiyor. AMBER.
    case blocked
    /// Video bizde yok ya da çıkarımı sürüyor. SOLUK.
    case pending
    /// İstek başarısız. KIRMIZI, tekrar denenebilir.
    case error
    /// Cihaz belirteci girilmemiş. SOLUK, ayara yönlendirir.
    case noToken
}

/// Bir videonun düğmesinin bildiği her şey.
///
/// ViewModel'de tutuluyor, sayfada değil: dikey kaydırma sayfayı yeniden
/// oluşturuyor ve durum sayfada dursaydı her geri dönüşte kaybolurdu.
struct IhbarMark: Equatable {
    let phase: IhbarPhase
    /// Sunucunun kendi cümlesi ya da durum etiketi; düğmenin alt satırı.
    var detail: String? = nil
    /// Onayı engelleyen alanlar; alt satır bunlardan yazılıyor.
    var blockingFields: [String] = []
    /// Sunucunun hangi anahtarla eşleştirdiği (mid / sentAt / url / index).
    /// Ekranda gösterilmiyor; ileride "yanlış videoya yeşil düğme" şüphesi
    /// doğduğunda bakılacak ilk alan bu ve o gün elde olması gerekiyor.
    var matchedBy: String? = nil
}

private func ihbarPhase(state: String, humanVerified: Bool) -> IhbarPhase {
    switch state {
    case IhbarState.onaylandi: .approved
    case IhbarState.bilinmiyor, IhbarState.beklemede: .pending
    case IhbarState.onaylanamaz: .blocked
    // Aşağıdaki üçü teyitten ÖNCE de görülebiliyor: kayıt eksik bilgili ya da
    // kapanmış olabilir ama sahip henüz videoya bakmamıştır. Teyit yokken düğme
    // nötr kalıyor, çünkü asıl istenen şey hâlâ onun dokunuşu; amber ya da soluk
    // göstermek "yapacak bir şey yok" demek olurdu.
    case IhbarState.eksikBilgi: humanVerified ? .needsInfo : .markable
    case IhbarState.kapandi, IhbarState.reddedildi: humanVerified ? .blocked : .markable
    case IhbarState.inceleniyor: humanVerified ? .verified : .markable
    // Sunucu tanımadığımız bir durum eklediyse: teyit varsa yeşil, yoksa nötr.
    // Bilinmeyen bir ada bakıp "hata" demek, çalışan bir sistemi bozuk gösterirdi.
    default: humanVerified ? .verified : .markable
    }
}

extension IhbarStatusItem {

    var mark: IhbarMark {
        let phase = ihbarPhase(state: state, humanVerified: humanVerified)
        let label = stateLabel.isEmpty ? nil : stateLabel
        return IhbarMark(
            phase: phase,
            // Onaylanmış kayıtta ihbar kodu, kalanında sunucunun durum etiketi:
            // kodu görmek, aynı ihbarı yönetici konsolunda aramayı mümkün kılıyor.
            detail: phase == .approved ? (violationCode ?? label) : label,
            blockingFields: blockingFields,
            matchedBy: matchedBy
        )
    }
}

extension IhbarApproveResponse {

    var mark: IhbarMark {
        let phase = ihbarPhase(state: state, humanVerified: humanVerified)
        // Onay ucu her dalda tam bir cümle yazıyor; kendi metnimizi üretmek,
        // sunucunun bildiğini tahmin etmek olurdu. Tek istisna engellenmiş kayıt:
        // orada somut sebep ("Açıklama çok kısa") genel cümleden daha çok işe yarar.
        let sentence = message.isEmpty ? (stateLabel.isEmpty ? nil : stateLabel) : message
        return IhbarMark(
            phase: phase,
            detail: phase == .blocked ? (problems.first ?? sentence) : sentence,
            blockingFields: blockingFields,
            matchedBy: matchedBy
        )
    }
}

/// Sunucunun alan adlarını sahibin kullandığı kelimelere çevirir.
///
/// Sunucu 'il', 'tarih', 'medya' gibi ŞEMA adları gönderiyor; ekranda okunan şey
/// "konum eksik" olmalı. Tanımadığımız bir ad ham hâliyle gösteriliyor: sunucuya
/// yeni bir alan eklendiğinde eksiği gizlemektense çirkin göstermek yeğdir.
func ihbarFieldLabel(_ field: String) -> String {
    switch field {
    case "il": "konum"
    case "ilce": "ilçe"
    case "tarih": "tarih"
    case "plaka": "plaka"
    case "medya": "video"
    default: field
    }
}

/// Bir videoyu ihbar sunucusunda aratacak anahtarlar.
///
/// NEDEN ``Conversation/sentAt(_:)`` DEĞİL DE mediaTs DOĞRUDAN: sentAt(), damga
/// yoksa konuşmanın lastMessageDate'ine düşüyor — o damga BU videonun değil,
/// konuşmanın son mesajının zamanı. Sunucu ±2 dakikalık pencerede en yakın
/// medyalı mesajı seçtiği için, uydurulmuş bir damga sessizce YANLIŞ VİDEOYU
/// eşleştirir: durumda yanlış renkli düğme, onayda yanlış ihbarın emniyet
/// birimine gitmesi. Damga yoksa eşleştirmeyi adrese ve sıraya bırakmak, yanlış
/// bir zamana güvenmekten iyidir.
///
/// NEDEN VEKİL (proxy) ADRESİ DEĞİL DE HAM ADRES: sunucu CDN adresinin
/// değişmeyen parçasını (dosya adı / asset_id) arıyor. Vekil adresinin yolunda
/// ("/admin/media-proxy") böyle bir parça yok, sorgu anahtarı da beyaz listede
/// değil — yani vekil adresiyle hiçbir zaman eşleşme olmaz.
func ihbarItem(for conversation: Conversation, mediaIndex: Int) -> IhbarItem {
    IhbarItem(
        clientId: conversation.clientId,
        sentAt: conversation.mediaTs.indices.contains(mediaIndex)
            ? conversation.mediaTs[mediaIndex]
            : nil,
        url: conversation.urls.indices.contains(mediaIndex)
            ? conversation.urls[mediaIndex]
            : nil,
        mid: nil,
        index: mediaIndex
    )
}

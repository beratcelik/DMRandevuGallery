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
    /// OLUMSUZ DÜĞMENİN dayandığı bayrak: sahip "bu görüntü ihlal değil" dedi.
    ///
    /// ADI SUNUCUDAKİNİN AYNISI. Bu dosyanın başındaki "alan adları birebir"
    /// kuralı burada bir kez çiğnenmişti ve derleyici hiçbir şey söylemiyordu:
    /// alan `humanRejected` diye yazılmış, oysa sunucu `notViolation` gönderiyor.
    /// Eşleşmeyen ad sessizce false çözülür; sahibin elediği video bir sonraki
    /// açılışta yeniden "el değmemiş" görünür ve o video daha önce teyit
    /// edilmişse düğme YEŞİL yanardı — düzeltmeye çalıştığımız arızanın aynısı.
    ///
    /// NEDEN ``humanVerified`` İLE İKİ AYRI BAYRAK, ÜÇ DEĞERLİ TEK ALAN DEĞİL:
    /// sunucuda iki damga ayrı ayrı duruyor ve olumsuz dokunuş, daha önce alınmış
    /// bir teyidin izini silmiyor. Tek alana indirmek, "önce teyit edildi sonra
    /// elendi" ayrımını tel üzerinde kaybetmek olurdu — o ayrım, aynı videoya iki
    /// kez farklı cevap veren bir günü çözebilecek yegâne iz.
    ///
    /// Sunucu bu alanı henüz göndermiyorsa false kalıyor: eski bir sunucuya
    /// bağlanan yeni uygulama, olmayan bir elemeyi uydurmak yerine düğmeyi nötr
    /// bırakır — yanlış yönde hata yapmanın ucuz olanı bu. Böyle bir sunucuda
    /// durumun kendisi (``IhbarState/ihlalDegil``) ikinci tanık olarak duruyor.
    let notViolation: Bool
    let matchedBy: String?
    let violationCode: String?
    /// Eksik alanların onayı gerçekten ENGELLEYEN alt kümesi (il / tarih / medya).
    let blockingFields: [String]
    /// Bu ihbara bağlı TOPLAM video sayısı.
    ///
    /// NEDEN GEREKLİ: onay KAYDIN TAMAMINI memura gönderiyor. Sağa atış tek bir
    /// videoya karar vermek gibi görünürken üç videoyu birden ihbar edebiliyor
    /// ve kart bunu söylemeden önce bilmek zorunda. Aynı sayı, elemede
    /// gösterilecek geri çekme penceresinin hangi cümleyi kuracağını da
    /// belirliyor.
    ///
    /// 0 = "sunucu söylemedi" (eski sunucu); kart o hâlde sayıdan hiç söz
    /// etmiyor, uydurmuyor.
    let mediaCount: Int
    /// Bu ihbara bağlı olup sahibin ZATEN elediği video sayısı.
    let eliminatedCount: Int

    private enum CodingKeys: String, CodingKey {
        case state, stateLabel, humanVerified, notViolation, matchedBy, violationCode
        case blockingFields, mediaCount, eliminatedCount
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        state = try container.decodeIfPresent(String.self, forKey: .state) ?? ""
        stateLabel = try container.decodeIfPresent(String.self, forKey: .stateLabel) ?? ""
        humanVerified = try container.decodeIfPresent(Bool.self, forKey: .humanVerified) ?? false
        notViolation = try container.decodeIfPresent(Bool.self, forKey: .notViolation) ?? false
        matchedBy = try container.decodeIfPresent(String.self, forKey: .matchedBy)
        violationCode = try container.decodeIfPresent(String.self, forKey: .violationCode)
        blockingFields = try container.decodeIfPresent([String].self, forKey: .blockingFields) ?? []
        mediaCount = try container.decodeIfPresent(Int.self, forKey: .mediaCount) ?? 0
        eliminatedCount = try container.decodeIfPresent(Int.self, forKey: .eliminatedCount) ?? 0
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
    /// Onaylanan ihbarın kaç video taşıdığı — memura giden delil sayısı.
    let mediaCount: Int
    /// Onaydan önce kayıttan çıkarılan, daha önce elenmiş kardeş video sayısı.
    let detachedSiblings: Int

    private enum CodingKeys: String, CodingKey {
        case state, stateLabel, matchedBy, humanVerified, blockingFields, problems, message
        case mediaCount, detachedSiblings
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
        mediaCount = try container.decodeIfPresent(Int.self, forKey: .mediaCount) ?? 0
        detachedSiblings = try container.decodeIfPresent(Int.self, forKey: .detachedSiblings) ?? 0
    }
}

/// Olumsuz dokunuşun sonucu: "bu görüntü bir ihlal DEĞİL".
///
/// NEDEN ONAY YANITINDAN AYRI BİR TİP (alanların çoğu ortak olsa da): onay
/// yanıtındaki `problems` ve `blockingFields`, kaydın onaya HAZIR OLMASI için
/// eksik kalanları anlatıyor. Olumsuz cevapta öyle bir soru yok — kayıt zaten
/// gitmeyecek. İki ucu tek tipte toplamak, burada hiçbir zaman dolmayacak iki
/// alanı taşımak ve bir sonraki okuyucuya "eleme neyi engelliyor?" diye
/// arattırmak olurdu.
struct IhbarRejectResponse: Decodable {

    let state: String
    let stateLabel: String
    let matchedBy: String?
    /// Dokunuşun TUTUP TUTMADIĞI. Adı durum ucundaki bayrakla aynı: iki uç aynı
    /// şeyi iki ayrı adla söyleseydi, hangisinin hangi cevapta geçtiğini bir
    /// sonraki okuyucuya arattırırdı.
    ///
    /// false, "sunucu videoyu tanıyamadı" demek (eşleşme yok ya da belirsiz);
    /// böyle bir cevapta düğmeyi "elendi" göstermek, sahibi bir daha hiç
    /// basmayacağı bir yalanla baş başa bırakırdı — oysa o kayıt yapay zekâ
    /// hattında ilerlemeye devam ediyor.
    let notViolation: Bool
    /// Sunucunun kendi Türkçe cümlesi. Geri çekme dalında memura bildirim
    /// gittiğini de bu cümle söylüyor; kendi metnimizi üretmek, sunucunun
    /// bildiğini tahmin etmek olurdu.
    let message: String
    /// Karar YALNIZCA bu videoya uygulandı mı — kayıt kalan delille ayakta mı?
    ///
    /// NEDEN AYRI BİR ALAN (mesaja bakılmıyor): ekran, kullanıcıya söyleyeceği
    /// cümleyi buna göre seçiyor. Serbest metinden çıkarım yapmak, sunucu
    /// cümlesini düzelten ilk günün sessizce yanlış şey söylemesi demekti.
    let detached: Bool
    /// Ayırmadan sonra kayıtta kalan video sayısı.
    let remainingMedia: Int
    /// Karar anında kayda bağlı toplam video.
    let mediaCount: Int

    private enum CodingKeys: String, CodingKey {
        case state, stateLabel, matchedBy, notViolation, message
        case detached, remainingMedia, mediaCount
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        state = try container.decodeIfPresent(String.self, forKey: .state) ?? ""
        stateLabel = try container.decodeIfPresent(String.self, forKey: .stateLabel) ?? ""
        matchedBy = try container.decodeIfPresent(String.self, forKey: .matchedBy)
        notViolation = try container.decodeIfPresent(Bool.self, forKey: .notViolation) ?? false
        message = try container.decodeIfPresent(String.self, forKey: .message) ?? ""
        detached = try container.decodeIfPresent(Bool.self, forKey: .detached) ?? false
        remainingMedia = try container.decodeIfPresent(Int.self, forKey: .remainingMedia) ?? 0
        mediaCount = try container.decodeIfPresent(Int.self, forKey: .mediaCount) ?? 0
    }
}

// MARK: - Toplu eleme

/// Bir konuşmanın karar verilmemiş videolarını TEK istekte eleme isteği.
///
/// NEDEN TOPLU BİR UÇ VAR (tekil uç dururken): bir muhabir on beş video
/// gönderebiliyor ve hiçbiri ihlal olmayabilir. Tek tek elemek on beş istek
/// demek; iki yazma ucu TEK bir oran sınırı kovasını paylaşıyor (dakikada
/// yirmi) ve yirmi birinci dokunuş reddediliyor — konuşma yarım elenmiş kalıyor
/// ve ekranda bitmiş görünüyor. Toplu uç tek çağrı = tek hak sayılıyor.
///
/// TOPLU ONAY YOK VE OLMAYACAK: eleme geri alınabilir bir karar (aynı videoyu
/// sağa atmak fikri değiştiriyor); onay ise delili bir kamu birimine çıkarıyor.
struct IhbarBulkRejectRequest: Encodable {
    let items: [IhbarItem]
}

/// Toplu elemede TEK bir videonun sonucu; öğeler İSTEK SIRASIYLA dönüyor.
struct IhbarBulkRejectItem: Decodable {

    let key: String
    /// Sahibin kararı deftere YAZILDI mı?
    let applied: Bool
    /// Kaydın kendisine dokunulmadıysa sebebi: 'onayli' | 'zayif_anahtar' |
    /// 'bulunamadi' | 'zaten'. Tanınmayan bir değer atlanmış sayılmaya devam
    /// ediyor — sunucu yeni bir sebep eklediğinde uygulama çökmemeli.
    let skipped: String?
    let state: String
    let stateLabel: String
    let notViolation: Bool
    let detached: Bool
    let remainingMedia: Int
    let violationCode: String?
    let message: String

    private enum CodingKeys: String, CodingKey {
        case key, applied, skipped, state, stateLabel, notViolation
        case detached, remainingMedia, violationCode, message
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        key = try container.decodeIfPresent(String.self, forKey: .key) ?? ""
        applied = try container.decodeIfPresent(Bool.self, forKey: .applied) ?? false
        skipped = try container.decodeIfPresent(String.self, forKey: .skipped)
        state = try container.decodeIfPresent(String.self, forKey: .state) ?? ""
        stateLabel = try container.decodeIfPresent(String.self, forKey: .stateLabel) ?? ""
        notViolation = try container.decodeIfPresent(Bool.self, forKey: .notViolation) ?? false
        detached = try container.decodeIfPresent(Bool.self, forKey: .detached) ?? false
        remainingMedia = try container.decodeIfPresent(Int.self, forKey: .remainingMedia) ?? 0
        violationCode = try container.decodeIfPresent(String.self, forKey: .violationCode)
        message = try container.decodeIfPresent(String.self, forKey: .message) ?? ""
    }
}

struct IhbarBulkRejectResponse: Decodable {

    let items: [IhbarBulkRejectItem]
    /// Kararı deftere yazılan video sayısı.
    let applied: Int
    /// Dokunulmayan video sayısı (onaylı, tanınmayan, zayıf anahtarlı).
    let skipped: Int

    private enum CodingKeys: String, CodingKey {
        case items, applied, skipped
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        items = try container.decodeIfPresent([IhbarBulkRejectItem].self, forKey: .items) ?? []
        applied = try container.decodeIfPresent(Int.self, forKey: .applied) ?? 0
        skipped = try container.decodeIfPresent(Int.self, forKey: .skipped) ?? 0
    }
}

extension IhbarBulkRejectItem {

    var mark: IhbarMark {
        let phase: IhbarPhase
        switch skipped {
        // ATLANAN ÖĞE İŞARETİ DEĞİŞTİRMEMELİ: onaylı kayıt onaylı kalıyor,
        // tanınmayan video bilinmiyor kalıyor. Toplu bir hareketin, dokunmadığı
        // bir videonun rengini değiştirmesi en sessiz yalan olurdu.
        case "onayli": phase = .approved
        case "bulunamadi", "zayif_anahtar": phase = .pending
        default:
            phase = (notViolation || state == "ihlal_degil") ? .notViolation : .pending
        }
        return IhbarMark(
            phase: phase,
            detail: message.isEmpty ? (stateLabel.isEmpty ? nil : stateLabel) : message,
            mediaCount: detached ? remainingMedia : 0
        )
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
    /// Sahibin elediği video.
    ///
    /// BAYRAĞIN YERİNE DEĞİL, YANINDA: sunucu olumsuz kararı hem ayrı bir alanla
    /// hem de durumun kendisiyle söylüyor ve ikisi ayrı kaynaklardan türüyor —
    /// biri medyaya çıpalanmış karar satırı, öteki o satırın kaydın durumunun
    /// ÖNÜNE GEÇMESİ. Yalnızca bayrağa bakmak, adı bir gün değişirse elemeyi
    /// sessizce kaçırırdı ve bu tam olarak BİR KEZ YAŞANDI; yalnızca duruma
    /// bakmak ise sunucu eski sürümdeyken kaçırırdı. İkisi birden bakmanın
    /// bedeli tek bir karşılaştırma.
    static let ihlalDegil = "ihlal_degil"
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
    /// Teyit alındı ama ihbar kaydı HENÜZ AÇILMADI. YEŞİL.
    ///
    /// NEDEN ``pending``'DEN AYRI: ikisi de "kayıt yok" hâlini anlatıyor ama
    /// biri sahibin dokunuşunu taşıyor, diğeri taşımıyor. Tek evreye
    /// sıkıştırıldığında sağa atılan video soluk "kayıt hazır değil" görünüyor
    /// ve sahip aynı videoyu tekrar tekrar atıyordu — oysa dokunuş kaydedilmiş
    /// ve kayıt açılır açılmaz onaya gidecek (sunucuda galeri/finalize.ts).
    ///
    /// NEDEN ``verified`` DEĞİL: alt satırdaki cümle farklı olmak zorunda.
    /// "İhlal olarak işaretlendi" demek, ihbarın çoktan yola çıktığını ima
    /// ederdi; doğru cümle "kayıt açılınca ihbar edilecek".
    case verifiedPending
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
    // ── olumsuz dokunuşun evreleri ───────────────────────────────────────────
    //
    // NEDEN AYNI ENUM (ikinci bir "reddetme evresi" tipi değil): bir videonun
    // TEK bir durumu var. İki ayrı eksen tutsaydık "hem teyit edilmiş hem
    // elenmiş" gibi imkânsız bir çift kurulabilir ve iki düğme aynı anda iki zıt
    // renk gösterirdi. Tek enum, bu çelişkiyi derleme zamanında imkânsız kılıyor.
    /// Olumsuz istek yolda. Çember OLUMSUZ düğmede döner, ikisi de basılmaz.
    case rejecting
    /// Sahip "bu görüntü ihlal değil" dedi ve sunucu kaydetti. ARDUVAZ.
    case notViolation
    /// Olumsuz istek başarısız. KIRMIZI, tekrar denenebilir.
    case rejectError
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
    /// Bu ihbarın kaç video taşıdığı (0 = sunucu söylemedi).
    ///
    /// Ekranda iki yerde okunuyor: kartın "3 videoluk ihbar" satırı ve geri
    /// çekme penceresinin metni. Sağa atmak tek videoya karar vermek gibi
    /// görünürken kaydın tamamını gönderiyor; bunu söylemenin tek yolu bu sayı.
    var mediaCount: Int = 0
    /// Bu ihbarda sahibin ZATEN elediği video sayısı.
    var eliminatedCount: Int = 0
}

extension IhbarMark {

    /// Çipin söyleyecek bir şeyi var mı?
    ///
    /// ─── NEDEN "KARAR VERİLMEDİ" YAZMIYORUZ ────────────────────────────────
    /// Sahip videoyu YENİ açtı; karar vermediği zaten kesin. Ekranda duran her
    /// etiket okunmayı hak etmek zorunda ve bu etiket hiçbir şey öğretmiyordu —
    /// yalnızca kararın verileceği yerde, tam da videoya bakılması gereken anda
    /// yer kaplıyordu. Görünen her çip artık bir HABER taşıyor: ihbar edildi,
    /// elendi, bilgi eksik, istek düştü, belirteç yok.
    ///
    /// NÖTR EVREDE ÇİP OLMAMASI BİLGİ KAYBI DEĞİL: kararın nasıl verileceğini
    /// kaydırmanın kendisi öğretiyor (kart parmakla hareket ediyor ve damga
    /// beliriyor), ve boş bir ekran "bu videoya henüz dokunulmadı" demenin en
    /// kısa yolu.
    var saysSomething: Bool {
        phase != .unknown && phase != .markable
    }
}

private func ihbarPhase(state: String, humanVerified: Bool, notViolation: Bool) -> IhbarPhase {
    // OLUMSUZ DOKUNUŞ HER ŞEYİN ÖNÜNDE — durumun kendisinin bile.
    //
    // "Son dokunuş kazanır" kuralını SUNUCU uyguluyor: zıt yöndeki her dokunuş
    // öncekinin bayrağını düşürüyor, yani iki bayrak aynı anda açık dönmüyor.
    // İSTEMCİDE İKİ DAMGANIN ZAMANINI KARŞILAŞTIRMIYORUZ: ihbar sunucusunda saat
    // dilimi kayması bilinen ve daha önce yaşanmış bir arıza; ekranda hangi
    // rengin yanacağını iki ISO metnini kıyaslayarak seçmek, o kaymayı galeriye
    // taşımak olurdu. Karar kimin verdiği bellidir: sunucu.
    if notViolation || state == IhbarState.ihlalDegil { return .notViolation }
    // `return switch`: erken çıkış eklendiği an switch artık gövdenin tek ifadesi
    // değil, bir deyim — örtük dönüş kalkıyor ve `.approved` gibi kısaltmalar
    // bağlamsız kalıyor.
    return switch state {
    case IhbarState.onaylandi: .approved
    // BEKLEMEDE + TEYİT = dokunuş kaydedildi, kayıt yolda. Bu ayrım olmadan
    // sahibin sağa attığı video soluk "kayıt hazır değil" görünüyor ve aynı
    // videoya tekrar tekrar basılıyordu.
    case IhbarState.beklemede: humanVerified ? .verifiedPending : .pending
    case IhbarState.bilinmiyor: .pending
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
        let phase = ihbarPhase(
            state: state, humanVerified: humanVerified, notViolation: notViolation
        )
        let label = stateLabel.isEmpty ? nil : stateLabel
        return IhbarMark(
            phase: phase,
            // Onaylanmış kayıtta ihbar kodu, kalanında sunucunun durum etiketi:
            // kodu görmek, aynı ihbarı yönetici konsolunda aramayı mümkün kılıyor.
            detail: phase == .approved ? (violationCode ?? label) : label,
            blockingFields: blockingFields,
            matchedBy: matchedBy,
            mediaCount: mediaCount,
            eliminatedCount: eliminatedCount
        )
    }
}

extension IhbarApproveResponse {

    var mark: IhbarMark {
        // notViolation: false — OLUMLU uçtan dönen cevapta eleme bayrağı yok ve
        // olamaz: sahip az önce "bu bir ihlal" dedi, yani varsa bile önceki eleme
        // sunucuda o dokunuşla düşmüş durumda. Durum da bu uçta hiçbir dalda
        // 'ihlal_degil' dönmüyor, yani ikinci tanık da sessiz.
        let phase = ihbarPhase(
            state: state, humanVerified: humanVerified, notViolation: false
        )
        // Onay ucu her dalda tam bir cümle yazıyor; kendi metnimizi üretmek,
        // sunucunun bildiğini tahmin etmek olurdu. Tek istisna engellenmiş kayıt:
        // orada somut sebep ("Açıklama çok kısa") genel cümleden daha çok işe yarar.
        let sentence = message.isEmpty ? (stateLabel.isEmpty ? nil : stateLabel) : message
        return IhbarMark(
            phase: phase,
            detail: phase == .blocked ? (problems.first ?? sentence) : sentence,
            blockingFields: blockingFields,
            matchedBy: matchedBy,
            mediaCount: mediaCount
        )
    }
}

extension IhbarRejectResponse {

    var mark: IhbarMark {
        let sentence = message.isEmpty ? (stateLabel.isEmpty ? nil : stateLabel) : message
        // Dokunuş tutmadıysa arduvaz düğme YANLIŞ olurdu: video sunucuda
        // eşleşmedi, yani hiçbir şey elenmedi. Bu ayrımı ``ihbarPhase`` veriyor —
        // ne bayrak ne durum olumsuzu gösteriyorsa düğme sunucunun dediği yere
        // dönüyor (ağırlıkla "bilinmiyor" → soluk) ve cümlesi altta duruyor.
        //
        // humanVerified: false — bu uç o bayrağı taşımıyor. Eşleşmeyen bir
        // dokunuştan sonra düğme, bir sonraki durum sorgusuna kadar var olan bir
        // teyidi göstermeyebilir; olmayan bir teyidi VAR göstermekten iyidir,
        // çünkü yeşil düğme sahibi gerçekten basması gereken yerde basmaktan
        // alıkoyar.
        return IhbarMark(
            phase: ihbarPhase(state: state, humanVerified: false, notViolation: notViolation),
            detail: sentence,
            matchedBy: matchedBy,
            // AYIRMADAN SONRAKİ SAYI: kayıt ayakta kaldıysa kaç video kaldığı,
            // kapandıysa kaç video taşıdığı. Kartın "kayıt 2 videoyla devam
            // ediyor" diyebilmesi için tek kaynak bu.
            mediaCount: detached ? remainingMedia : mediaCount
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

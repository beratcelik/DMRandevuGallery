import XCTest
@testable import DMRandevuGaleri

/// "İhlal değil" dokunuşunun düğmeye çevrilmesi.
///
/// NEDEN TEST EDİLİYOR: bu eşlemenin iki tarafa da sapması pahalı. Olumsuz
/// dokunuş düğmede görünmezse sahip aynı videoyu tekrar tekrar eler (ve her
/// seferinde sunucuda bir geri çekme tetikler); tersine, dokunulmamış bir kaydı
/// "elendi" göstermek sahibin ikinci kez bakmasını engeller ve gerçek bir ihlal
/// sessizce çöpe gider. İkisi de ekranda hata gibi görünmüyor — ancak burada
/// yakalanır.
///
/// NEDEN NESNE KURULMUYOR DA JSON ÇÖZÜLÜYOR: bu tiplerin hepsi kendi
/// ``init(from:)``'ını yazıyor, yani üyelik başlatıcısı yok — ama asıl sebep bu
/// değil. Sınanmak istenen şey tam olarak TEL ÜZERİNDEKİ AD: alan bir kez
/// `humanRejected` diye yazılmıştı, sunucu ise `notViolation` gönderiyordu ve
/// derleyici hiçbir şey söylemedi; eksik anahtar sessizce false çözülüyor.
/// Elle kurulan bir nesne o arızayı asla yakalayamazdı, çözülen bir gövde
/// yakalar.
final class IhbarNotViolationTests: XCTestCase {

    private func status(_ json: String) throws -> IhbarStatusItem {
        try JSONDecoder().decode(IhbarStatusItem.self, from: Data(json.utf8))
    }

    private func reject(_ json: String) throws -> IhbarRejectResponse {
        try JSONDecoder().decode(IhbarRejectResponse.self, from: Data(json.utf8))
    }

    private func approve(_ json: String) throws -> IhbarApproveResponse {
        try JSONDecoder().decode(IhbarApproveResponse.self, from: Data(json.utf8))
    }

    /// Sunucu tarafındaki asıl tuzak: sahip "ihlal değil" dediğinde kayıt
    /// TRAFIK_DISI ile reddediliyor ve ham durum "reddedildi" olabiliyor — ama
    /// aynı ad, yöneticinin konsoldan yetersiz delille yaptığı reddin de sonucu.
    /// Karar durum adına bırakılsaydı iki farklı olay tek renge düşerdi ve sahip
    /// kendi dokunuşunu ekranda göremezdi.
    func testTheNegativeTouchOutranksTheState() throws {
        let marked = try status(
            #"{"state":"reddedildi","stateLabel":"Reddedildi","notViolation":true}"#
        )
        XCTAssertEqual(marked.mark.phase, .notViolation)

        let byAdmin = try status(#"{"state":"reddedildi","stateLabel":"Reddedildi"}"#)
        XCTAssertEqual(byAdmin.mark.phase, .markable)
    }

    /// Memura gitmiş bir ihbar da elenebiliyor (sunucuda geri çekme yolu var).
    /// Yeşil kalsaydı sahip dokunuşunun yutulduğunu sanır ve tekrar basardı —
    /// her basış memura ikinci bir geri çekme bildirimi demek.
    func testAnApprovedRecordDoesNotStayGreenOnceItIsDismissed() throws {
        let item = try status(
            #"{"state":"onaylandi","humanVerified":true,"notViolation":true}"#
        )
        XCTAssertEqual(item.mark.phase, .notViolation)
    }

    /// GERİLEME KORUMASI — bu tam olarak yaşanmış arıza.
    ///
    /// Sunucu olumsuz kararı iki ayrı yerden söylüyor: bayrak ve durumun kendisi
    /// ('ihlal_degil', karar kaydın durumunun önüne geçtiğinde). Bayrağın adı
    /// istemcide yanlış yazıldığında geriye YALNIZCA durum kalıyordu; ona da
    /// bakılmadığı için sahibin elediği video ekranda yeniden "el değmemiş"
    /// görünüyordu.
    func testTheStateAloneIsEnough() throws {
        let item = try status(
            #"{"state":"ihlal_degil","stateLabel":"İhlal değil olarak işaretlendi"}"#
        )
        XCTAssertEqual(item.mark.phase, .notViolation)
        XCTAssertEqual(item.mark.detail, "İhlal değil olarak işaretlendi")
    }

    /// Aynı korumanın ikinci yarısı: kayıt daha önce teyit edilmişse, kaçırılan
    /// olumsuz karar düğmeyi nötr değil YEŞİL bırakırdı — sahibe elediği videoyu
    /// "ihlal olarak işaretlendi" diye gösteren en kötü hâl.
    func testAVerifiedRecordIsNotPaintedGreenWhenTheStateSaysDismissed() throws {
        let item = try status(#"{"state":"ihlal_degil","humanVerified":true}"#)
        XCTAssertEqual(item.mark.phase, .notViolation)
    }

    /// Sunucunun cümlesi düğmenin alt satırına geçiyor: kaydın reddedildiğini mi
    /// yoksa memurdan GERİ ÇEKİLDİĞİNİ mi söylediğini o cümle taşıyor ve ayrımı
    /// sunucu bizden iyi biliyor.
    func testTheServerSentenceReachesTheButton() throws {
        let mark = try reject(
            #"""
            {"state":"kapandi","stateLabel":"Kapandı","notViolation":true,
             "message":"İhbar geri çekildi; memurlara bilgi gönderiliyor."}
            """#
        ).mark
        XCTAssertEqual(mark.phase, .notViolation)
        XCTAssertEqual(mark.detail, "İhbar geri çekildi; memurlara bilgi gönderiliyor.")
    }

    /// 200 döndü ama dokunuş yazılamadı — sunucu videoyu tanıyamadı. Arduvaza
    /// boyamak, sahibe elemediği bir kaydı elenmiş gösterir ve o kayıt yapay zekâ
    /// hattında ilerlemeye devam ederken sahip bir daha bakmazdı.
    func testAnUnwrittenTouchDoesNotLookDismissed() throws {
        let mark = try reject(
            #"""
            {"state":"bilinmiyor","stateLabel":"Bilinmiyor",
             "message":"Bu video ihbar sisteminde bulunamadı."}
            """#
        ).mark
        XCTAssertEqual(mark.phase, .pending)
        XCTAssertEqual(mark.detail, "Bu video ihbar sisteminde bulunamadı.")
    }

    /// Olumsuz alanın eklenmesi olumlu yolu bozmamalı. Onay yanıtı bu bayrağı hiç
    /// taşımıyor ve taşımamalı: o uca basmak, varsa önceki elemeyi sunucuda zaten
    /// geçersiz kılıyor (revertNotViolation).
    func testThePositivePathIsUnchanged() throws {
        let mark = try approve(
            #"""
            {"state":"inceleniyor","stateLabel":"İnceleniyor","humanVerified":true,
             "message":"İhbar işaretlendi."}
            """#
        ).mark
        XCTAssertEqual(mark.phase, .verified)
        XCTAssertEqual(mark.detail, "İhbar işaretlendi.")
    }

    /// Bilinmeyen alanlar yok sayılıyor ve eksik alanlar hata değil: eski bir
    /// sunucuya bağlanan yeni uygulama, olmayan bir elemeyi uydurmak yerine
    /// düğmeyi nötr bırakıyor.
    func testWithoutTheFlagTheButtonClaimsNothing() throws {
        let mark = try status(
            #"{"state":"inceleniyor","stateLabel":"İnceleniyor","notViolationAt":null}"#
        ).mark
        XCTAssertEqual(mark.phase, .markable)
        XCTAssertNil(mark.matchedBy)
    }

    /// ÇIKARIM HENÜZ BİTMEMİŞKEN VERİLEN OLUMLU KARAR.
    ///
    /// NEDEN TEST EDİLİYOR: bu hâl istisna değil, sağa atışın NORMAL yolu —
    /// çıkarım iki saatlik sessizlik penceresinde bekliyor, sahip ise videoyu
    /// izler izlemez karar veriyor. Tek evreye ("kayıt hazır değil")
    /// sıkıştırıldığında kart soluk görünüyor ve sahip aynı videoyu tekrar
    /// tekrar atıyordu; oysa dokunuş kaydedilmiş ve kayıt açılır açılmaz onaya
    /// gidecek.
    func testVerifiedButNotYetExtractedHasItsOwnPhase() throws {
        let mark = try status(
            #"{"state":"beklemede","stateLabel":"Çıkarım sürüyor","humanVerified":true}"#
        ).mark
        XCTAssertEqual(mark.phase, .verifiedPending)

        // Dokunulmamış video aynı durumda SOLUK kalıyor: teyit taşımıyor.
        let untouched = try status(
            #"{"state":"beklemede","stateLabel":"Çıkarım sürüyor"}"#
        ).mark
        XCTAssertEqual(untouched.phase, .pending)
    }

    /// Kaydın kaç video taşıdığı istemciye ULAŞIYOR.
    ///
    /// Onay kaydın TAMAMINI memura gönderiyor; sağa atış tek videoya karar
    /// vermek gibi görünürken üç videoyu birden ihbar edebiliyor. Sayı
    /// taşınmazsa ekranın bunu söylemesinin hiçbir yolu yok.
    func testMediaCountsReachTheMark() throws {
        let mark = try status(
            #"{"state":"inceleniyor","mediaCount":3,"eliminatedCount":1}"#
        ).mark
        XCTAssertEqual(mark.mediaCount, 3)
        XCTAssertEqual(mark.eliminatedCount, 1)
    }

    /// Ayırma: kayıt ayakta kaldığında işaret KALAN video sayısını taşıyor.
    /// Geri çekme penceresinin metni buna bakıyor; yanlış sayı, gerçekleşmeyecek
    /// bir şeyi vaat etmek olurdu.
    func testDetachCarriesTheRemainingCount() throws {
        let mark = try reject(
            #"""
            {"state":"ihlal_degil","notViolation":true,"detached":true,
             "remainingMedia":2,"mediaCount":3,"message":"kayıttan çıkarıldı"}
            """#
        ).mark
        XCTAssertEqual(mark.phase, .notViolation)
        XCTAssertEqual(mark.mediaCount, 2)
    }

    /// Geri çekme penceresi, kayıtta itirazsız delil kalıyorsa AYIRMA metnini
    /// kuruyor. Tek metin, gerçekleşmeyecek bir şeyi ("ihbar geri çekilir")
    /// vaat ederdi.
    func testRetractCopyBranchesOnRemainingEvidence() {
        let multi = IhbarMark(phase: .approved, mediaCount: 3, eliminatedCount: 0)
        XCTAssertTrue(IhbarRetractCopy.detachOnly(multi))

        let single = IhbarMark(phase: .approved, mediaCount: 1, eliminatedCount: 0)
        XCTAssertFalse(IhbarRetractCopy.detachOnly(single))

        // Sunucu sayı göndermediyse (eski sunucu) AĞIR metin kalıyor: hafif
        // olanı vaat edip ağırını yapmaktan iyi.
        let unknown = IhbarMark(phase: .approved)
        XCTAssertFalse(IhbarRetractCopy.detachOnly(unknown))
    }

    /// KARAR VERİLMEMİŞ VİDEODA ÇİP YOK.
    ///
    /// Sahip videoyu yeni açtı; karar vermediği zaten kesin ve o etiket hiçbir
    /// şey öğretmiyordu — yalnızca tam da videoya bakılması gereken anda yer
    /// kaplıyordu. Görünen her çip bir HABER taşımak zorunda.
    func testChipOnlyShowsWhenItHasSomethingToSay() {
        XCTAssertFalse(IhbarMark(phase: .unknown).saysSomething)
        XCTAssertFalse(IhbarMark(phase: .markable).saysSomething)

        let newsworthy: [IhbarPhase] = [
            .approved, .verified, .verifiedPending, .notViolation, .needsInfo,
            .blocked, .pending, .error, .rejectError, .noToken, .busy, .rejecting,
        ]
        for phase in newsworthy {
            XCTAssertTrue(IhbarMark(phase: phase).saysSomething, "\(phase) görünmeli")
        }
    }
}

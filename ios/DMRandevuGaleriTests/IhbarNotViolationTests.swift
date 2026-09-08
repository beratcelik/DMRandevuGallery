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
}

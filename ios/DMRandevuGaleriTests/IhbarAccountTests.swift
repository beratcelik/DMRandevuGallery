import XCTest
@testable import DMRandevuGaleri

/// İhbar düğmesinin görünürlüğü tek bir karşılaştırmaya dayanıyor; yanlış tarafa
/// düşmesinin iki bedeli de ağır. Doğru hesapta kaybolursa sahip ihbar
/// gönderemediğini fark etmeyebilir; yanlış hesapta belirirse ihbar hattına ait
/// olmayan bir videoyu emniyet birimine sokmayı dener.
final class IhbarAccountTests: XCTestCase {

    func testTheOwnAccountMatchesInEveryWriting() {
        XCTAssertTrue(IhbarAccount.matches("trafik_cezasi"))
        XCTAssertTrue(IhbarAccount.matches("@trafik_cezasi"))
        XCTAssertTrue(IhbarAccount.matches("  @trafik_cezasi  "))
        XCTAssertTrue(IhbarAccount.matches("@ trafik_cezasi"))
    }

    /// Küçültme Türkçe yerelinde "I"yı noktasız "ı"ya düşürüyor; eşleşmenin buna
    /// takılmaması gerekiyor.
    func testCaseDoesNotDecideIt() {
        XCTAssertTrue(IhbarAccount.matches("Trafik_Cezasi"))
        XCTAssertTrue(IhbarAccount.matches("TRAFIK_CEZASI"))
    }

    /// Giriş alanına kullanıcı adı yerine sayısal kimlik yazmak da geçerli bir
    /// kullanım: galeri sayfaları zaten bu kimlikle isteniyor.
    func testTheNumericIdIsAcceptedToo() {
        XCTAssertTrue(IhbarAccount.matches("17841468848724091"))
        XCTAssertTrue(IhbarAccount.matches(" 17841468848724091 "))
    }

    func testTheOtherAccountNeverMatches() {
        XCTAssertFalse(IhbarAccount.matches("trafykamerasi"))
        XCTAssertFalse(IhbarAccount.matches("@Trafykamerasi"))
        XCTAssertFalse(IhbarAccount.matches("17841472755272054"))
    }

    /// Boş ayar hiçbir hesap değil: aksi hâlde düğme ait olmadığı yerde belirirdi.
    func testEmptyIsNotAnAccount() {
        XCTAssertFalse(IhbarAccount.matches(""))
        XCTAssertFalse(IhbarAccount.matches("   "))
        XCTAssertFalse(IhbarAccount.matches("@"))
    }

    /// Benzeyen bir ad aynı hesap değil; karşılaştırma "içeriyor" değil "eşit".
    func testALookalikeHandleIsNotTheAccount() {
        XCTAssertFalse(IhbarAccount.matches("trafik_cezasi_2"))
        XCTAssertFalse(IhbarAccount.matches("xtrafik_cezasi"))
    }
}

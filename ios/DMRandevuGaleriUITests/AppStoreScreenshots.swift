import XCTest

/// App Store ekran görüntüleri: tanıtım, akış, filtre rayı, caption sayfası.
///
/// NEDEN AYRI VE ELLE KOŞULAN BİR SINIF: mağaza görüntüleri halka açık, yani yalnızca inceleme
/// için kopyalanmış demo hesapta (`cheto_demo`, sunucudaki `scripts/seed-review-demo.js`)
/// çekilmeli. Bu sınıf GİRİŞ YAPMIYOR — şifre ortam değişkeninden bile okunmuyor; oturum açık
/// değilse atlıyor. Hangi hesapla çekildiğine uygulamada oturumu açan kişi karar veriyor.
///
/// HİÇ KAYDIRMIYOR: başka müşteriye geçmek bir öncekini sunucudan siliyor. Her görüntü ilk
/// sayfada, yalnızca dokunuşla elde ediliyor.
///
/// Çıktı: `.keepAlways` ekler. Sonuç paketinden şöyle alınır:
///
///     xcrun xcresulttool export attachments --path <xcresult> --output-path <klasör>
///
/// iPhone 17 Pro Max simülatöründe 1320×2868 çıkıyor. App Store Connect'in tek zorunlu yuvası ise
/// 6.3 inç (1206×2622); `sips -z 2622 1206` ile küçültülüyor, en-boy oranı farkı %0,1'in altında.
final class AppStoreScreenshots: XCTestCase {

    private var app: XCUIApplication!

    @MainActor
    func testCaptureStoreScreenshots() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()

        if app.secureTextFields["loginPassword"].waitForExistence(timeout: 8) {
            throw XCTSkip("Not signed in: sign in to the demo account in the app first")
        }

        // Taze kurulumda giriş sonrası tanıtım açılıyor: onu olduğu gibi çek, sonra kapat.
        let tourDone = app.buttons["Anladım"]
        if tourDone.waitForExistence(timeout: 6) {
            sleep(1)
            capture("4-nasil-kullanilir")
            tourDone.tap()
        }

        XCTAssertTrue(onScreen("customerName").waitForExistence(timeout: 45), "the gallery never loaded")
        // İlk kare boyansın, kontroller sönsün.
        sleep(6)
        capture("1-akis")

        // Filigran önizlemesi videonun üzerinde gezinen hesap adını gösteriyor; açıp çek, eski
        // hâline döndür (ayar kalıcı). Düğme seçili durumunu bildirmiyor; hangi uyarının
        // çıktığına bakılıyor, GalleryUITests'in yaptığı gibi.
        let watermarkOn = app.staticTexts["Filigran açık — hesap adı videonun üzerinde gezinecek"]
        onScreen("toggleWatermark").tap()
        var turnedOnHere = true
        if !watermarkOn.waitForExistence(timeout: 3) {
            turnedOnHere = false // zaten açıktı, az önce kapandı: yeniden aç
            sleep(2)
            onScreen("toggleWatermark").tap()
            _ = watermarkOn.waitForExistence(timeout: 3)
        }
        capture("2-filtreler-uyari")
        sleep(5)
        capture("2-filtreler")
        if turnedOnHere { onScreen("toggleWatermark").tap() }

        onScreen("actionCaption").tap()
        XCTAssertTrue(app.staticTexts["✨ Instagram Caption"].waitForExistence(timeout: 10), "the caption sheet did not open")
        let copy = app.buttons["caption-copy"]
        let ready = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == true AND isEnabled == true"),
            object: copy
        )
        _ = XCTWaiter().wait(for: [ready], timeout: 95)
        sleep(1)
        capture("3-caption")
        app.buttons["caption-close"].tap()
    }

    @MainActor
    private func capture(_ name: String) {
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = name
        shot.lifetime = .keepAlways
        add(shot)
    }

    /// GalleryUITests'teki ile aynı: sayfalayıcı komşu sayfaları da tutuyor, ekrandakini seç.
    @MainActor
    private func onScreen(_ identifier: String) -> XCUIElement {
        let matches = app.descendants(matching: .any).matching(identifier: identifier)
        let screen = app.windows.firstMatch.frame
        for index in 0..<matches.count {
            let element = matches.element(boundBy: index)
            guard element.exists else { continue }
            let frame = element.frame
            guard frame.width > 0, frame.height > 0 else { continue }
            if frame.midY >= screen.minY, frame.midY <= screen.maxY { return element }
        }
        return matches.firstMatch
    }
}

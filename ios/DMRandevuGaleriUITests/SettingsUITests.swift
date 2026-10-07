import XCTest

/// Ayarlar: dişli, şifre değiştirme, çıkış ve ikinci bir hesapla giriş.
///
/// YALNIZCA YEREL BİR SAHTE SUNUCUYA KARŞI KOŞUYOR. `DMRANDEVU_MOCK_SERVER` (örn.
/// `http://127.0.0.1:3111`, gerçek bir telefonda Mac'in yerel ağ adresi) verilmezse ya da adres
/// yerel/özel ağ değilse test atlanıyor, yani üretime bağlanamaz: bu test şifre değiştiriyor, çıkış yapıyor ve hesap değiştiriyor, ve gerçek bir
/// hesapta bunların hiçbiri "deneme" sayılmaz. Sahte sunucu iki hesap taşıyor (`demo_hesap` ve
/// `demo_hesap2`, farklı müşterilerle), her giriş bilgisini kabul ediyor ve mevcut şifre olarak
/// `demo` bekliyor. Gerçek müşteri verisi yok.
///
/// NEDEN BİRLİKTE BİR TEST: hesap değiştirme tek başına bir hata saklıyordu. Çıkıp başka hesapla
/// girildiğinde galeri, önceki kişinin müşterilerini gösterebilirdi; bu yüzden son adım ikinci
/// hesabın müşterisini ve ilkininkinin KAYBOLDUĞUNU doğruluyor.
final class SettingsUITests: XCTestCase {

    private var app: XCUIApplication!
    private var server = ""

    // SENKRON setUp: asenkron olan, kesme izleyicisinin ihtiyaç duyduğu "geçerli test bağlamı"nı
    // taşımıyor (gerçek telefonda "Current context must not be nil" ile düşüyordu).
    override func setUpWithError() throws {
        continueAfterFailure = false
        let environment = ProcessInfo.processInfo.environment
        guard let configured = environment["DMRANDEVU_MOCK_SERVER"],
              let host = URL(string: configured)?.host,
              Self.isLocalHost(host) else {
            throw XCTSkip("DMRANDEVU_MOCK_SERVER must point at a local stand-in server (127.0.0.1 or a private LAN address)")
        }
        server = configured

        // Gerçek bir telefon, yerel ağdaki bir adrese ilk bağlandığında "yerel ağı bulmasına izin ver"
        // diye soruyor; simülatör sormuyor. Soru testi durdurmasın.
        addUIInterruptionMonitor(withDescription: "Local network permission") { alert in
            for label in ["Allow", "OK", "İzin Ver", "Tamam"] where alert.buttons[label].exists {
                alert.buttons[label].tap()
                return true
            }
            return false
        }

        app = XCUIApplication()
        app.launch()
    }

    @MainActor
    func testSettingsPasswordLogoutAndSwitchingAccounts() throws {
        // ── 1. Birinci hesapla giriş ────────────────────────────────────────────
        signIn(account: "demo_hesap")
        dismissTourIfShown()
        XCTAssertTrue(
            waitForCustomer(containing: "ornek_musteri"),
            "the first account's customer never appeared"
        )

        // ── 2. Dişli ayarları açıyor ───────────────────────────────────────────
        let gear = app.buttons["settingsButton"]
        XCTAssertTrue(gear.waitForExistence(timeout: 5), "the settings gear is missing")
        XCTAssertTrue(gear.isHittable, "the settings gear cannot be tapped")
        gear.tap()
        XCTAssertTrue(app.navigationBars["Ayarlar"].waitForExistence(timeout: 5), "settings did not open")
        // LabeledContent sunar etiketle değeri tek bir öğede ("Instagram hesabı, @demo_hesap"),
        // yani değeri tek başına bir metin olarak aramak onu bulamıyor.
        let shown = app.descendants(matching: .any).matching(
            NSPredicate(format: "label CONTAINS %@ OR value CONTAINS %@", "@demo_hesap", "@demo_hesap")
        ).firstMatch
        XCTAssertTrue(shown.exists, "the signed-in account is not shown")

        // ── 3. Yanlış mevcut şifre: reddediliyor ve oturum açık kalıyor ────────
        app.buttons["changePasswordButton"].tap()
        fill("currentPassword", with: "yanlis-sifre")
        fill("newPassword", with: "yeni-sifre-123")
        fill("repeatPassword", with: "yeni-sifre-123")
        app.buttons["submitPassword"].tap()
        XCTAssertTrue(
            app.staticTexts["Mevcut şifre yanlış."].waitForExistence(timeout: 10),
            "a wrong current password was not reported"
        )
        XCTAssertTrue(app.navigationBars["Ayarlar"].exists, "a wrong password threw the person out")

        // ── 4. Doğru mevcut şifre: değişiyor ───────────────────────────────────
        replace("currentPassword", with: "demo")
        app.buttons["submitPassword"].tap()
        XCTAssertTrue(
            app.staticTexts["Şifre değişti"].waitForExistence(timeout: 10),
            "the password change was not confirmed"
        )

        // ── 5. Çıkış ───────────────────────────────────────────────────────────
        // KOORDİNATLA DOKUNUŞ: XCTest bu düğmeyi (yıkıcı rollü, bir Form hücresinin içinde)
        // "dokunulabilir" saymıyor, oysa ekranda tamamen görünür ve hiçbir şey onu örtmüyor. Dokunuş
        // gerçekten düğmeye ulaşıyor mu, hemen aşağıdaki onay penceresinin AÇILMASI ile doğrulanıyor.
        let logout = app.buttons["logoutButton"]
        XCTAssertTrue(logout.waitForExistence(timeout: 5), "the sign-out button is missing")
        logout.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        XCTAssertTrue(
            app.staticTexts["Çıkış yapılsın mı?"].waitForExistence(timeout: 5),
            "the sign-out confirmation did not open"
        )
        // Onay penceresinin düğmesi, listedeki düğmeyle AYNI etiketi taşıyor: ayrım kimlikten.
        let confirm = app.buttons.matching(
            NSPredicate(format: "label == 'Çıkış yap' AND identifier != 'logoutButton'")
        ).firstMatch
        XCTAssertTrue(confirm.waitForExistence(timeout: 5), "the confirmation's sign-out button is missing")
        confirm.tap()
        XCTAssertTrue(
            app.secureTextFields["loginPassword"].waitForExistence(timeout: 15),
            "signing out did not return to the login screen"
        )

        // ── 6. Başka bir hesapla giriş: önceki kişiden hiçbir şey kalmamış olmalı ──
        signIn(account: "demo_hesap2")
        dismissTourIfShown()
        XCTAssertTrue(
            waitForCustomer(containing: "ikinci_hesap_musterisi"),
            "the second account's customer never appeared"
        )
        XCTAssertFalse(
            customerName(containing: "ornek_musteri").exists,
            "the previous account's customer is still on screen after signing in as someone else"
        )
    }

    // MARK: - Handles

    /// Döngü geri adresi, `localhost` ya da özel (RFC 1918) bir ağ adresi. Genel bir adres asla.
    private static func isLocalHost(_ host: String) -> Bool {
        if host == "localhost" { return true }
        let parts = host.split(separator: ".").compactMap { Int($0) }
        guard parts.count == 4 else { return false }
        return parts[0] == 127 || parts[0] == 10
            || (parts[0] == 192 && parts[1] == 168)
            || (parts[0] == 172 && (16...31).contains(parts[1]))
    }

    private func customerName(containing text: String) -> XCUIElement {
        app.staticTexts.matching(identifier: "customerName")
            .matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch
    }

    /// Müşteri adı görünene kadar bekler. Beklerken aralıklarla zararsız bir dokunuş yapıyor: gerçek
    /// bir telefonda çıkan "yerel ağ" izni gibi bir sistem uyarısı, kesme izleyicisine ancak bir
    /// etkileşim sırasında ulaşıyor; yalnızca beklemek onu açık bırakıp bağlantıyı tutardı.
    @MainActor
    private func waitForCustomer(containing text: String, timeout: TimeInterval = 40) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if customerName(containing: text).waitForExistence(timeout: 2) { return true }
            // Durum çubuğu bölgesi: iOS'ta listeyi başa sarar, başka bir şey yapmaz.
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.02)).tap()
        }
        // Bulunamadı: ekranın o anki hâli sonuç paketine eklensin ki neden okunabilsin.
        let shot = XCTAttachment(screenshot: XCUIScreen.main.screenshot())
        shot.name = "waiting-for-\(text)"
        shot.lifetime = .keepAlways
        add(shot)
        return false
    }

    @MainActor
    private func signIn(account: String) {
        let password = app.secureTextFields["loginPassword"]
        XCTAssertTrue(password.waitForExistence(timeout: 15), "the login screen is not showing")
        replace(app.textFields["loginServer"], with: server)
        replace(app.textFields["loginUsername"], with: "demo")
        replace(password, with: "demo")
        replace(app.textFields["loginAccount"], with: account)
        app.buttons["loginSubmit"].tap()
        // Bir sistem uyarısı (yerel ağ izni) çıktıysa kesme izleyicisi ancak bir etkileşimde
        // devreye giriyor; ekranın üst orta kısmına zararsız tek bir dokunuş.
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.04)).tap()
    }

    /// Varsa tanıtımı kapatır (kurulum başına bir kez çıkar, ve altına dokunuş geçirmez).
    @MainActor
    private func dismissTourIfShown() {
        let done = app.buttons["Anladım"]
        if done.waitForExistence(timeout: 20) { done.tap() }
    }

    @MainActor
    private func field(_ identifier: String) -> XCUIElement {
        let secure = app.secureTextFields[identifier]
        return secure.exists ? secure : app.textFields[identifier]
    }

    @MainActor
    private func fill(_ identifier: String, with text: String) {
        let element = field(identifier)
        XCTAssertTrue(element.waitForExistence(timeout: 5), "\(identifier) is missing")
        element.tap()
        element.typeText(text)
    }

    @MainActor
    private func replace(_ identifier: String, with text: String) {
        replace(field(identifier), with: text)
    }

    /// Alanın içindekini siler ve yenisini yazar. Gizli alanın değeri noktalardan oluşuyor; noktaların
    /// sayısı karakter sayısına eşit, silmek için bu yetiyor.
    @MainActor
    private func replace(_ element: XCUIElement, with text: String) {
        XCTAssertTrue(element.waitForExistence(timeout: 5), "a field is missing")
        element.tap()
        let existing = (element.value as? String) ?? ""
        if !existing.isEmpty {
            // Yer tutucu metin değer olarak dönebilir; fazladan silmek zararsız.
            element.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: existing.count + 2))
        }
        element.typeText(text)
    }
}

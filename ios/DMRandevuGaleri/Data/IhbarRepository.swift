import Foundation

/// Trafik İhbar sunucusuyla konuşan üç uç: bir ekran dolusu videonun durumu,
/// tek bir videonun onayı ve tek bir videonun elenmesi.
///
/// NEDEN ``GalleryRepository``'ye EKLENMEDİ: o sınıfın tamamı DMRandevu'nun
/// çerezli oturumuna dayanıyor ve 401'i "oturum bitti" sayıp kullanıcıyı giriş
/// ekranına atıyor. İhbar sunucusunda kimlik bir çerez değil, cihaz belirteci;
/// oradan gelen 401 "oturumun bitti" demek değil, "belirtecin yanlış" demek.
/// İkisini aynı sınıfta toplamak, ihbar belirteci hatalı olduğu an sahibi galeri
/// oturumundan atardı.
///
/// AYNI URLSession kullanılıyor (yeni bir ağ yığını yok); yalnızca çerezler
/// istek bazında kapatılıyor.
final class IhbarRepository {

    /// Sunucunun tek istekte kabul ettiği azami video sayısı.
    static let maxItems = 50

    private let session: URLSession
    private let settings: SettingsStore
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()

    init(session: URLSession, settings: SettingsStore) {
        self.session = session
        self.settings = settings
    }

    private var base: String {
        var url = settings.ihbarBaseURL
        while url.hasSuffix("/") { url.removeLast() }
        return url
    }

    /// Belirteç girilmiş mi. Düğme, girilmemişken kullanıcıya bunu söylüyor.
    var hasToken: Bool { !settings.ihbarToken.isEmpty }

    /// Bir ekran dolusu videonun durumu — TEK istekte.
    ///
    /// Video başına istek atmak, her kaydırmada N istek demek olurdu: mobil
    /// bağlantıda gözle görülür gecikme ve sunucudaki oran sınırının hiçbir iş
    /// yapmadan dolması. Sunucu tek istekte en fazla ``maxItems`` öğe kabul
    /// ediyor; bölme işi çağırana ait, çünkü kaç gidiş geliş yapıldığı burada
    /// gizlenmemeli.
    func status(_ items: [IhbarItem]) async throws -> [IhbarStatusItem] {
        guard !items.isEmpty else { return [] }
        let body = try encoder.encode(IhbarStatusRequest(items: items))
        let response: IhbarStatusResponse = try await post("/api/galeri/durum", body: body)
        return response.items
    }

    /// Sahibin dokunuşu: "bu görüntü gerçekten bir ihlal gösteriyor".
    ///
    /// Tekil, çünkü bu bir dokunuş. Toplu onay, yanlışlıkla elli kaydın bir
    /// kerede emniyet birimlerine gitmesi demek olurdu.
    ///
    /// Aynı videoya ikinci kez basmak zararsız: sunucu insan teyidini koşullu
    /// yazıyor (ilk dokunuş kazanır) ve dağıtım satırları tekil.
    func approve(_ item: IhbarItem) async throws -> IhbarApproveResponse {
        let body = try encoder.encode(item)
        return try await post("/api/galeri/onayla", body: body)
    }

    /// Sahibin olumsuz dokunuşu: "bu görüntü bir ihlal DEĞİL".
    ///
    /// NEDEN AYRI BİR UÇ, onayla'ya `verdict` alanı EKLEMEK DEĞİL: iki eylemin
    /// sonuçları zıt ve sunucudaki oran sınırları da öyle olmalı. Tek uçta
    /// toplasaydık, gövdedeki tek bir alanın yanlış kodlanması bir elemeyi
    /// sessizce ONAYA çevirebilirdi — yani kaydı emniyet birimine yollayabilirdi.
    /// Ayrı yol, o hatayı 404'e düşürüyor.
    ///
    /// NEDEN TEKİL: bu da bir dokunuş. Toplu eleme, tek hareketle bir ekran dolusu
    /// ihbarı (aralarında memura gitmiş olanları da) kapatmak demek olurdu.
    ///
    /// Aynı videoya ikinci kez basmak zararsız: sunucu son dokunuşu yazıyor ve
    /// zaten elenmiş bir kaydı yeniden elemek durumu değiştirmiyor.
    ///
    /// Çerez burada da gitmiyor — ``post`` her istekte kapatıyor.
    func reject(_ item: IhbarItem) async throws -> IhbarRejectResponse {
        let body = try encoder.encode(item)
        return try await post("/api/galeri/ihlal-degil", body: body)
    }

    private func post<T: Decodable>(_ path: String, body: Data) async throws -> T {
        let token = settings.ihbarToken
        // Ağa hiç çıkmadan duruyoruz: belirteçsiz istek sunucuda yalnızca
        // geçersiz-belirteç oran sınırını doldurur ve kullanıcıya "sunucu hatası"
        // gibi görünürdü. Eksik olan şey ayarda, sunucuda değil.
        guard !token.isEmpty else { throw IhbarTokenMissingError() }
        // Adres ayarlardan geliyor, yani pekâlâ adres olmayan bir şey olabilir.
        guard let url = URLComponents(string: base + path)?.url else {
            throw InvalidServerAddressError()
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        // DMRandevu yönetici oturum çerezi başka bir sunucuya HİÇBİR koşulda
        // gitmemeli. Ortak kavanoz alan adına bakıp filtreliyor, ama bu sınır bir
        // filtrenin doğru yazılmış olmasına bırakılamayacak kadar önemli.
        request.httpShouldHandleCookies = false
        request.httpBody = body

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(status) else {
            // Hata gövdesi de JSON ve içindeki `error` alanı ZATEN TÜRKÇE,
            // kullanıcıya gösterilmek üzere yazılmış. Kendi metnimizi uydurmak
            // yerine onu taşıyoruz; sunucu bir reddi neden verdiğini bizden iyi
            // biliyor.
            let parsed = try? decoder.decode(IhbarErrorResponse.self, from: data)
            var reason = parsed?.error ?? ""
            if reason.isEmpty {
                reason = "İhbar sunucusu isteği reddetti (HTTP \(status))"
            }
            throw IhbarError(code: parsed?.code ?? "HTTP_\(status)", message: reason)
        }
        return try decoder.decode(T.self, from: data)
    }
}

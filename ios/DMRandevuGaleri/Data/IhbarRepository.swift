import Foundation

/// Trafik İhbar köprüsü: bir ekran dolusu videonun durumu, tek bir videonun onayı ve elenmesi.
///
/// NEDEN ARTIK DMRandevu'YA KONUŞUYOR (İhbar sunucusuna değil): İhbar'ın cihaz belirteci artık
/// telefonda tutulmuyor. Giriş yapan kişi DMRandevu'da zaten belli; istekler onun oturumuyla
/// `/admin/ihbar/…` uçlarına gidiyor ve sunucu, yalnızca trafik_cezasi hesabına yetkili kişiler
/// için, kendi belirteciyle İhbar'a iletiyor. Kaybolan bir telefon hiçbir şey sızdırmıyor.
///
/// İKİ HATA, İKİ ANLAM: DMRandevu'dan gelen 401 "oturumun bitti" demek ve ``UnauthorizedError``
/// olarak çıkıyor; görünüm modeli kullanıcıyı giriş ekranına götürüyor. İhbar sunucusunun KENDİ
/// ret sebebi (sunucunun belirteci, oran sınırı, eksik alan) ise sunucuda 502/4xx'e çevriliyor ve
/// ``IhbarError`` oluyor: oturumu bitirmiyor, düğmede Türkçe sebebiyle görünüyor.
///
/// AYNI URLSession ve ÇEREZ KAVANOZU kullanılıyor (galeriyle aynı oturum).
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
        var url = settings.baseURL
        while url.hasSuffix("/") { url.removeLast() }
        return url
    }

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
        let response: IhbarStatusResponse = try await post("/admin/ihbar/durum", body: body)
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
        return try await post("/admin/ihbar/onayla", body: body)
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
    func reject(_ item: IhbarItem) async throws -> IhbarRejectResponse {
        let body = try encoder.encode(item)
        return try await post("/admin/ihbar/ihlal-degil", body: body)
    }

    private func post<T: Decodable>(_ path: String, body: Data) async throws -> T {
        // Adres ayarlardan geliyor, yani pekâlâ adres olmayan bir şey olabilir.
        guard let url = URLComponents(string: base + path)?.url else {
            throw InvalidServerAddressError()
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = body

        let (data, response) = try await session.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        // Oturum bitti: İhbar'ın değil, kullanıcının kendi girişi. Giriş ekranına dönülecek.
        if status == 401 { throw UnauthorizedError() }
        guard (200..<300).contains(status) else {
            // Hata gövdesi de JSON ve içindeki `error` alanı ZATEN TÜRKÇE, kullanıcıya
            // gösterilmek üzere yazılmış. Kendi metnimizi uydurmak yerine onu taşıyoruz.
            let parsed = try? decoder.decode(IhbarErrorResponse.self, from: data)
            var reason = parsed?.error ?? ""
            if reason.isEmpty {
                reason = "İhbar isteği reddedildi (HTTP \(status))"
            }
            throw IhbarError(code: parsed?.code ?? "HTTP_\(status)", message: reason)
        }
        return try decoder.decode(T.self, from: data)
    }
}

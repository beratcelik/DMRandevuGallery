import Foundation

/// İhbar hattının bağlı olduğu TEK Instagram hesabı.
///
/// Galeri iki hesap geziyor (trafik_cezasi ve trafykamerasi); Trafik İhbar
/// sistemi yalnızca birini tanıyor — üretimde `IgAccount` tablosunda tam olarak
/// bir satır var ve sunucunun ortamında `IG_ACCOUNT_ID=17841468848724091`
/// yazıyor.
///
/// NEDEN DÜĞME YANLIŞ HESAPTA "HATA VERMİYOR" DA HİÇ ÇİZİLMİYOR: öteki hesabın
/// videosu ihbar sunucusunda hiçbir kayıtla eşleşmez, yani basılan düğme ya
/// sessizce nötr kalır ya da anlamsız bir ret metni gösterirdi. İkisi de sahibe
/// "ihbar bozuk" dedirtirdi; oysa doğru cümle "bu hesap ihbar hattına bağlı
/// değil" ve onu en iyi anlatan şey düğmenin orada hiç bulunmaması.
///
/// NEDEN TEK YERDE: kimlik üç ayrı kararda okunuyor (düğmenin görünürlüğü,
/// toplu durum sorgusu, onay). Üçüne ayrı ayrı yazılsaydı biri güncellenmeden
/// kalır ve düğme ya ait olduğu hesapta kaybolur ya da ait olmadığı hesapta
/// belirirdi — ikincisi, yanlış hesabın videosunu emniyet hattına sokmayı
/// deneyen bir dokunuş demek.
enum IhbarAccount {

    /// İhbar sunucusunun dinlediği hesabın kullanıcı adı (@ olmadan).
    static let handle = "trafik_cezasi"

    /// Aynı hesabın sayısal Instagram kimliği.
    ///
    /// Galeri sayfaları bu kimlikle isteniyor, yani "gezilen hesap" sorusunun en
    /// güvenilir karşılığı kullanıcı adı değil, bu.
    static let igId = "17841468848724091"

    /// Verilen hesap tanımlayıcısı ihbar hesabı mı.
    ///
    /// Hem kullanıcı adını hem sayısal kimliği kabul ediyor, çünkü çağıranın
    /// elinde hangisinin bulunduğu yere göre değişiyor: görünürlük kararı
    /// çözülmüş sayısal kimlikten geliyor, giriş ekranına ise elle yazılmış bir
    /// metin giriliyor ve orada "@Trafik_Cezasi" da geçerli bir yazım.
    ///
    /// NEDEN `lowercased()` DEĞİL DE ``caseInsensitiveCompare(_:)``: küçültme
    /// yerele bağlanabilen bir işlem ve Türkçe yerelinde "I" harfi noktasız "ı"ya
    /// düşüyor — "TRAFIK_CEZASI" bu yolla "trafık_cezası" olur ve eşleşme sessizce
    /// kaybolurdu. Karşılaştırma hiçbir yerele danışmıyor.
    static func matches(_ account: String) -> Bool {
        var trimmed = account.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.hasPrefix("@") { trimmed.removeFirst() }
        // '@' ile ad arasına düşen boşluk da eleniyor; kopyala-yapıştır bir
        // kullanıcı adı bunu yeterince sık taşıyor.
        trimmed = trimmed.trimmingCharacters(in: .whitespacesAndNewlines)
        // Boş metin hiçbir hesap değil: aksi hâlde ayarların boş olduğu bir
        // durumda düğme ait olmadığı yerde belirebilirdi.
        guard !trimmed.isEmpty else { return false }
        return trimmed.caseInsensitiveCompare(handle) == .orderedSame || trimmed == igId
    }
}

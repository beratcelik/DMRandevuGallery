import SwiftUI

/// Her iki ihbar düğmesinin ortak renk sözlüğü.
///
/// NEDEN TEK YERDE: bu iki düğmenin tek işi, sahibin verdiği iki zıt kararı BİR
/// BAKIŞTA ayırt ettirmek. Renkler iki struct'a ayrı ayrı yazılsaydı, birini
/// tazeleyen bir düzenleme ötekini olduğu yerde bırakır ve ayrım sessizce
/// kapanırdı — yani düğmelerin var oluş sebebi kaybolurdu.
private enum IhbarPalette {

    /// Yeşil: bu görüntünün bir ihlal olduğunu bir insan teyit etti.
    static let green = Color(red: 0.106, green: 0.529, blue: 0.247)
    /// Amber: teyit alındı ama kayıt olduğu gibi emniyete gidemiyor.
    static let amber = Color(red: 0.604, green: 0.380, blue: 0.0)
    /// Kırmızı: istek başarısız — tekrar denenebilir. Kaydın kendisiyle ilgisi yok.
    static let red = Color(red: 0.627, green: 0.169, blue: 0.169)

    /// Arduvaz: sahip bu görüntüyü eledi — "ihlal değil".
    ///
    /// NEDEN KIRMIZI DEĞİL: kırmızı bu ekranda ZATEN "istek başarısız" demek.
    /// Elemeyi de kırmızı yapmak, geçerli bir kararı arıza gibi gösterir ve iki
    /// kırmızı düğmeden hangisinin "tekrar dene" olduğu ancak metin okunarak
    /// anlaşılırdı — "bir bakışta" ölçüsünün tam tersi.
    ///
    /// NEDEN SOLUK SİYAH DEĞİL: nötr ve beklemedeki düğmeler o tonda. Eleme
    /// onlarla karışsaydı sahip "bastım mı, basmadım mı?" sorusuna geri dönerdi
    /// ve emin olmak için ikinci kez basardı.
    ///
    /// Mavi-gri, videonun üstünde yeşilden en uzak duran ve yine de nötr değil de
    /// bir KARAR gibi okunan ton.
    static let slate = Color(red: 0.227, green: 0.290, blue: 0.388)

    /// Nötr: sorulmuş ama henüz karar yok; basılmayı bekliyor.
    static let neutral = Color.black.opacity(0.55)
    /// Soluk: şu an yapacak bir şey yok, ya da düğme ikincil.
    static let dim = Color.black.opacity(0.4)
}

/// Sahibin dokunuşu.
///
/// Sahip zaten her videoyu izliyor (hangisini Reel yapacağına karar vermek
/// için). Bu düğme, o izleme sırasında sistemin kendi başına asla üretemeyeceği
/// bilgiyi topluyor: GÖRÜNTÜ GERÇEKTEN BİR İHLAL GÖSTERİYOR MU. Model ihlalin NE
/// olduğunu çıkarıyor; buradaki dokunuş İHLAL OLDUĞUNU teyit ediyor.
///
/// NEDEN ALT SATIRDAKİ EYLEM ŞERİDİNE BİR SİMGE OLARAK EKLENMEDİ: o şerit dört
/// düğmeyle zaten dar ve hepsi videoyu DIŞA AKTARMAKLA ilgili. Bu ise videonun
/// kendisi hakkında bir karar ve tek görünür sonucu rengi — simge boyutunda bir
/// yüzeyde ne renk okunur ne de "eksik bilgi" yazılabilirdi.
struct IhbarMarkButton: View {

    let mark: IhbarMark
    let onTap: () -> Void
    let onLongPress: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            if mark.phase == .busy {
                ProgressView()
                    .tint(.white)
                    .frame(width: 20, height: 20)
            } else {
                Image(systemName: symbol)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 20, height: 20)
            }

            VStack(alignment: .leading, spacing: 1) {
                Text(titleText)
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(.white)
                if let detail {
                    Text(detail)
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.85))
                        .lineLimit(2)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(capsuleColour, in: .capsule)
        // Elenmiş videoda bu düğme geri çekiliyor ama KAYBOLMUYOR: sahibin fikrini
        // değiştirmesi (son dokunuş kazanır) tek dokunuş uzakta kalmalı. Silinmiş
        // bir düğme, aynı videoyu yeniden ihlal saymanın yolunu yönetici konsoluna
        // taşırdı.
        .opacity(mark.phase == .notViolation ? 0.5 : 1)
        // Şekil, glifler değil: onsuz simge ile metin arasındaki boşluk,
        // dokunuşun altındaki videoya düştüğü bir delik oluyor.
        .contentShape(.capsule)
        // NEDEN Button DEĞİL VE NEDEN "disabled" YOK: tam ekran videonun kendi
        // dokunma işleyicisi var ve devre dışı bir Button dokunuşu YUTMUYOR,
        // altındaki videoya geçiriyor — yani yeşil düğmeye basmak videoyu
        // duraklatırdı. Reddetme kararı onTap'in içinde; ActionButton aynı tuzağa
        // aynı çözümü koyuyor.
        //
        // Olumsuz istek yoldayken (.rejecting) bu düğme de sessiz: iki zıt yazma
        // isteğini aynı anda yola çıkarmak, hangisinin sonra vardığına bağlı bir
        // sonuç demek olurdu ve "son dokunuş kazanır" o yarışta yalan olurdu.
        .onTapGesture { if mark.phase != .busy && mark.phase != .rejecting { onTap() } }
        // Belirteci değiştirmenin tek yolu: uygulama açık oturumla başladığında
        // giriş ekranı hiç görünmüyor, yani oradaki alan aylarca erişilemez olabilir.
        .onLongPressGesture(minimumDuration: 0.5) { onLongPress() }
        .accessibilityIdentifier("ihbarMark")
    }

    private var capsuleColour: Color {
        switch mark.phase {
        case .verified, .approved: IhbarPalette.green
        case .needsInfo, .blocked: IhbarPalette.amber
        case .error: IhbarPalette.red
        case .pending, .noToken: IhbarPalette.dim
        // Olumsuz evrelerde bu düğme nötre dönüyor: karar ötekinde, burada yalnızca
        // "yine de ihlal say" davetiyesi kalıyor.
        case .unknown, .markable, .busy, .rejecting, .notViolation, .rejectError:
            IhbarPalette.neutral
        }
    }

    private var symbol: String {
        switch mark.phase {
        case .verified, .approved: "checkmark.circle.fill"
        case .needsInfo, .blocked: "exclamationmark.triangle.fill"
        case .error: "exclamationmark.circle"
        case .pending: "hourglass"
        case .noToken: "lock.fill"
        // .busy bu dala hiç gelmiyor (yerinde çember dönüyor), ama switch tam olmalı.
        case .unknown, .markable, .busy, .rejecting, .notViolation, .rejectError: "flag.fill"
        }
    }

    private var titleText: String {
        switch mark.phase {
        case .unknown, .markable: Strings.ihbarMark
        case .busy: Strings.ihbarMarking
        case .verified: Strings.ihbarMarked
        case .approved: Strings.ihbarApproved
        case .needsInfo: Strings.ihbarNeedsInfo
        case .blocked: Strings.ihbarBlocked
        case .pending: Strings.ihbarPending
        case .error: Strings.ihbarError
        case .noToken: Strings.ihbarNoToken
        // Olumsuz evrelerin metni ötekinde; burada davet duruyor.
        case .rejecting, .notViolation, .rejectError: Strings.ihbarMark
        }
    }

    /// Düğmenin alt satırı.
    ///
    /// Eksik alanlar her şeyin önünde: "konum eksik" sahibin YAPABİLECEĞİ tek şeyi
    /// söylüyor, sunucunun aynı şeyi anlatan uzun cümlesi ise ikinci satıra
    /// sığmıyor. Kalan durumlarda sunucunun kendi Türkçe metni geçiyor — reddin
    /// sebebini bizden iyi biliyor.
    private var detail: String? {
        // Olumsuz dokunuşun cümlesi OLUMSUZ düğmede duruyor. Aynı metni ikisine de
        // yazmak, ekranın iki ucunda aynı cümleyi okutur ve iki kapsül birden
        // genişleyip birbirine değerdi.
        switch mark.phase {
        case .rejecting, .notViolation, .rejectError: return nil
        default: break
        }
        if mark.phase == .noToken { return Strings.ihbarNoTokenDetail }
        if !mark.blockingFields.isEmpty {
            return Strings.ihbarMissingFields(
                mark.blockingFields.map(ihbarFieldLabel).joined(separator: ", ")
            )
        }
        // Sunucudan gelmeyen hata: sebebi ağ tarafında, metni burada.
        if mark.phase == .error && mark.detail == nil { return Strings.ihbarNetworkError }
        return mark.detail
    }
}

/// Sahibin olumsuz dokunuşu: "bu görüntü bir ihlal DEĞİL".
///
/// NEDEN AÇIK BİR DÜĞME, "kaydırıp geçmek" DEĞİL: galeri aynı zamanda Reels
/// seçmek için kullanılıyor. Bir videoyu izleyip kaydırmak çoğu zaman "bunu Reel
/// yapmayacağım" demek; "baktım ve eledim" demek değil. Dokunmamayı olumsuz
/// sinyal saymak, sahibin hiç açmadığı videoları da elemek olurdu — oysa
/// görülmemiş video yapay zekâ kontrolünden geçmeye DEVAM ETMELİ. Bu yüzden
/// sessizliğin bir anlamı yok; yalnızca bu düğmeye basmak bir cevap.
///
/// NEDEN OLUMLU DÜĞMENİN YANINDA DEĞİL, EKRANIN KARŞI UCUNDA: ikisi de tek
/// dokunuşla yazıyor ve sonuçları zıt — biri kaydı memura giden hatta sokuyor,
/// öteki onu (gerekirse memurdan geri çekerek) kapatıyor. Yan yana iki kapsülde
/// baş parmağın yanlışına karşı hiçbir güvence yok; ekranın iki ucu, yanlış
/// basmanın önündeki en ucuz ve en sessiz engel.
///
/// NEDEN DAHA KÜÇÜK VE DAHA SOLUK: asıl istenen dokunuş olumlu olan. Bu düğme
/// eşit ağırlıkta çizilseydi ekran iki eşit seçenek sunar ve sahip her videoda
/// bir karar vermeye çağrılmış olurdu; oysa cevapsız bırakmak da geçerli.
struct IhbarNotViolationButton: View {

    let mark: IhbarMark
    let onTap: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            if mark.phase == .rejecting {
                ProgressView()
                    .tint(.white)
                    .frame(width: 16, height: 16)
            } else {
                Image(systemName: symbol)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 16, height: 16)
            }

            VStack(alignment: .leading, spacing: 1) {
                Text(titleText)
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    // Genişliği kısılan bir metnin satır sayısı artar; yükseklik
                    // kendi ölçüsünü seçmezse kapsül metni kırpar ve "İhlal
                    // değil" yarım kalır.
                    .fixedSize(horizontal: false, vertical: true)
                if let detail {
                    Text(detail)
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.85))
                        .lineLimit(2)
                }
            }
            // Sunucunun cümlesi bazen uzun ("aynı muhabirin birden fazla kaydıyla
            // eşleşiyor…"). Sınırsız bırakılsaydı bu kapsül satırı doldurur ve
            // olumlu düğmeyi ezerdi; iki düğmenin arasındaki boşluk, yanlış basmayı
            // zorlaştıran şeyin ta kendisi.
            .frame(maxWidth: 180, alignment: .leading)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 9)
        .background(capsuleColour, in: .capsule)
        .contentShape(.capsule)
        // Olumlu düğmedeki gerekçenin aynısı: Button değil, çünkü altındaki tam
        // ekran video dokunuşu yutulmadığında duraklıyor. Yolda bir istek varken
        // (her iki yönde de) dokunuş yok sayılıyor.
        .onTapGesture { if mark.phase != .busy && mark.phase != .rejecting { onTap() } }
        .accessibilityIdentifier("ihbarNotViolation")
    }

    /// Alt satır YALNIZCA bu düğmenin kendi evrelerinde görünüyor.
    ///
    /// Kalan evrelerde ekrandaki cümle o videonun durumunu anlatıyor ve o söz
    /// olumlu düğmenin; ikisine birden yazmak, ekranın iki ucunda aynı metni
    /// okutmak olurdu.
    private var detail: String? {
        switch mark.phase {
        case .notViolation: mark.detail
        // Sunucudan gelmeyen hata: sebebi ağ tarafında, metni burada. Ağ metni
        // iki düğmede ortak kalabilir — "sunucuya ulaşılamadı" hangi düğmeye
        // basıldığından bağımsız olarak aynı şeyi anlatıyor.
        case .rejectError: mark.detail ?? Strings.ihbarNetworkError
        default: nil
        }
    }

    // NEDEN BU ÜÇ SWITCH'TE `default` VAR (olumlu düğmede yok): buraya düşen her
    // yeni evre "henüz eleme yok" olarak çizilir. Yanlış yönde hata yapmanın ucuz
    // olanı bu; tanımadığı bir evreye arduvaz basan bir düğme, sahibe elemediği
    // bir videoyu elenmiş gösterirdi.
    private var capsuleColour: Color {
        switch mark.phase {
        case .notViolation: IhbarPalette.slate
        case .rejectError: IhbarPalette.red
        default: IhbarPalette.dim
        }
    }

    private var symbol: String {
        switch mark.phase {
        // Dolu simge "karar verildi", boş simge "sorulabilir" demek; olumlu düğme
        // de aynı dili konuşuyor (checkmark.circle → checkmark.circle.fill).
        case .notViolation: "xmark.circle.fill"
        case .rejectError: "exclamationmark.circle"
        case .noToken: "lock.fill"
        default: "xmark.circle"
        }
    }

    private var titleText: String {
        switch mark.phase {
        case .rejecting: Strings.ihbarNotViolationSending
        case .notViolation: Strings.ihbarNotViolationMarked
        case .rejectError: Strings.ihbarNotViolationError
        default: Strings.ihbarNotViolation
        }
    }
}

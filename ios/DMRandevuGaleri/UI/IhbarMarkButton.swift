import SwiftUI

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

    /// Yeşil: bu görüntünün bir ihlal olduğunu bir insan teyit etti.
    private static let green = Color(red: 0.106, green: 0.529, blue: 0.247)
    /// Amber: teyit alındı ama kayıt olduğu gibi emniyete gidemiyor.
    private static let amber = Color(red: 0.604, green: 0.380, blue: 0.0)
    /// Kırmızı: istek başarısız — tekrar denenebilir. Kaydın kendisiyle ilgisi yok.
    private static let red = Color(red: 0.627, green: 0.169, blue: 0.169)

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
        // Şekil, glifler değil: onsuz simge ile metin arasındaki boşluk,
        // dokunuşun altındaki videoya düştüğü bir delik oluyor.
        .contentShape(.capsule)
        // NEDEN Button DEĞİL VE NEDEN "disabled" YOK: tam ekran videonun kendi
        // dokunma işleyicisi var ve devre dışı bir Button dokunuşu YUTMUYOR,
        // altındaki videoya geçiriyor — yani yeşil düğmeye basmak videoyu
        // duraklatırdı. Reddetme kararı onTap'in içinde; ActionButton aynı tuzağa
        // aynı çözümü koyuyor.
        .onTapGesture { if mark.phase != .busy { onTap() } }
        // Belirteci değiştirmenin tek yolu: uygulama açık oturumla başladığında
        // giriş ekranı hiç görünmüyor, yani oradaki alan aylarca erişilemez olabilir.
        .onLongPressGesture(minimumDuration: 0.5) { onLongPress() }
        .accessibilityIdentifier("ihbarMark")
    }

    private var capsuleColour: Color {
        switch mark.phase {
        case .verified, .approved: Self.green
        case .needsInfo, .blocked: Self.amber
        case .error: Self.red
        case .pending, .noToken: .black.opacity(0.4)
        case .unknown, .markable, .busy: .black.opacity(0.55)
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
        case .unknown, .markable, .busy: "flag.fill"
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
        }
    }

    /// Düğmenin alt satırı.
    ///
    /// Eksik alanlar her şeyin önünde: "konum eksik" sahibin YAPABİLECEĞİ tek şeyi
    /// söylüyor, sunucunun aynı şeyi anlatan uzun cümlesi ise ikinci satıra
    /// sığmıyor. Kalan durumlarda sunucunun kendi Türkçe metni geçiyor — reddin
    /// sebebini bizden iyi biliyor.
    private var detail: String? {
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

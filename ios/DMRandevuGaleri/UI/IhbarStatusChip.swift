import SwiftUI

/// Her iki ihbar düğmesinin ortak renk sözlüğü.
///
/// NEDEN TEK YERDE: bu iki düğmenin tek işi, sahibin verdiği iki zıt kararı BİR
/// BAKIŞTA ayırt ettirmek. Renkler iki struct'a ayrı ayrı yazılsaydı, birini
/// tazeleyen bir düzenleme ötekini olduğu yerde bırakır ve ayrım sessizce
/// kapanırdı — yani düğmelerin var oluş sebebi kaybolurdu.
enum IhbarPalette {

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

/// O an ekrandaki videonun ihbar durumu — SALT OKUNUR bir çip.
///
/// ─── NEDEN DÜĞME DEĞİL ─────────────────────────────────────────────────────
/// Karar artık kaydırmayla veriliyor: sağa at = ihbar et, sola at = ihlal
/// değil. Aynı kararı aynı ekranda ikinci bir yüzeyden de vermek, iki ayrı kas
/// hafızası ve iki ayrı yanlış dokunuş yolu üretirdi. Çip yalnızca sonucu
/// gösteriyor: bu videoya ne dedim, sunucu ne yaptı.
///
/// İKİ İSTİSNA DOKUNUŞ KABUL EDİYOR ve ikisi de karar değil:
///  - Belirteç yokken dokunmak, belirteci yapıştırma penceresini açıyor.
///    Sessizce çalışmayan bir ekran yerine eksik olanı sormak tek makul
///    davranış; kaydırmanın neden hiçbir şey yapmadığının cevabı burada.
///  - Uzun basış her hâlde aynı pencereyi açıyor: uygulama açık oturumla
///    başladığında giriş ekranı hiç görünmüyor ve oradaki alan aylarca
///    erişilemez kalabiliyor.
///
/// NEDEN `Button` DEĞİL de `.onTapGesture`: devre dışı bir SwiftUI Button
/// dokunuşu YUTMUYOR, altındaki videoya geçiriyor — yani çipe dokunmak videoyu
/// duraklatırdı. Bu dosyanın eski düğmeleri de aynı sebeple Button değildi.
struct IhbarStatusChip: View {

    let mark: IhbarMark
    let onTap: () -> Void
    let onLongPress: () -> Void

    private var busy: Bool { mark.phase == .busy || mark.phase == .rejecting }

    var body: some View {
        HStack(spacing: 8) {
            if busy {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(.white)
                    .scaleEffect(0.7)
                    .frame(width: 18, height: 18)
            } else {
                Image(systemName: icon)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 18, height: 18)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.white)
                if let detail {
                    Text(detail)
                        .font(.caption2)
                        .foregroundStyle(.white.opacity(0.85))
                        .lineLimit(2)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(background, in: .rect(cornerRadius: 20))
        // Uzun bir sunucu cümlesi ekranın yarısını kaplamasın.
        .frame(maxWidth: 260, alignment: .leading)
        .contentShape(.rect)
        .onTapGesture(perform: onTap)
        .onLongPressGesture(minimumDuration: 0.5, perform: onLongPress)
        .accessibilityIdentifier("ihbarStatusChip")
    }

    private var background: Color {
        switch mark.phase {
        case .verified, .approved, .verifiedPending: IhbarPalette.green
        case .needsInfo, .blocked: IhbarPalette.amber
        case .error, .rejectError: IhbarPalette.red
        case .pending, .noToken: IhbarPalette.dim
        // Sahibin elediği video: soğuk ve mat — "burada yapılacak bir şey yok".
        case .notViolation: IhbarPalette.slate
        case .unknown, .markable, .busy, .rejecting: IhbarPalette.neutral
        }
    }

    private var icon: String {
        switch mark.phase {
        case .verified, .approved, .verifiedPending: "checkmark.circle.fill"
        case .needsInfo, .blocked: "exclamationmark.triangle.fill"
        case .error, .rejectError: "exclamationmark.circle"
        case .pending: "hourglass"
        case .noToken: "lock.fill"
        case .notViolation: "nosign"
        // busy/rejecting bu dala hiç gelmiyor (yerinde çember dönüyor).
        case .unknown, .markable, .busy, .rejecting: "flag.fill"
        }
    }

    private var title: String {
        switch mark.phase {
        // NÖTR EVRELER BU DALA HİÇ GELMİYOR: karar verilmemiş videoda çip
        // çizilmiyor (bkz. IhbarMark.saysSomething) — sahip videoyu yeni açtı,
        // karar vermediği zaten kesin. Dal yine de tam, çünkü eksik bir switch
        // derlenmiyor ve buraya bir gün gelinirse söylenecek doğru şey
        // "bekliyor"dur, "işaretle" değil.
        case .unknown, .markable: Strings.ihbarPending
        case .busy: Strings.ihbarMarking
        case .rejecting: Strings.ihbarNotViolationSending
        case .verified: Strings.ihbarMarked
        case .verifiedPending: Strings.ihbarVerifiedPending
        case .approved: Strings.ihbarApproved
        case .needsInfo: Strings.ihbarNeedsInfo
        case .blocked: Strings.ihbarBlocked
        case .pending: Strings.ihbarPending
        case .error: Strings.ihbarError
        case .rejectError: Strings.ihbarNotViolationError
        case .noToken: Strings.ihbarNoToken
        case .notViolation: Strings.ihbarNotViolationMarked
        }
    }

    /// Çipin alt satırı.
    ///
    /// Eksik alanlar her şeyin önünde: "konum eksik" sahibin YAPABİLECEĞİ tek
    /// şeyi söylüyor, sunucunun aynı şeyi anlatan uzun cümlesi ise ikinci satıra
    /// sığmıyor. Kalan durumlarda sunucunun kendi Türkçe metni geçiyor.
    private var detail: String? {
        if mark.phase == .noToken { return Strings.ihbarNoTokenDetail }
        // Kayıt yolda: cümle "işaretlendi" DEMEMELİ, ihbar henüz yola çıkmadı.
        if mark.phase == .verifiedPending { return Strings.ihbarVerifiedPendingDetail }
        if !mark.blockingFields.isEmpty {
            return Strings.ihbarMissingFields(
                mark.blockingFields.map(ihbarFieldLabel).joined(separator: ", ")
            )
        }
        if mark.detail == nil, mark.phase == .error || mark.phase == .rejectError {
            return Strings.ihbarNetworkError
        }
        if let detail = mark.detail { return detail }
        // Kaç videoluk bir ihbar olduğu: sağa atmak tek videoya karar vermek
        // gibi görünürken kaydın tamamını gönderiyor.
        if mark.mediaCount > 1 { return Strings.ihbarMediaCount(mark.mediaCount) }
        return nil
    }
}

/// Kaydırırken kartın üstünde beliren damga.
///
/// NEDEN VAR: kaydırma kararın KENDİSİ ve geri alma penceresi üç saniye. Sahip
/// parmağını kaldırmadan önce hangi kararı verdiğini görmek zorunda; rengin tek
/// başına söylediği şey "bir şey oluyor", hangi şey olduğu değil.
struct SwipeStamp: View {

    let decision: SwipeDecision
    let opacity: Double

    var body: some View {
        let report = decision == .report
        HStack(spacing: 8) {
            Image(systemName: report ? "flag.fill" : "nosign")
                .font(.system(size: 20, weight: .bold))
            Text(report ? Strings.swipeReport : Strings.swipeDismiss)
                .font(.title3.weight(.bold))
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(
            (report ? IhbarPalette.green : IhbarPalette.slate),
            in: .rect(cornerRadius: 12)
        )
        .opacity(max(0, min(1, opacity)))
        .accessibilityIdentifier(report ? "swipeStampReport" : "swipeStampDismiss")
    }
}

/// Bekleyen kararın geri alma çipi.
///
/// NEDEN GEREKLİ: karar verildiği anda kart uçuyor ve akış bir sonraki videoya
/// geçiyor — yani sahip kararını verdiği videoyu ARTIK GÖRMÜYOR. Geri alma
/// yolunun, kararın kendisiyle aynı ekranda ve aynı anda durması gerekiyor.
/// (İkinci yol da var: o sayfaya geri kaydırmak kararı iptal ediyor.)
struct UndoChip: View {

    let decision: SwipeOutcome
    let onUndo: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: decision == .report ? "flag.fill" : "nosign")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(decision == .report ? IhbarPalette.green : Color.white)
            Text(decision == .report ? Strings.swipeReported : Strings.swipeDismissed)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.white)
            Text(Strings.swipeUndo)
                .font(.subheadline.weight(.bold))
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .contentShape(.rect)
                .onTapGesture(perform: onUndo)
                .accessibilityIdentifier("undoChip")
        }
        .padding(.leading, 14)
        .padding(.trailing, 6)
        .padding(.vertical, 6)
        .background(.black.opacity(0.82), in: .rect(cornerRadius: 20))
    }
}
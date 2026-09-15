import Foundation

/// Düz akışın sayfa cebiri — SAF, SwiftUI'siz.
///
/// ─── NEDEN AYRI BİR DOSYA ──────────────────────────────────────────────────
/// ``GalleryViewModel`` ServiceLocator'a bağlı (depo, ayarlar, elle işaretler),
/// yani testte tek başına örneklenemiyor. Buradaki kurallar ise sistemin en
/// pahalı yan etkisini tetikliyor: hangi konuşmanın SİLİNECEĞİNİ belirliyorlar.
/// Sınanamayan bir yerde durmaları kabul edilemez.
///
/// ─── DÜZ AKIŞ NEDİR ────────────────────────────────────────────────────────
/// Eskiden iki iç içe sayfalayıcı vardı: dikey konuşmalar, yatay o konuşmanın
/// videoları. Yeni düzende yatay eksen KARARA ayrıldı (sağa at = ihbar, sola at
/// = ihlal değil), dolayısıyla videolar da dikey eksene taşındı: tek liste, her
/// sayfa bir video.
///
/// Sayfa kimliği "konuşmaAnahtarı#videoSırası" — uydurulmadı, ihbar
/// işaretlerinin ve elle küfür işaretlerinin ZATEN kullandığı anahtar
/// (``GalleryViewModel/ihbarKey(_:_:)``, ``ManualMarks``). Üçüncü bir kimlik
/// biçimi, aynı videonun üç ayrı adı olması demekti.
struct FeedPage: Identifiable, Equatable, Hashable {
    let conversationKey: String
    let mediaIndex: Int

    var id: String { "\(conversationKey)#\(mediaIndex)" }
}

/// Konuşma listesini düz sayfa listesine çevirir.
///
/// VİDEOSUZ KONUŞMA SAYFA ÜRETMEZ: akış zaten yalnızca videolu konuşmaları
/// çekiyor, ama boş bir konuşma sızarsa sayfasız kalmalı — yoksa sahip bakacak
/// hiçbir şeyi olmayan siyah bir sayfaya düşer ve ondan çıkmak için
/// kaydırdığında o konuşmayı silmiş olur.
func buildFeed(_ items: [Conversation]) -> [FeedPage] {
    items.flatMap { conversation in
        conversation.urls.indices.map { FeedPage(conversationKey: conversation.key, mediaIndex: $0) }
    }
}

/// Bir konuşmanın kapladığı sayfa sayısı.
func pageSpan(_ items: [Conversation], conversationKey: String) -> Int {
    items.first { $0.key == conversationKey }?.urls.count ?? 0
}

/// Bir sayfa yerleştiğinde yapılacak iş.
///
/// Silme, sistemin geri alınamaz tek yan etkisi (sunucudaki konuşma siliniyor),
/// bu yüzden kararı veren kural saf ve tek başına sınanabilir olmak zorunda.
enum SettleAction: Equatable {
    /// Yapılacak bir şey yok. Aynı konuşma içinde ilerlemek bu dala düşüyor.
    case none
    /// Silinmek üzere bekleyen konuşmaya geri dönüldü: silme iptal.
    case cancelPendingDelete
    /// Geride bırakılan konuşma silme sırasına alınsın.
    case queueDelete(conversationIndex: Int)
}

/// Yerleşen sayfaya bakarak ne yapılacağını söyler.
///
/// ─── ÜÇ KURAL, ÜÇÜ DE BİR ARIZAYI ÖNLÜYOR ──────────────────────────────────
///
/// 1. AYNI KONUŞMA İÇİNDE HİÇBİR ŞEY SİLİNMEZ. Düz akışta yukarı kaydırmak
///    artık çoğu zaman "aynı müşterinin bir sonraki videosu" demek. Kural
///    yalnızca sayfa değişimine baksaydı, üç videolu bir müşteride ikinci
///    videoya geçmek o müşteriyi silme sırasına alırdı — üstelik sahip hâlâ
///    onun videosunu izlerken ve beş saniye sonra sessizce.
///
/// 2. YALNIZCA İLERİ YÖN SİLER. Geri kaydırmak asla silmez; eski davranıştan
///    aynen geliyor ve tek geri alma yolu o.
///
/// 3. ÖNCEKİ KONUŞMANIN SIRASI ÇAĞRI ANINDA, CANLI LİSTEDEN ÇÖZÜLÜR. Anlık
///    görüntüde saklanan bir sıra, yukarıdaki bir konuşma silindiği an bir
///    eksik kalır ve YANLIŞ müşteri silinirdi. Bu yüzden fonksiyon sırayı değil
///    ANAHTARI alıyor.
func onSettled(
    previousConversationKey: String?,
    next: FeedPage,
    conversationKeys: [String],
    pendingDeleteKey: String?
) -> SettleAction {
    // Aynı konuşma: videolar arası gezinme. Silme yok.
    if previousConversationKey == next.conversationKey { return .none }

    // Silinmeyi bekleyen konuşmaya dönüldü: örtük geri alma.
    if let pendingDeleteKey, pendingDeleteKey == next.conversationKey {
        return .cancelPendingDelete
    }

    guard let previousConversationKey else { return .none }

    // Biri listede yoksa (silinmiş, henüz yüklenmemiş) hiçbir şey uydurmuyoruz.
    guard let previousIndex = conversationKeys.firstIndex(of: previousConversationKey),
          let nextIndex = conversationKeys.firstIndex(of: next.conversationKey),
          nextIndex > previousIndex else {
        return .none
    }

    return .queueDelete(conversationIndex: previousIndex)
}

// MARK: - Kaydırma eşiği

/// Kaydırmanın hangi kararı verdiği.
///
/// Sağa at = "bu görüntü gerçekten bir ihlal" (ihbar memura gider).
/// Sola at  = "bu görüntü ihlal DEĞİL" (memura gitmez).
///
/// NEDEN SAF: eşik yanlış seçildiğinde iki arıza da pahalı. Fazla düşükse,
/// videoyu izlerken yapılan küçük bir parmak kayması ihbar gönderiyor; fazla
/// yüksekse sahip kararını veremiyor ve ekranı zorluyor.
enum SwipeDecision: Equatable {
    case report
    case dismiss
}

/// Kararın verilmesi için gereken yatay yol — ekran genişliğinin oranı.
let swipeDistanceFraction: CGFloat = 0.25

/// Bu hızın üstünde fırlatma, mesafeye bakılmaksızın karar sayılıyor (nokta/sn).
let swipeVelocityThreshold: CGFloat = 900

/// Sürükleme bittiğinde karar.
///
/// MESAFE VEYA HIZ (ikisi birden değil): sahip ya kartı ortaya kadar taşıyor ya
/// da kısa ve hızlı bir fiske atıyor. Yalnızca mesafeye baksaydık fiske hiç
/// çalışmaz, yalnızca hıza baksaydık yavaş ve kararlı bir sürükleme karar
/// vermezdi.
///
/// nil = karar yok; kart yerine döner.
func decisionFor(translation: CGFloat, velocity: CGFloat, width: CGFloat) -> SwipeDecision? {
    // Sıfır/negatif genişlik yalnızca ölçüm daha yapılmadan gelirse olur; orada
    // karar vermek, ekranda olmayan bir hareketten ihbar üretmek olurdu.
    guard width > 0 else { return nil }

    let far = abs(translation) >= width * swipeDistanceFraction
    // HIZ YÖNÜYLE TUTARLI OLMALI: parmağını sola sürükleyip sağa fırlatan bir
    // hareket (geri çekme) karar sayılmamalı.
    let flung = abs(velocity) >= swipeVelocityThreshold && velocity * translation > 0
    guard far || flung else { return nil }

    return translation > 0 ? .report : .dismiss
}

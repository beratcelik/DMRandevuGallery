import Foundation

/// Kaydırma kararlarının GERİ ALMA penceresi — zamanlayıcısız defter, SAF.
///
/// ─── NEDEN GERİ ALMA PENCERESİ VAR ─────────────────────────────────────────
/// Kaydırma kararı hızlandırıyor; yanlış kararı da. Sağa atış bir ihbarı
/// emniyet birimine gönderiyor ve o tamamen geri alınamıyor (geri çekme memura
/// "bu ihbarı dikkate almayın" bildirimi gönderiyor). Üç saniyelik pencere,
/// parmağın kaydığı hâlleri ağa hiç çıkmadan yakalıyor.
///
/// ─── NEDEN KONUŞMANIN ANLIK GÖRÜNTÜSÜ SAKLANIYOR ───────────────────────────
/// Karar beklerken sahip bir sonraki müşteriye geçebiliyor ve o hareket geride
/// bıraktığı konuşmayı SİLME sırasına alıyor (beş saniye). Karar o anda
/// anahtarla aransaydı — liste artık o konuşmayı taşımıyor — hiçbir şey
/// bulunamaz ve karar sessizce kaybolurdu.
///
/// ÜÇ SANİYE < BEŞ SANİYE, BİLEREK: karar, konuşmanın silinmesinden önce yola
/// çıkıyor.
///
/// ─── NEDEN AYNI ANDA BİRDEN ÇOK KARAR BEKLEYEBİLİYOR ───────────────────────
/// Silme kuyruğunda tek bekleyen var (yenisi öncekini hemen işliyor), ama
/// burada sahip saniyede bir karar verebiliyor. Tek yuvalı bir defter, hızlı
/// kaydıran birinin ikinci kararını birincisini ağa göndererek karşılardı.
enum SwipeOutcome: Equatable {
    case report
    case dismiss
}

/// Bekleyen tek bir karar. Zamanlayıcı ``GalleryViewModel``'de, burada değil.
struct QueuedDecision: Equatable {
    let page: FeedPage
    /// Kararın uygulanacağı konuşmanın ANLIK GÖRÜNTÜSÜ.
    ///
    /// Anahtar DEĞİL: liste bu karar beklerken değişebiliyor (silme, yeni sayfa,
    /// bağlantı tazeleme) ve o hâlde anahtarla arama boş dönerdi.
    let conversation: Conversation
    let decision: SwipeOutcome
}

/// Bekleyen kararların defteri. Değer tipi: her işlem yeni bir defter
/// döndürüyor, böylece SwiftUI değişimi görüyor ve testte ara durumlar tek tek
/// incelenebiliyor.
struct DecisionLedger: Equatable {

    private var rows: [String: QueuedDecision] = [:]
    /// Ekleme SIRASI: geri alma çipi EN SON kararı gösteriyor ve sözlükte sıra
    /// yok. Sırayı ayrıca tutmazsak çip her çizimde başka bir kararı gösterirdi.
    private var order: [String] = []

    init() {}

    var count: Int { rows.count }
    var isEmpty: Bool { rows.isEmpty }

    subscript(pageID: String) -> QueuedDecision? { rows[pageID] }

    /// Bekleyen kararlar, EKLENME sırasıyla.
    var all: [QueuedDecision] { order.compactMap { rows[$0] } }

    /// En son verilen karar — geri alma çipinin gösterdiği.
    var latest: QueuedDecision? { order.last.flatMap { rows[$0] } }

    /// Yeni bir karar koyar. Aynı sayfaya ikinci karar öncekinin YERİNE geçiyor:
    /// sahip fikrini değiştirdiyse ağa yalnızca son kararı çıkmalı. (Çağıran,
    /// önceki kararın zamanlayıcısını iptal etmek zorunda.)
    mutating func put(_ decision: QueuedDecision) {
        if rows[decision.page.id] == nil { order.append(decision.page.id) }
        rows[decision.page.id] = decision
    }

    mutating func remove(_ pageID: String) {
        guard rows.removeValue(forKey: pageID) != nil else { return }
        order.removeAll { $0 == pageID }
    }

    /// Bir konuşmanın bekleyen kararları — toplu eleme bunları önce iptal ediyor.
    func of(conversationKey: String) -> [QueuedDecision] {
        all.filter { $0.page.conversationKey == conversationKey }
    }

    mutating func clear() {
        rows.removeAll()
        order.removeAll()
    }
}

package com.dmrandevu.gallery.ui

import com.dmrandevu.gallery.data.Conversation

/**
 * Kaydırma kararlarının GERİ ALMA penceresi — zamanlayıcısız defter, SAF.
 *
 * ─── NEDEN GERİ ALMA PENCERESİ VAR ─────────────────────────────────────────
 * Kaydırma kararı hızlandırıyor; yanlış kararı da. Sağa atış bir ihbarı
 * emniyet birimine gönderiyor ve o tamamen geri alınamıyor (geri çekme memura
 * "bu ihbarı dikkate almayın" bildirimi gönderiyor). Üç saniyelik pencere,
 * parmağın kaydığı hâlleri ağa hiç çıkmadan yakalıyor.
 *
 * ─── NEDEN KONUŞMANIN ANLIK GÖRÜNTÜSÜ SAKLANIYOR ───────────────────────────
 * Karar beklerken sahip bir sonraki müşteriye geçebiliyor ve o hareket geride
 * bıraktığı konuşmayı SİLME sırasına alıyor (beş saniye). Karar o anda
 * anahtarla aranmış olsaydı — liste artık o konuşmayı taşımıyor — hiçbir şey
 * bulunamaz ve karar sessizce kaybolurdu. Defter, kararın ihtiyaç duyduğu her
 * şeyi kendi içinde taşıyor.
 *
 * ÜÇ SANİYE < BEŞ SANİYE, BİLEREK: karar, konuşmanın silinmesinden önce yola
 * çıkıyor. İki sunucu birbirinden bağımsız (DMRandevu silme / trafik-ihbar
 * kararı), yani sıralama bir zorunluluk değil; ama tersi sıra, "sildiğim
 * müşterinin kararı gitti mi" sorusunu her seferinde sordurur.
 *
 * ─── NEDEN AYNI ANDA BİRDEN ÇOK KARAR BEKLEYEBİLİYOR ───────────────────────
 * Silme kuyruğunda tek bir bekleyen var (yenisi öncekini hemen işliyor), ama
 * burada sahip saniyede bir karar verebiliyor. Tek yuvalı bir defter, hızlı
 * kaydıran birinin ikinci kararını birincisini ağa göndererek karşılardı;
 * beklenen davranış ise ikisinin de kendi penceresini yaşaması.
 */
enum class SwipeOutcome { REPORT, DISMISS }

/** Bekleyen tek bir karar. Zamanlayıcı [GalleryViewModel]'de, burada değil. */
data class QueuedDecision(
    val page: FeedPage,
    /**
     * Kararın uygulanacağı konuşmanın ANLIK GÖRÜNTÜSÜ.
     *
     * Anahtar DEĞİL: liste bu karar beklerken değişebiliyor (silme, yeni sayfa,
     * bağlantı tazeleme) ve o hâlde anahtarla arama boş dönerdi.
     */
    val conversation: Conversation,
    val decision: SwipeOutcome,
)

/**
 * Bekleyen kararların defteri. Değişmez (immutable): her işlem yeni bir defter
 * döndürüyor, böylece Compose durum karşılaştırması çalışıyor ve testte ara
 * durumlar tek tek incelenebiliyor.
 */
class DecisionLedger private constructor(
    private val rows: Map<String, QueuedDecision>,
) {
    constructor() : this(emptyMap())

    val size: Int get() = rows.size
    fun isEmpty(): Boolean = rows.isEmpty()

    /** Bu sayfada bekleyen karar, ya da null. */
    operator fun get(pageId: String): QueuedDecision? = rows[pageId]

    /** Tüm bekleyen kararlar; sıra garanti edilmiyor. */
    fun all(): List<QueuedDecision> = rows.values.toList()

    /**
     * Yeni bir karar koyar. Aynı sayfaya ikinci karar öncekinin YERİNE geçiyor:
     * sahip fikrini değiştirdiyse ağa yalnızca son kararı çıkmalı. (Çağıran,
     * önceki kararın zamanlayıcısını iptal etmek zorunda.)
     */
    fun put(decision: QueuedDecision): DecisionLedger =
        DecisionLedger(rows + (decision.page.id to decision))

    fun remove(pageId: String): DecisionLedger =
        if (pageId in rows) DecisionLedger(rows - pageId) else this

    /** Bir konuşmanın bekleyen kararları — toplu eleme bunları önce iptal ediyor. */
    fun of(conversationKey: String): List<QueuedDecision> =
        rows.values.filter { it.page.conversationKey == conversationKey }

    fun clear(): DecisionLedger = if (rows.isEmpty()) this else DecisionLedger()
}

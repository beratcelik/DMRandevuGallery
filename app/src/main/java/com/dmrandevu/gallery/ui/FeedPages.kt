package com.dmrandevu.gallery.ui

import com.dmrandevu.gallery.data.Conversation

/**
 * Düz akışın sayfa cebiri — SAF ve Android'siz.
 *
 * ─── NEDEN AYRI BİR DOSYA ──────────────────────────────────────────────────
 * [GalleryViewModel] alan başlatmada ServiceLocator okuyor (repository, ayarlar,
 * elle işaretler), yani JVM testinde örneklenemiyor. Buradaki kurallar ise
 * sistemin en pahalı yan etkisini tetikliyor: hangi konuşmanın SİLİNECEĞİNİ
 * belirliyorlar. Sınanamayan bir yerde durmaları kabul edilemez.
 *
 * ─── DÜZ AKIŞ NEDİR ────────────────────────────────────────────────────────
 * Eskiden iki iç içe sayfalayıcı vardı: dikey konuşmalar, yatay o konuşmanın
 * videoları. Yeni düzende yatay eksen KARARA ayrıldı (sağa at = ihbar, sola at
 * = ihlal değil), dolayısıyla videolar da dikey eksene taşındı: tek bir liste,
 * her sayfa bir video.
 *
 * Sayfa kimliği "konuşmaAnahtarı#videoSırası" — bu dizge uydurulmadı, ihbar
 * işaretlerinin ve elle küfür işaretlerinin ZATEN kullandığı anahtar
 * (GalleryViewModel.ihbarKey, ManualMarks). Üçüncü bir kimlik biçimi üretmek,
 * aynı videonun üç ayrı adı olması demekti.
 */
data class FeedPage(
    val conversationKey: String,
    val mediaIndex: Int,
) {
    val id: String get() = "$conversationKey#$mediaIndex"
}

/**
 * Konuşma listesini düz sayfa listesine çevirir.
 *
 * VİDEOSUZ KONUŞMA SAYFA ÜRETMEZ: akış zaten yalnızca videolu konuşmaları
 * çekiyor (loadMore süzüyor), ama boş bir konuşma buraya sızarsa sayfasız
 * kalmalı — yoksa sahip bakacak hiçbir şeyi olmayan siyah bir sayfaya düşer ve
 * ondan çıkmak için kaydırdığında o konuşmayı silmiş olur.
 */
fun buildFeed(items: List<Conversation>): List<FeedPage> =
    items.flatMap { conversation ->
        conversation.urls.indices.map { index -> FeedPage(conversation.key, index) }
    }

/** Bir konuşmanın kapladığı sayfa sayısı. */
fun pageSpan(items: List<Conversation>, conversationKey: String): Int =
    items.firstOrNull { it.key == conversationKey }?.urls?.size ?: 0

/** Bir konuşmanın İLK sayfasının sırası, ya da -1. */
fun firstPageOf(feed: List<FeedPage>, conversationKey: String): Int =
    feed.indexOfFirst { it.conversationKey == conversationKey }

/**
 * Bir sayfa yerleştiğinde yapılacak iş.
 *
 * Silme, sistemin geri alınamaz tek yan etkisi (sunucudaki konuşma siliniyor),
 * bu yüzden kararı veren kural saf ve tek başına sınanabilir olmak zorunda.
 */
sealed interface SettleAction {
    /** Yapılacak bir şey yok. Aynı konuşma içinde ilerlemek bu dala düşüyor. */
    data object None : SettleAction

    /** Silinmek üzere bekleyen konuşmaya geri dönüldü: silme iptal. */
    data object CancelPendingDelete : SettleAction

    /** Geride bırakılan konuşma silme sırasına alınsın. */
    data class QueueDelete(val conversationIndex: Int) : SettleAction
}

/**
 * Yerleşen sayfaya bakarak ne yapılacağını söyler.
 *
 * ─── ÜÇ KURAL, ÜÇÜ DE BİR ARIZAYI ÖNLÜYOR ──────────────────────────────────
 *
 * 1. AYNI KONUŞMA İÇİNDE HİÇBİR ŞEY SİLİNMEZ. Düz akışta yukarı kaydırmak
 *    artık çoğu zaman "aynı müşterinin bir sonraki videosu" demek. Kural
 *    yalnızca sayfa değişimine baksaydı, üç videolu bir müşteride ikinci
 *    videoya geçmek o müşteriyi silme sırasına alırdı — üstelik sahip hâlâ
 *    onun videosunu izlerken ve beş saniye sonra sessizce.
 *
 * 2. YALNIZCA İLERİ YÖN SİLER. Geri kaydırmak asla silmez; bu kural eski
 *    davranıştan aynen geliyor ve tek geri alma yolu o.
 *
 * 3. ÖNCEKİ KONUŞMANIN SIRASI ÇAĞRI ANINDA, CANLI LİSTEDEN ÇÖZÜLÜR. Anlık
 *    görüntüde saklanan bir sıra, yukarıdaki bir konuşma silindiği an bir
 *    eksik kalır ve YANLIŞ müşteri silinirdi. Bu yüzden fonksiyon sırayı değil
 *    ANAHTARI alıyor.
 */
fun onSettled(
    previousConversationKey: String?,
    next: FeedPage,
    conversationKeys: List<String>,
    pendingDeleteKey: String?,
): SettleAction {
    // Aynı konuşma: videolar arası gezinme. Silme yok.
    if (previousConversationKey == next.conversationKey) return SettleAction.None

    // Silinmeyi bekleyen konuşmaya dönüldü: örtük geri alma.
    if (pendingDeleteKey != null && pendingDeleteKey == next.conversationKey) {
        return SettleAction.CancelPendingDelete
    }

    if (previousConversationKey == null) return SettleAction.None

    val previousIndex = conversationKeys.indexOf(previousConversationKey)
    val nextIndex = conversationKeys.indexOf(next.conversationKey)
    // Biri listede yoksa (silinmiş, henüz yüklenmemiş) hiçbir şey uydurmuyoruz.
    if (previousIndex == -1 || nextIndex == -1) return SettleAction.None
    if (nextIndex <= previousIndex) return SettleAction.None

    return SettleAction.QueueDelete(previousIndex)
}

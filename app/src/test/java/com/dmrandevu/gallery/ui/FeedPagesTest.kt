package com.dmrandevu.gallery.ui

import com.dmrandevu.gallery.data.Conversation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Düz akışın sayfa cebiri.
 *
 * NEDEN TEST EDİLİYOR: bu kurallar sistemin geri alınamaz tek yan etkisini
 * tetikliyor — hangi müşterinin SİLİNECEĞİNİ belirliyorlar. Akış düzleşince
 * "sayfa değişti" artık "müşteri değişti" demek değil ve kural bunu
 * ayırt edemezse, sahip aynı müşterinin ikinci videosuna geçtiği anda o
 * müşteri beş saniye sonra sessizce siliniyor.
 */
class FeedPagesTest {

    private fun conversation(key: String, videos: Int) = Conversation(
        salonId = key.substringBefore(':'),
        clientId = key.substringAfter(':'),
        clientName = key,
        urls = List(videos) { "https://cdn/$key/$it.mp4" },
    )

    private val a = conversation("s:a", 3)
    private val b = conversation("s:b", 1)
    private val c = conversation("s:c", 2)
    private val items = listOf(a, b, c)
    private val keys = items.map { it.key }
    private val feed = buildFeed(items)

    @Test
    fun `her video bir sayfa`() {
        assertEquals(6, feed.size)
        assertEquals(FeedPage("s:a", 0), feed[0])
        assertEquals(FeedPage("s:a", 2), feed[2])
        assertEquals(FeedPage("s:b", 0), feed[3])
        assertEquals(FeedPage("s:c", 1), feed[5])
    }

    @Test
    fun `videosuz konuşma sayfa üretmiyor`() {
        // Sayfasız kalmalı: bakacak hiçbir şeyi olmayan siyah bir sayfadan
        // çıkmak için kaydırmak, o müşteriyi silmek olurdu.
        val feed = buildFeed(listOf(conversation("s:bos", 0), a))
        assertEquals(3, feed.size)
        assertEquals("s:a", feed.first().conversationKey)
    }

    @Test
    fun `sayfa kimliği işaret anahtarıyla aynı biçimde`() {
        // İhbar işaretleri ve elle küfür işaretleri ZATEN bu dizgiyi kullanıyor.
        // Üçüncü bir kimlik biçimi, aynı videonun üç ayrı adı olması demekti.
        assertEquals("s:a#2", FeedPage("s:a", 2).id)
    }

    @Test
    fun `aynı konuşma içinde ilerlemek HİÇBİR ŞEY silmiyor`() {
        // Bu testin koruduğu arıza: üç videolu bir müşteride ikinci videoya
        // geçmek, sahip hâlâ onun videosunu izlerken o müşteriyi silme sırasına
        // alıyordu.
        val action = onSettled(
            previousConversationKey = "s:a",
            next = FeedPage("s:a", 1),
            conversationKeys = keys,
            pendingDeleteKey = null,
        )
        assertEquals(SettleAction.None, action)
    }

    @Test
    fun `ileri yönde konuşma değişimi silme sırasına alıyor`() {
        val action = onSettled(
            previousConversationKey = "s:a",
            next = FeedPage("s:b", 0),
            conversationKeys = keys,
            pendingDeleteKey = null,
        )
        assertEquals(SettleAction.QueueDelete(0), action)
    }

    @Test
    fun `geri kaydırmak ASLA silmiyor`() {
        val action = onSettled(
            previousConversationKey = "s:c",
            next = FeedPage("s:b", 0),
            conversationKeys = keys,
            pendingDeleteKey = null,
        )
        assertEquals(SettleAction.None, action)
    }

    @Test
    fun `silinmeyi bekleyen konuşmaya dönmek örtük geri alma`() {
        val action = onSettled(
            previousConversationKey = "s:b",
            next = FeedPage("s:a", 2),
            conversationKeys = keys,
            pendingDeleteKey = "s:a",
        )
        assertEquals(SettleAction.CancelPendingDelete, action)
    }

    @Test
    fun `listede olmayan konuşma hiçbir şey uydurtmuyor`() {
        // Önceki konuşma silinmiş olabilir; orada bir sıra uydurmak YANLIŞ
        // müşteriyi silerdi.
        val action = onSettled(
            previousConversationKey = "s:yok",
            next = FeedPage("s:b", 0),
            conversationKeys = keys,
            pendingDeleteKey = null,
        )
        assertEquals(SettleAction.None, action)
    }

    @Test
    fun `ilk yerleşmede silinecek bir şey yok`() {
        val action = onSettled(
            previousConversationKey = null,
            next = FeedPage("s:a", 0),
            conversationKeys = keys,
            pendingDeleteKey = null,
        )
        assertEquals(SettleAction.None, action)
    }

    @Test
    fun `konuşmanın kapladığı sayfa sayısı`() {
        // commit() bu kadar geri adım atıyor: tek sayfa geri gitmek, izlenen
        // videonun başka bir müşteriye kaymasıyla sonuçlanırdı.
        assertEquals(3, pageSpan(items, "s:a"))
        assertEquals(1, pageSpan(items, "s:b"))
        assertEquals(0, pageSpan(items, "s:yok"))
    }

    @Test
    fun `konuşmanın ilk sayfası`() {
        assertEquals(0, firstPageOf(feed, "s:a"))
        assertEquals(3, firstPageOf(feed, "s:b"))
        assertEquals(-1, firstPageOf(feed, "s:yok"))
    }
}

package com.dmrandevu.gallery.ui

import com.dmrandevu.gallery.data.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kaydırma kararlarının geri alma defteri.
 *
 * EN KRİTİK DAVRANIŞ: karar, konuşması listeden SİLİNSE BİLE hayatta kalmalı.
 * Sahip son videoyu karara bağlayıp bir sonraki müşteriye geçtiğinde, geride
 * bıraktığı konuşma silme sırasına giriyor; karar o anda anahtarla aransaydı
 * hiçbir şey bulunamaz ve sessizce kaybolurdu.
 */
class DecisionQueueTest {

    private val conversation = Conversation(
        salonId = "s", clientId = "a", clientName = "a",
        urls = listOf("https://cdn/1.mp4", "https://cdn/2.mp4"),
    )
    private val page = FeedPage(conversation.key, 0)

    @Test
    fun `boş defter`() {
        val ledger = DecisionLedger()
        assertTrue(ledger.isEmpty())
        assertNull(ledger[page.id])
    }

    @Test
    fun `karar konuşmanın ANLIK GÖRÜNTÜSÜNÜ taşıyor`() {
        val ledger = DecisionLedger()
            .put(QueuedDecision(page, conversation, SwipeOutcome.REPORT))

        val queued = ledger[page.id]
        assertNotNull(queued)
        // Anahtar değil, konuşmanın kendisi: liste bu karar beklerken
        // değişebiliyor ve anahtarla arama boş dönerdi.
        assertEquals(conversation, queued!!.conversation)
        assertEquals(SwipeOutcome.REPORT, queued.decision)
    }

    @Test
    fun `aynı sayfaya ikinci karar öncekinin YERİNE geçiyor`() {
        val ledger = DecisionLedger()
            .put(QueuedDecision(page, conversation, SwipeOutcome.REPORT))
            .put(QueuedDecision(page, conversation, SwipeOutcome.DISMISS))

        assertEquals(1, ledger.size)
        // Sahip fikrini değiştirdiyse ağa yalnızca son kararı çıkmalı.
        assertEquals(SwipeOutcome.DISMISS, ledger[page.id]!!.decision)
    }

    @Test
    fun `aynı anda birden çok karar bekleyebiliyor`() {
        // Silme kuyruğunda tek bekleyen var; burada sahip saniyede bir karar
        // verebiliyor ve ikisi de kendi penceresini yaşamalı.
        val ikinci = FeedPage(conversation.key, 1)
        val ledger = DecisionLedger()
            .put(QueuedDecision(page, conversation, SwipeOutcome.REPORT))
            .put(QueuedDecision(ikinci, conversation, SwipeOutcome.DISMISS))

        assertEquals(2, ledger.size)
        assertEquals(SwipeOutcome.REPORT, ledger[page.id]!!.decision)
        assertEquals(SwipeOutcome.DISMISS, ledger[ikinci.id]!!.decision)
    }

    @Test
    fun `geri alma yalnızca o sayfayı kaldırıyor`() {
        val ikinci = FeedPage(conversation.key, 1)
        val ledger = DecisionLedger()
            .put(QueuedDecision(page, conversation, SwipeOutcome.REPORT))
            .put(QueuedDecision(ikinci, conversation, SwipeOutcome.DISMISS))
            .remove(page.id)

        assertNull(ledger[page.id])
        assertNotNull(ledger[ikinci.id])
    }

    @Test
    fun `bir konuşmanın bekleyen kararları toplu elemede iptal edilebiliyor`() {
        val baska = Conversation(salonId = "s", clientId = "b", urls = listOf("https://cdn/3.mp4"))
        val ledger = DecisionLedger()
            .put(QueuedDecision(page, conversation, SwipeOutcome.REPORT))
            .put(QueuedDecision(FeedPage(baska.key, 0), baska, SwipeOutcome.REPORT))

        assertEquals(1, ledger.of(conversation.key).size)
        assertEquals(page.id, ledger.of(conversation.key).first().page.id)
    }

    @Test
    fun `karar konuşması listeden kalksa da hayatta kalıyor`() {
        // BU TESTİN KORUDUĞU ARIZA: karar uygulanırken konuşma anahtarla
        // aransaydı — liste artık onu taşımıyor — hiçbir şey bulunamaz ve
        // karar sessizce kaybolurdu.
        val items = mutableListOf(conversation)
        val ledger = DecisionLedger()
            .put(QueuedDecision(page, conversation, SwipeOutcome.REPORT))

        items.removeAll { it.key == conversation.key }

        val queued = ledger[page.id]!!
        assertTrue(items.none { it.key == conversation.key })
        assertEquals("a", queued.conversation.clientId)
    }
}

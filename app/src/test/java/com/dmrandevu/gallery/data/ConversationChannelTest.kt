package com.dmrandevu.gallery.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Galeri artık Instagram'la birlikte Messenger ve WhatsApp konuşmalarını da
 * taşıyor ve kanal iki şeye karar veriyor: başlıkta adın nasıl yazıldığına ve
 * ihbar kaydırmasının o videoda çalışıp çalışmadığına.
 *
 * NEDEN iOS TARAFINDAKİ ConversationChannelTests İLE AYNI SENARYOLAR: kural iki
 * uygulamada ayrı yazıldı; ayrışırlarsa bir platform Messenger videosunu ihbar
 * sistemine göndermeye çalışır ve fark ancak sahip telefon değiştirdiğinde
 * görülür.
 */
class ConversationChannelTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(body: String): Conversation = json.decodeFromString(body)

    @Test
    fun `sunucunun söylediği kanal okunuyor`() {
        assertEquals(ChatChannel.INSTAGRAM, decode("""{"salonId":"1","clientId":"77","channel":"instagram"}""").channel)
        assertEquals(ChatChannel.FACEBOOK, decode("""{"salonId":"1","clientId":"fb:42","channel":"facebook"}""").channel)
        assertEquals(ChatChannel.WHATSAPP, decode("""{"salonId":"1","clientId":"wa:90555","channel":"whatsapp"}""").channel)
    }

    /**
     * Kanal alanı yeni. Eski bir sunucu onu göndermiyor ama Messenger ve
     * WhatsApp konuşmalarını yine aynı önekle adlandırıyordu; uygulama o
     * sunucuya karşı da Messenger videosunda ihbar kaydırmasını kapatmalı.
     */
    @Test
    fun `kanal alanı olmayan eski yanıt önekten okunuyor`() {
        assertEquals(ChatChannel.INSTAGRAM, decode("""{"salonId":"1","clientId":"17841400"}""").channel)
        assertEquals(ChatChannel.FACEBOOK, decode("""{"salonId":"1","clientId":"fb:42"}""").channel)
        assertEquals(ChatChannel.WHATSAPP, decode("""{"salonId":"1","clientId":"wa:90555"}""").channel)
    }

    @Test
    fun `yalnızca Instagram adının başında @ var`() {
        assertEquals("@kaan.ig", Conversation("1", "77", clientName = "kaan.ig").displayName)
        assertEquals(
            "Ayşe Yılmaz",
            Conversation("1", "fb:42", clientName = "Ayşe Yılmaz", channelName = "facebook").displayName
        )
        assertEquals(
            "+905551112233",
            Conversation("1", "wa:905551112233", clientName = "+905551112233", channelName = "whatsapp").displayName
        )
    }

    /** WhatsApp videosunun adresi sunucuya özgü bir biçim; uygulama onu olduğu gibi taşımalı. */
    @Test
    fun `WhatsApp video adresi dokunulmadan taşınıyor`() {
        val conversation = decode(
            """{"salonId":"1","clientId":"wa:90555","channel":"whatsapp","urls":["wa-media://1/998877"]}"""
        )
        assertEquals(listOf("wa-media://1/998877"), conversation.urls)
    }
}

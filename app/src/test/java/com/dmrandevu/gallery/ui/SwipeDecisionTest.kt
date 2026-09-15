package com.dmrandevu.gallery.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Kaydırmanın hangi kararı verdiği.
 *
 * İKİ YÖNDE DE PAHALI: eşik fazla düşükse videoyu izlerken yapılan küçük bir
 * parmak kayması emniyet birimine ihbar gönderiyor; fazla yüksekse sahip
 * kararını veremiyor ve ekranı zorluyor.
 */
class SwipeDecisionTest {

    private val width = 400f // dp

    @Test
    fun `kısa sürükleme karar değil`() {
        assertNull(decisionFor(dragXdp = 40f, velocityDpPerSec = 0f, widthDp = width))
    }

    @Test
    fun `eşiği geçen sağ sürükleme ihbar`() {
        assertEquals(
            SwipeDecision.REPORT,
            decisionFor(dragXdp = width * 0.3f, velocityDpPerSec = 0f, widthDp = width),
        )
    }

    @Test
    fun `eşiği geçen sol sürükleme eleme`() {
        assertEquals(
            SwipeDecision.DISMISS,
            decisionFor(dragXdp = -width * 0.3f, velocityDpPerSec = 0f, widthDp = width),
        )
    }

    @Test
    fun `hızlı fiske kısa olsa da karar`() {
        // Sahip ya kartı ortaya kadar taşıyor ya da kısa ve hızlı bir fiske
        // atıyor. Yalnızca mesafeye baksaydık fiske hiç çalışmazdı.
        assertEquals(
            SwipeDecision.REPORT,
            decisionFor(dragXdp = 30f, velocityDpPerSec = 1200f, widthDp = width),
        )
    }

    @Test
    fun `hız yönü sürükleme yönüyle çelişirse karar yok`() {
        // Parmağını sola sürükleyip sağa fırlatan hareket GERİ ÇEKMEDİR;
        // karar saymak, vazgeçen sahibin ihbarını göndermek olurdu.
        assertNull(decisionFor(dragXdp = -30f, velocityDpPerSec = 1200f, widthDp = width))
    }

    @Test
    fun `genişlik ölçülmeden karar verilmiyor`() {
        // Ölçüm gelmeden karar vermek, ekranda olmayan bir hareketten ihbar
        // üretmek olurdu.
        assertNull(decisionFor(dragXdp = 500f, velocityDpPerSec = 0f, widthDp = 0f))
    }

    @Test
    fun `eşiğin tam üstü karar sayılıyor`() {
        assertEquals(
            SwipeDecision.REPORT,
            decisionFor(
                dragXdp = width * SWIPE_DISTANCE_FRACTION,
                velocityDpPerSec = 0f,
                widthDp = width,
            ),
        )
    }
}

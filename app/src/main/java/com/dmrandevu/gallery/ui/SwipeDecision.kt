package com.dmrandevu.gallery.ui

import kotlin.math.abs

/**
 * Kaydırmanın hangi kararı verdiği — SAF ve Android'siz.
 *
 * Sağa at = "bu görüntü gerçekten bir ihlal" (ihbar memura gider).
 * Sola at  = "bu görüntü ihlal DEĞİL" (memura gitmez).
 *
 * NEDEN AYRI VE SAF: eşik yanlış seçildiğinde iki arıza da pahalı. Fazla
 * düşükse, videoyu izlerken yapılan küçük bir parmak kayması ihbar gönderiyor;
 * fazla yüksekse sahip kararını veremiyor ve ekranı zorluyor. Sayıların testte
 * tek tek sınanabilmesi gerekiyor.
 */
enum class SwipeDecision { REPORT, DISMISS }

/** Kararın verilmesi için gereken yatay yol — ekran genişliğinin oranı. */
const val SWIPE_DISTANCE_FRACTION = 0.25f

/**
 * Bu hızın üstünde fırlatma, mesafeye bakılmaksızın karar sayılıyor (dp/sn).
 *
 * NEDEN dp/sn, px/sn DEĞİL: piksel hızı ekran yoğunluğuna bağlı. Aynı parmak
 * hareketi yoğun bir ekranda iki kat büyük bir sayı üretir ve eşik telefondan
 * telefona değişirdi.
 */
const val SWIPE_VELOCITY_DP = 900f

/**
 * Sürükleme bittiğinde karar.
 *
 * MESAFE VEYA HIZ (ikisi birden değil): sahip ya kartı ortaya kadar taşıyor ya
 * da kısa ve hızlı bir fiske atıyor. Yalnızca mesafeye baksaydık fiske hiç
 * çalışmaz, yalnızca hıza baksaydık yavaş ve kararlı bir sürükleme karar
 * vermezdi.
 *
 * null = karar yok; kart yerine döner.
 */
fun decisionFor(dragXdp: Float, velocityDpPerSec: Float, widthDp: Float): SwipeDecision? {
    // Sıfır/negatif genişlik yalnızca ölçüm daha yapılmadan gelirse olur;
    // orada karar vermek, ekranda olmayan bir hareketten ihbar üretmek olurdu.
    if (widthDp <= 0f) return null

    val far = abs(dragXdp) >= widthDp * SWIPE_DISTANCE_FRACTION
    // HIZ YÖNÜYLE TUTARLI OLMALI: parmağını sola sürükleyip sağa fırlatan bir
    // hareket (geri çekme) karar sayılmamalı. İşaretler ayrışıyorsa mesafeye
    // bakılıyor.
    val flung = abs(velocityDpPerSec) >= SWIPE_VELOCITY_DP && velocityDpPerSec * dragXdp > 0f
    if (!far && !flung) return null

    return if (dragXdp > 0f) SwipeDecision.REPORT else SwipeDecision.DISMISS
}

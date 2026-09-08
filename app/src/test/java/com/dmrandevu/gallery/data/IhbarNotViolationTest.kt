package com.dmrandevu.gallery.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * "İhlal değil" dokunuşunun düğmeye çevrilmesi.
 *
 * NEDEN TEST EDİLİYOR: bu eşlemenin iki tarafa da sapması pahalı. Olumsuz
 * dokunuş düğmede görünmezse sahip aynı videoyu tekrar tekrar eler (ve her
 * seferinde sunucuda bir geri çekme tetikler); tersine, dokunulmamış bir kaydı
 * "elendi" göstermek sahibin ikinci kez bakmasını engeller ve gerçek bir ihlal
 * sessizce çöpe gider. İkisi de ekranda hata gibi görünmüyor — ancak burada
 * yakalanır.
 */
class IhbarNotViolationTest {

    /**
     * Sunucu tarafındaki asıl tuzak: sahip "ihlal değil" dediğinde kayıt
     * TRAFIK_DISI ile reddediliyor ve durum adı "reddedildi" oluyor — ama aynı
     * ad, yöneticinin konsoldan yaptığı reddin de sonucu. Karar durum adına
     * bırakılsaydı iki farklı olay tek renge düşerdi.
     */
    @Test
    fun `olumsuz dokunuş durum adının önünde geliyor`() {
        val marked = IhbarStatusItem(
            state = "reddedildi",
            stateLabel = "Reddedildi",
            notViolation = true
        ).toMark()
        assertEquals(IhbarPhase.NOT_VIOLATION, marked.phase)

        val byAdmin = IhbarStatusItem(state = "reddedildi", stateLabel = "Reddedildi").toMark()
        assertEquals(IhbarPhase.MARKABLE, byAdmin.phase)
    }

    /**
     * Memura gitmiş bir ihbar da elenebiliyor (sunucuda geri çekme yolu var).
     * Yeşil kalsaydı sahip dokunuşunun yutulduğunu sanırdı.
     */
    @Test
    fun `onaylanmış kayıt elendiğinde yeşil kalmıyor`() {
        val mark = IhbarStatusItem(
            state = "onaylandi",
            humanVerified = true,
            notViolation = true
        ).toMark()
        assertEquals(IhbarPhase.NOT_VIOLATION, mark.phase)
    }

    /**
     * Sunucu bayrak yerine kendi durum adını göndermeyi seçerse de düğme doğru
     * boyanmalı; iki taraf tek bir alanın adına bağlı kalmamalı.
     */
    @Test
    fun `durum adı tek başına da yeterli`() {
        val mark = IhbarStatusItem(state = "ihlal_degil", stateLabel = "İhlal değil").toMark()
        assertEquals(IhbarPhase.NOT_VIOLATION, mark.phase)
    }

    /** Sunucunun cümlesi düğmenin alt satırına geçiyor; kendi metnimizi uydurmuyoruz. */
    @Test
    fun `olumsuz yanıtın cümlesi düğmeye taşınıyor`() {
        val mark = IhbarRejectResponse(
            state = "kapandi",
            stateLabel = "Kapandı",
            notViolation = true,
            message = "İhbar geri çekildi; memurlara bilgi gönderiliyor."
        ).toMark()
        assertEquals(IhbarPhase.NOT_VIOLATION, mark.phase)
        assertEquals("İhbar geri çekildi; memurlara bilgi gönderiliyor.", mark.detail)
    }

    /**
     * 200 döndü ama dokunuş yazılamadı — video ihbar sisteminde henüz yok.
     * Arduvaza boyamak, sahibe elemediği bir kaydı elenmiş gösterirdi.
     */
    @Test
    fun `yazılmamış dokunuş elenmiş gibi görünmüyor`() {
        val mark = IhbarRejectResponse(
            state = "bilinmiyor",
            stateLabel = "Bilinmiyor",
            message = "Bu video ihbar sisteminde bulunamadı."
        ).toMark()
        assertEquals(IhbarPhase.PENDING, mark.phase)
    }

    /**
     * Gerileme koruması: olumsuz alanın eklenmesi olumlu yolu bozmamalı. Onay
     * yanıtı bu bayrağı hiç taşımıyor ve taşımamalı — o uca basmak, varsa
     * önceki elemeyi zaten geçersiz kılıyor.
     */
    @Test
    fun `onay yolu değişmedi`() {
        val mark = IhbarApproveResponse(
            state = "inceleniyor",
            stateLabel = "İnceleniyor",
            humanVerified = true,
            message = "İhbar işaretlendi."
        ).toMark()
        assertEquals(IhbarPhase.VERIFIED, mark.phase)
        assertEquals("İhbar işaretlendi.", mark.detail)
    }

    /** Bilinmeyen alanlar yok sayılıyor; eski sunucu yeni istemciyi kırmıyor. */
    @Test
    fun `bayrak gelmediğinde düğme hiçbir şey iddia etmiyor`() {
        val mark = IhbarStatusItem(state = "inceleniyor", stateLabel = "İnceleniyor").toMark()
        assertEquals(IhbarPhase.MARKABLE, mark.phase)
        assertNull(mark.matchedBy)
    }
}

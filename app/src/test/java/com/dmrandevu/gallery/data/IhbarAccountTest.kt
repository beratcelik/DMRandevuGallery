package com.dmrandevu.gallery.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * İhbar düğmesinin görünürlüğü tek bir karşılaştırmaya dayanıyor ve yanlış
 * tarafa düşmesinin iki bedeli de ağır: doğru hesapta kaybolursa sahip ihbar
 * gönderemediğini fark etmeyebilir, yanlış hesapta belirirse ihbar hattına ait
 * olmayan bir videoyu emniyet birimine sokmayı dener.
 *
 * NEDEN iOS TARAFINDAKİ IhbarAccountTests İLE AYNI SENARYOLAR: kapı iki
 * uygulamada ayrı ayrı yazıldı. Testler ayrışırsa iki platform sessizce farklı
 * davranmaya başlar ve fark, ancak sahip telefon değiştirdiğinde görülür.
 */
class IhbarAccountTest {

    @Test
    fun `kendi hesabımız her yazımda eşleşiyor`() {
        assertTrue(IhbarAccount.matches("trafik_cezasi"))
        assertTrue(IhbarAccount.matches("@trafik_cezasi"))
        assertTrue(IhbarAccount.matches("  trafik_cezasi  "))
    }

    /**
     * Karşılaştırmanın YERELE takılmadığının kanıtı: `lowercase()` değişmez
     * yerelin kurallarını uyguluyor. Yerele bakan `toLowerCase()`'e geçildiği
     * gün Türkçe kurulu bir telefonda "TRAFIK_CEZASI" noktasız harflere düşer
     * ve bu test kırılır — kırılması gereken tam olarak burası.
     */
    @Test
    fun `harf durumu kararı değiştirmiyor`() {
        assertTrue(IhbarAccount.matches("Trafik_Cezasi"))
        assertTrue(IhbarAccount.matches("TRAFIK_CEZASI"))
    }

    /**
     * Giriş alanına kullanıcı adı yerine sayısal kimlik yazmak da geçerli bir
     * kullanım: galeri sayfaları zaten bu kimlikle isteniyor.
     */
    @Test
    fun `sayısal kimlik de kabul ediliyor`() {
        assertTrue(IhbarAccount.matches(IhbarAccount.IG_ID))
        assertTrue(IhbarAccount.matches(" 17841468848724091 "))
    }

    @Test
    fun `öteki hesap asla eşleşmiyor`() {
        assertFalse(IhbarAccount.matches("trafykamerasi"))
        assertFalse(IhbarAccount.matches("@Trafykamerasi"))
        assertFalse(IhbarAccount.matches("17841472755272054"))
    }

    /**
     * Boş ya da tanımsız ayar hiçbir hesap değil: aksi hâlde düğme, hesabın
     * henüz çözülmediği bir anda ait olmadığı yerde belirebilirdi.
     */
    @Test
    fun `boş girdi hesap sayılmıyor`() {
        assertFalse(IhbarAccount.matches(null))
        assertFalse(IhbarAccount.matches(""))
        assertFalse(IhbarAccount.matches("   "))
        assertFalse(IhbarAccount.matches("@"))
    }

    /** Karşılaştırma "içeriyor" değil "eşit": benzeyen ad aynı hesap değil. */
    @Test
    fun `benzeyen ad o hesap değil`() {
        assertFalse(IhbarAccount.matches("trafik_cezasi_2"))
        assertFalse(IhbarAccount.matches("xtrafik_cezasi"))
    }

    /**
     * Ayara yazılan değer [SettingsStore] tarafından zaten kırpılıp '@'i
     * siliniyor; kapı o biçimi de tanımak zorunda, yoksa düğme doğru hesapta
     * kaybolurdu.
     */
    @Test
    fun `SettingsStore'un sakladığı biçim tanınıyor`() {
        assertTrue(IhbarAccount.matches(SettingsStore.DEFAULT_IG_ACCOUNT))
    }
}

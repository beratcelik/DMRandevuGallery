package com.dmrandevu.gallery.data

/**
 * İhbar köprüsünün tanıdığı TEK Instagram hesabı.
 *
 * Bu bir tercih değil, kurulumun kendisi: ihbar sunucusunun `IgAccount`
 * tablosunda tek satır var ve sunucunun `IG_ACCOUNT_ID` ayarı bu kimliği
 * gösteriyor. Galeri ise iki hesap geziyor (bkz. [GalleryRepository]
 * içindeki KNOWN_ACCOUNTS); trafykamerasi'ndeki bir videoya ihbar düğmesi
 * göstermek, ihbar sisteminde karşılığı HİÇ olmayan bir kaydı onaylatmaya
 * çalışmak olurdu — sahip her seferinde "bulunamadı" cevabı alırdı ve
 * sebebini düğmeden anlayamazdı.
 *
 * NEDEN TEK YERDE: ad ve kimlik üç ayrı yerde okunuyor (düğmenin çizimi,
 * toplu durum sorgusu, onay dokunuşu). Üçüne ayrı dizgi yazılsaydı hesap
 * kimliği bir gün değiştiğinde biri geride kalır ve düğme yanlış hesapta
 * çizilmeye devam ederdi.
 */
object IhbarAccount {

    /** Instagram kullanıcı adı; [SettingsStore.DEFAULT_IG_ACCOUNT] ile aynı hesap. */
    const val HANDLE = "trafik_cezasi"

    /** Aynı hesabın sayısal Instagram kimliği; ihbar sunucusunun IG_ACCOUNT_ID'si. */
    const val IG_ID = "17841468848724091"

    /**
     * Gezilen hesap ihbar hesabı mı?
     *
     * NEDEN HEM AD HEM KİMLİK KABUL EDİLİYOR: hesap iki biçimde elimize
     * geliyor. Giriş ekranındaki alana ad yazılabildiği gibi doğrudan sayısal
     * kimlik de yazılabiliyor ve [GalleryRepository.resolveAccount] sayısal
     * girdiyi olduğu gibi geçiriyor; galeri sayfaları ise her zaman çözülmüş
     * sayısal kimlikle çekiliyor. Yalnızca birini karşılaştırmak, iki
     * biçimden birinde düğmenin sessizce kaybolması demekti.
     *
     * NEDEN NORMALLEŞTİRİLİYOR: alan kullanıcının yazdığı metni saklıyor.
     * "@Trafik_Cezasi" yazan biri doğru hesapta duruyor; baştaki @ ya da
     * büyük harf yüzünden düğmeyi kaybetmemeli. [SettingsStore] yazarken
     * zaten @ siliyor, ama karar burada verildiği için temizlik de burada
     * duruyor — oradaki davranış yarın değişirse düğme sessizce kaybolurdu.
     */
    fun matches(account: String?): Boolean {
        // `lowercase()` PARAMETRESİZ ÇAĞRILIYOR ve öyle kalmalı: bu biçim
        // değişmez yerelin kurallarını uyguluyor. Yerel alan `lowercase(Locale)`
        // aşırı yüklemesi ise Türkçe yerelinde "I" harfini noktasız "ı"ya
        // düşürür — "TRAFIK_CEZASI" yazan sahip "trafık_cezası" ile
        // karşılaştırılır, eşleşme kaybolur ve düğme DOĞRU hesapta sessizce yok
        // olur. Kimsenin hata mesajı görmediği, yalnızca "ihbar bozulmuş"
        // denen arıza türü tam olarak bu. (Eski `toLowerCase()` aynı tuzağı
        // taşıyor ama derleyici onu zaten reddediyor; asıl açık kapı
        // yereli parametre olarak vermek.) iOS tarafındaki eşi de bu yüzden
        // `lowercased()` değil `caseInsensitiveCompare(_:)` kullanıyor.
        val normalized = account?.trim()?.removePrefix("@")?.lowercase() ?: return false
        return normalized == HANDLE || normalized == IG_ID
    }
}

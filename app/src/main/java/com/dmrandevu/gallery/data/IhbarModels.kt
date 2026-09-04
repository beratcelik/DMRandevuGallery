package com.dmrandevu.gallery.data

import kotlinx.serialization.Serializable

/**
 * Trafik İhbar galeri köprüsünün tel üzerindeki sözleşmesi.
 *
 * NEDEN AYRI BİR DOSYA (Models.kt'ye eklenmedi): bu alanlar BAŞKA bir sunucuya
 * ait — https://ihbar.lega.digital, DMRandevu'dan tamamen ayrı bir sistem ve
 * ayrı bir kimlik doğrulama. İkisini tek dosyada tutmak, hangi alanın hangi
 * sunucuyla değiştiğini bir sonraki okuyucuya sildirirdi.
 *
 * Alan adları sunucudaki karşılıklarıyla BİREBİR aynı; hiçbiri burada
 * "düzeltilmedi". Yeniden adlandırma, iki tarafın sözleşmesini tek taraflı
 * değiştirmek olurdu ve derleyici bunu yakalayamaz.
 *
 * Bilinmeyen alanlar yok sayılıyor (Json { ignoreUnknownKeys = true }): sunucu
 * yanıta yeni bir alan eklediğinde uygulama çökmemeli, sadece görmezden
 * gelmeli.
 */

// ─── istek ───────────────────────────────────────────────────────────────────

/**
 * Bir videoyu ihbar sisteminde tanımlayan anahtar kümesi.
 *
 * Hiçbir alan zorunlu değil ve HEPSİ birden gönderiliyor: sunucu en güvenilir
 * anahtardan başlayıp sırayla deniyor (mid → clientId+sentAt → adres → sıra) ve
 * hangisiyle eşleştiğini [IhbarStatusItem.matchedBy] alanında geri söylüyor.
 * Elimizdekinin tamamını göndermek, ileride galeri `mid` taşımaya başladığında
 * sunucu tarafında hiçbir değişiklik gerektirmemesi demek.
 *
 * Varsayılanı null olan alanlar kodlanmıyor (kotlinx.serialization
 * encodeDefaults = false), yani gövdede yalnızca gerçekten bilinenler yer alır.
 */
@Serializable
data class IhbarItem(
    /** Gönderenin Instagram kimliği; sunucuda Reporter.igSenderId'nin ta kendisi. */
    val clientId: String? = null,
    /** Videonun kendi gönderim zamanı, ISO 8601. Sunucu epoch biçimlerini de kabul ediyor. */
    val sentAt: String? = null,
    /** HAM CDN adresi — vekil (media-proxy) adresi değil; gerekçesi [ihbarItemFor]'da. */
    val url: String? = null,
    /** Meta mesaj kimliği. Galeri bugün taşımıyor; sunucu yarın taşırsa diye duruyor. */
    val mid: String? = null,
    /**
     * urls[] dizisindeki sıra. Sunucu bunu YALNIZCA durum sorgusunda kabul
     * ediyor; onay ucunda bilerek yok sayıyor. Onay gövdesinde de gitmesi bu
     * yüzden zararsız ve ikinci bir istek tipi tutmaktan ucuz.
     */
    val index: Int? = null
)

@Serializable
data class IhbarStatusRequest(val items: List<IhbarItem>)

// ─── yanıt ───────────────────────────────────────────────────────────────────

// Sunucu aşağıdaki iki yanıtta gösterdiğimizden fazlasını taşıyor: il, ilçe,
// plaka, güven puanı, videodaki konuşmanın metni. Burada YALNIZCA düğmenin
// okuduğu alanlar duruyor.
//
// İki sebebi var. Taşınan her fazla alan, bir sonraki okuyucuya "bu nerede
// gösteriliyor?" diye arattıracak ölü ağırlık; ve konuşma metni MUHABİRİN KENDİ
// SESİ — adını, adresini, telefonunu içerebiliyor ve bir galeri uygulamasının
// belleğinde durmasının hiçbir gerekçesi yok.

/**
 * Toplu durum yanıtı. Öğeler İSTEK SIRASIYLA dönüyor, yani istemci kendi
 * listesiyle konumdan eşleştirebilir.
 */
@Serializable
data class IhbarStatusResponse(
    val items: List<IhbarStatusItem> = emptyList()
)

/**
 * Tek bir videonun durumu.
 *
 * Düğme yalnızca [state] alanına bakıyor, ham veritabanı durumuna değil:
 * sunucu bu kümeyi bilerek dar tutuyor, tam da mobil istemci sekiz durumluk bir
 * enum'u yeniden yorumlamak zorunda kalmasın diye.
 */
@Serializable
data class IhbarStatusItem(
    val state: String = "",
    val stateLabel: String = "",
    /** YEŞİL DÜĞMENİN dayandığı tek alan. */
    val humanVerified: Boolean = false,
    val matchedBy: String? = null,
    val violationCode: String? = null,
    /** Eksik alanların onayı gerçekten ENGELLEYEN alt kümesi (il / tarih / medya). */
    val blockingFields: List<String> = emptyList()
)

/** Onay dokunuşunun sonucu. Bulunamayan video da 200 döner; hata değil, cevaptır. */
@Serializable
data class IhbarApproveResponse(
    val state: String = "",
    val stateLabel: String = "",
    val matchedBy: String? = null,
    val humanVerified: Boolean = false,
    val blockingFields: List<String> = emptyList(),
    /** Onayı durduran somut sebepler ("Açıklama çok kısa"); [message]'dan daha kesin. */
    val problems: List<String> = emptyList(),
    /**
     * Sunucunun kendi Türkçe cümlesi ("İhbar onaylandı ve 3 memura iletiliyor.").
     * Kaç memura gittiği de, hangi alanın eksik olduğu da bunun içinde — o
     * yüzden o alanlar ayrıca taşınmıyor.
     */
    val message: String = ""
)

/** Sunucunun her hata gövdesi bu biçimde: { ok:false, code, error }. */
@Serializable
data class IhbarErrorResponse(
    val code: String = "",
    /** Zaten Türkçe ve kullanıcıya gösterilmek üzere yazılmış. */
    val error: String = ""
)

// ─── durum sözlüğü ───────────────────────────────────────────────────────────

/**
 * Sunucunun döndürdüğü durum adları. Bilinçli olarak dar bir küme ve düğmenin
 * görünümüyle birebir; ham veritabanı durumu (PENDING_REVIEW vb.) burada
 * yorumlanmıyor ki sunucuya yeni bir durum eklendiği gün uygulama sessizce
 * yanlış renk göstermesin.
 */
private const val STATE_BILINMIYOR = "bilinmiyor"
private const val STATE_BEKLEMEDE = "beklemede"
private const val STATE_INCELENIYOR = "inceleniyor"
private const val STATE_EKSIK_BILGI = "eksik_bilgi"
private const val STATE_ONAYLANDI = "onaylandi"
private const val STATE_KAPANDI = "kapandi"
private const val STATE_REDDEDILDI = "reddedildi"
private const val STATE_ONAYLANAMAZ = "onaylanamaz"

// ─── düğmenin durumu ─────────────────────────────────────────────────────────

/** İhlal düğmesinin görünümü. Her değer ayrı bir renk ve ayrı bir metin demek. */
enum class IhbarPhase {
    /** Sunucuya henüz sorulmadı. Nötr ve basılabilir. */
    UNKNOWN,

    /** İstek yolda. Çember döner, düğme basılmaz. */
    BUSY,

    /** Kayıt var, insan teyidi yok. Basılmayı bekliyor. */
    MARKABLE,

    /** İnsan teyidi kaydedildi. YEŞİL. */
    VERIFIED,

    /** İhbar onaylandı ve memurlara gidiyor. YEŞİL. */
    APPROVED,

    /** Teyit alındı ama kritik alan eksik. AMBER. */
    NEEDS_INFO,

    /** Teyit alındı ama kayıt bu hâliyle gönderilemiyor (kapalı/reddedilmiş). AMBER. */
    BLOCKED,

    /** Video bizde yok ya da çıkarımı sürüyor. SOLUK. */
    PENDING,

    /** İstek başarısız. KIRMIZI, tekrar denenebilir. */
    ERROR,

    /** Cihaz belirteci girilmemiş. SOLUK, ayara yönlendirir. */
    NO_TOKEN
}

/**
 * Bir videonun düğmesinin bildiği her şey.
 *
 * ViewModel'de tutuluyor, sayfa değil: dikey kaydırma sayfayı yeniden
 * oluşturuyor ve durum sayfada dursaydı her geri dönüşte kaybolurdu.
 */
data class IhbarMark(
    val phase: IhbarPhase,
    /** Sunucunun kendi cümlesi ya da durum etiketi; düğmenin alt satırı. */
    val detail: String? = null,
    /** Onayı engelleyen alanlar; alt satır bunlardan yazılıyor. */
    val blockingFields: List<String> = emptyList(),
    /**
     * Sunucunun hangi anahtarla eşleştirdiği (mid / sentAt / url / index).
     * Ekranda gösterilmiyor; ileride "yanlış videoya yeşil düğme" şüphesi
     * doğduğunda bakılacak ilk alan bu ve o gün elde olması gerekiyor.
     */
    val matchedBy: String? = null
)

private fun phaseOf(state: String, humanVerified: Boolean): IhbarPhase = when (state) {
    STATE_ONAYLANDI -> IhbarPhase.APPROVED
    STATE_BILINMIYOR, STATE_BEKLEMEDE -> IhbarPhase.PENDING
    STATE_ONAYLANAMAZ -> IhbarPhase.BLOCKED
    // Aşağıdaki üçü teyitten ÖNCE de görülebiliyor: kayıt eksik bilgili ya da
    // kapanmış olabilir ama sahip henüz videoya bakmamıştır. Teyit yokken düğme
    // nötr kalıyor, çünkü asıl istenen şey hâlâ onun dokunuşu; amber/soluk
    // göstermek "yapacak bir şey yok" demek olurdu.
    STATE_EKSIK_BILGI -> if (humanVerified) IhbarPhase.NEEDS_INFO else IhbarPhase.MARKABLE
    STATE_KAPANDI, STATE_REDDEDILDI -> if (humanVerified) IhbarPhase.BLOCKED else IhbarPhase.MARKABLE
    STATE_INCELENIYOR -> if (humanVerified) IhbarPhase.VERIFIED else IhbarPhase.MARKABLE
    // Sunucu tanımadığımız bir durum eklediyse: teyit varsa yeşil, yoksa nötr.
    // Bilinmeyen bir ada bakıp "hata" demek, çalışan bir sistemi bozuk gösterirdi.
    else -> if (humanVerified) IhbarPhase.VERIFIED else IhbarPhase.MARKABLE
}

fun IhbarStatusItem.toMark(): IhbarMark {
    val phase = phaseOf(state, humanVerified)
    val label = stateLabel.ifBlank { null }
    return IhbarMark(
        phase = phase,
        // Onaylanmış kayıtta ihbar kodu, kalanında sunucunun durum etiketi: kodu
        // görmek, aynı ihbarı yönetici konsolunda aramayı mümkün kılıyor.
        detail = if (phase == IhbarPhase.APPROVED) violationCode ?: label else label,
        blockingFields = blockingFields,
        matchedBy = matchedBy
    )
}

fun IhbarApproveResponse.toMark(): IhbarMark {
    val phase = phaseOf(state, humanVerified)
    // Onay ucu her dalda tam bir cümle yazıyor; kendi metnimizi üretmek,
    // sunucunun bildiğini tahmin etmek olurdu. Tek istisna engellenmiş kayıt:
    // orada somut sebep ("Açıklama çok kısa") genel cümleden daha çok işe yarar.
    val detail = if (phase == IhbarPhase.BLOCKED) {
        problems.firstOrNull() ?: message.ifBlank { null }
    } else {
        message.ifBlank { stateLabel.ifBlank { null } }
    }
    return IhbarMark(
        phase = phase,
        detail = detail,
        blockingFields = blockingFields,
        matchedBy = matchedBy
    )
}

/**
 * Sunucunun alan adlarını sahibin kullandığı kelimelere çevirir.
 *
 * Sunucu 'il', 'tarih', 'medya' gibi ŞEMA adları gönderiyor; ekranda okunan
 * şey "konum eksik" olmalı. Tanımadığımız bir ad ham hâliyle gösteriliyor:
 * sunucuya yeni bir alan eklendiğinde eksiği gizlemektense çirkin göstermek
 * yeğdir.
 */
fun ihbarFieldLabel(field: String): String = when (field) {
    "il" -> "konum"
    "ilce" -> "ilçe"
    "tarih" -> "tarih"
    "plaka" -> "plaka"
    "medya" -> "video"
    else -> field
}

/**
 * Bir videoyu ihbar sunucusunda aratacak anahtarlar.
 *
 * NEDEN [Conversation.sentAt] DEĞİL DE mediaTs DOĞRUDAN: sentAt(), damga yoksa
 * konuşmanın lastMessageDate'ine düşüyor — o damga BU videonun değil,
 * konuşmanın son mesajının zamanı. Sunucu ±2 dakikalık pencerede en yakın
 * medyalı mesajı seçtiği için, uydurulmuş bir damga sessizce YANLIŞ VİDEOYU
 * eşleştirir: durumda yanlış renkli düğme, onayda yanlış ihbarın emniyet
 * birimine gitmesi. Damga yoksa eşleştirmeyi adrese ve sıraya bırakmak,
 * yanlış bir zamana güvenmekten iyidir.
 *
 * NEDEN VEKİL (proxy) ADRESİ DEĞİL DE HAM ADRES: sunucu CDN adresinin
 * değişmeyen parçasını (dosya adı / asset_id) arıyor. Vekil adresinin yolunda
 * ("/admin/media-proxy") böyle bir parça yok, sorgu anahtarı da beyaz listede
 * değil — yani vekil adresiyle hiçbir zaman eşleşme olmaz.
 */
fun ihbarItemFor(conversation: Conversation, mediaIndex: Int): IhbarItem = IhbarItem(
    clientId = conversation.clientId,
    sentAt = conversation.mediaTs.getOrNull(mediaIndex),
    url = conversation.urls.getOrNull(mediaIndex),
    index = mediaIndex
)

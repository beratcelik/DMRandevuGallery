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
    /**
     * OLUMSUZ DÜĞMENİN dayandığı tek alan: sahip bu görüntüye "ihlal değil"
     * dedi mi.
     *
     * NEDEN [state] TEK BAŞINA YETMİYOR: sahip "ihlal değil" dediğinde sunucu
     * kaydı TRAFIK_DISI sebebiyle reddediyor ve durum "reddedildi" oluyor — ama
     * aynı durum, yöneticinin konsoldan yaptığı reddin de sonucu. Ayrı bir alan
     * olmasaydı düğme, sahibin hiç dokunmadığı bir kaydı "işaretlenmiş"
     * gösterir; sahip de ikinci kez bakmaya gerek duymadığı için ihlal olan bir
     * görüntü sessizce elenirdi.
     *
     * ADI SUNUCUYLA BİREBİR AYNI OLMAK ZORUNDA. Bu alan bir süre `humanRejected`
     * yazılmıştı; sunucu ise her zaman `notViolation` gönderiyor. Eksik anahtar
     * derleyiciye görünmez — kotlinx.serialization sessizce varsayılana, yani
     * false'a düşer. Sonuç, tam da bu düğmenin önlemek için yazıldığı arıza
     * oluyordu: sahibin elediği video bir sonraki açılışta yeniden "el
     * değmemiş" görünüyor, ikinci kez bakılmıyor ve memura gidiyordu.
     *
     * Varsayılanın false olması yine de doğru: alanı tanımayan ESKİ bir
     * sunucuya karşı düğme nötr kalır ve yanlış bir şey iddia etmez.
     */
    val notViolation: Boolean = false,
    val matchedBy: String? = null,
    val violationCode: String? = null,
    /** Eksik alanların onayı gerçekten ENGELLEYEN alt kümesi (il / tarih / medya). */
    val blockingFields: List<String> = emptyList(),
    /**
     * Bu ihbara bağlı TOPLAM video sayısı.
     *
     * NEDEN GEREKLİ: onay KAYDIN TAMAMINI memura gönderiyor. Sağa atış tek bir
     * videoya karar vermek gibi görünürken üç videoyu birden ihbar edebiliyor
     * ve kart bunu söylemeden önce bilmek zorunda. Aynı sayı, elemede
     * gösterilecek geri çekme penceresinin hangi cümleyi kuracağını da
     * belirliyor.
     *
     * Varsayılan 0 = "sunucu söylemedi" (eski sunucu); kart o hâlde sayıdan
     * hiç söz etmiyor, uydurmuyor.
     */
    val mediaCount: Int = 0,
    /** Bu ihbara bağlı olup sahibin ZATEN elediği video sayısı. */
    val eliminatedCount: Int = 0
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
    /** Onaylanan ihbarın kaç video taşıdığı — memura giden delil sayısı. */
    val mediaCount: Int = 0,
    /** Onaydan önce kayıttan çıkarılan, daha önce elenmiş kardeş video sayısı. */
    val detachedSiblings: Int = 0,
    /**
     * Sunucunun kendi Türkçe cümlesi ("İhbar onaylandı ve 3 memura iletiliyor.").
     * Kaç memura gittiği de, hangi alanın eksik olduğu da bunun içinde — o
     * yüzden o alanlar ayrıca taşınmıyor.
     */
    val message: String = ""
)

/**
 * "İhlal değil" dokunuşunun sonucu.
 *
 * NEDEN [IhbarApproveResponse] YENİDEN KULLANILMADI: iki ucun taşıdığı alanlar
 * farklı. Onay ucu neyin ONAYI ENGELLEDİĞİNİ anlatmak zorunda (blockingFields,
 * problems); olumsuz dokunuşta engel diye bir şey yok — kayıt her hâlükârda
 * memura gitmekten çıkıyor. Tek tipe sıkıştırmak, her iki tarafta da hangi
 * alanın hangi uçta dolduğunu okuyucuya arattırırdı.
 *
 * [message] sunucunun kendi Türkçe cümlesi: kaydın reddedildiğini mi yoksa
 * memurdan GERİ ÇEKİLDİĞİNİ mi söylediği oradan okunuyor. İkisi sahip için
 * farklı şeyler ve ayrımı sunucu bizden iyi biliyor.
 */
@Serializable
data class IhbarRejectResponse(
    val state: String = "",
    val stateLabel: String = "",
    val matchedBy: String? = null,
    /** Dokunuşun gerçekten yazıldığının tek kanıtı; düğmenin rengi buna bakıyor. */
    val notViolation: Boolean = false,
    /**
     * Karar YALNIZCA bu videoya uygulandı mı — kayıt kalan delille ayakta mı?
     *
     * NEDEN AYRI BİR ALAN (mesaja bakılmıyor): ekran, kullanıcıya söyleyeceği
     * cümleyi buna göre seçiyor. Serbest metinden çıkarım yapmak, sunucu
     * cümlesini düzelten ilk günün sessizce yanlış şey söylemesi demekti.
     */
    val detached: Boolean = false,
    /** Ayırmadan sonra kayıtta kalan video sayısı. */
    val remainingMedia: Int = 0,
    /** Karar anında kayda bağlı toplam video. */
    val mediaCount: Int = 0,
    val message: String = ""
)

// ─── toplu eleme ─────────────────────────────────────────────────────────────

/**
 * Bir konuşmanın karar verilmemiş videolarını TEK istekte eleme isteği.
 *
 * NEDEN TOPLU BİR UÇ VAR (tekil uç dururken): bir muhabir on beş video
 * gönderebiliyor ve hiçbiri ihlal olmayabilir. Tek tek elemek on beş istek
 * demek; iki yazma ucu TEK bir oran sınırı kovasını paylaşıyor (dakikada
 * yirmi) ve yirmi birinci dokunuş reddediliyor — konuşma yarım elenmiş kalıyor
 * ve ekranda bitmiş görünüyor. Toplu uç tek çağrı = tek hak sayılıyor.
 *
 * TOPLU ONAY YOK VE OLMAYACAK: eleme geri alınabilir bir karar (aynı videoyu
 * sağa atmak fikri değiştiriyor); onay ise delili bir kamu birimine çıkarıyor.
 */
@Serializable
data class IhbarBulkRejectRequest(val items: List<IhbarItem>)

/** Toplu elemede TEK bir videonun sonucu; öğeler İSTEK SIRASIYLA dönüyor. */
@Serializable
data class IhbarBulkRejectItem(
    val key: String = "",
    /** Sahibin kararı deftere YAZILDI mı? */
    val applied: Boolean = false,
    /**
     * Kaydın kendisine dokunulmadıysa sebebi: 'onayli' | 'zayif_anahtar' |
     * 'bulunamadi' | 'zaten'. Tanınmayan bir değer, atlanmış saymaya devam
     * ediyor — sunucu yeni bir sebep eklediğinde uygulama çökmemeli.
     */
    val skipped: String? = null,
    val state: String = "",
    val stateLabel: String = "",
    val notViolation: Boolean = false,
    val detached: Boolean = false,
    val remainingMedia: Int = 0,
    val violationCode: String? = null,
    val message: String = ""
)

@Serializable
data class IhbarBulkRejectResponse(
    val items: List<IhbarBulkRejectItem> = emptyList(),
    /** Kararı deftere yazılan video sayısı. */
    val applied: Int = 0,
    /** Dokunulmayan video sayısı (onaylı, tanınmayan, zayıf anahtarlı). */
    val skipped: Int = 0
)

/** Toplu eleme sonucunun işarete çevrilmiş hâli. */
fun IhbarBulkRejectItem.toMark(): IhbarMark = IhbarMark(
    phase = when {
        // ATLANAN ÖĞE İŞARETİ DEĞİŞTİRMEMELİ: onaylı kayıt onaylı kalıyor,
        // tanınmayan video bilinmiyor kalıyor. Toplu bir hareketin, dokunmadığı
        // bir videonun rengini değiştirmesi en sessiz yalan olurdu.
        skipped == "onayli" -> IhbarPhase.APPROVED
        skipped == "bulunamadi" || skipped == "zayif_anahtar" -> IhbarPhase.PENDING
        notViolation || state == STATE_IHLAL_DEGIL -> IhbarPhase.NOT_VIOLATION
        else -> IhbarPhase.PENDING
    },
    detail = message.ifBlank { stateLabel.ifBlank { null } },
    mediaCount = if (detached) remainingMedia else 0
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

/**
 * Sahibin "ihlal değil" dediği kayıt.
 *
 * [IhbarStatusItem.notViolation] alanının YANINDA duruyor, onun yerine değil:
 * sunucu kararı İKİ ayrı kanaldan söylüyor (medyaya çıpalanmış bayrak; ve o
 * kararın kaydın durumunun önüne geçmesiyle bu durum adı) ve düğme ikisine
 * birden bakıyor. Tek tanığa güvenmek, adlardan biri değiştiği gün düğmeyi
 * sessizce nötre düşürürdü — sahip elediği videoyu yeniden elenmemiş görürdü.
 */
private const val STATE_IHLAL_DEGIL = "ihlal_degil"

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

    /**
     * Teyit alındı ama ihbar kaydı HENÜZ AÇILMADI. YEŞİL.
     *
     * NEDEN AYRI BİR EVRE (PENDING'den): ikisi de "kayıt yok" hâlini anlatıyor
     * ama biri sahibin dokunuşunu taşıyor, diğeri taşımıyor. Tek evreye
     * sıkıştırıldığında sağa atılan video soluk "kayıt hazır değil" görünüyor
     * ve sahip aynı videoyu tekrar tekrar atıyordu — oysa dokunuş kaydedilmişti
     * ve kayıt açılır açılmaz onaya gidecek (sunucuda galeri/finalize.ts).
     *
     * NEDEN VERIFIED DEĞİL: alt satırdaki cümle farklı olmak zorunda. "İhlal
     * olarak işaretlendi" demek, ihbarın çoktan yola çıktığını ima ederdi;
     * doğru cümle "kayıt açılınca ihbar edilecek".
     */
    VERIFIED_PENDING,

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
    NO_TOKEN,

    /** "İhlal değil" isteği yolda. Olumsuz düğmede çember döner. */
    REJECTING,

    /** Sahip "bu ihlal değil" dedi; kayıt memura gitmiyor. ARDUVAZ. */
    NOT_VIOLATION,

    /**
     * "İhlal değil" isteği başarısız.
     *
     * NEDEN [ERROR] İLE AYNI EVRE DEĞİL: iki düğme var ve hata hangisine
     * düşerse sahip onu tekrar deniyor. Olumsuz dokunuşun hatası olumlu düğmeye
     * kırmızı olarak yansısaydı, sahip düzeltmek için OLUMLU düğmeye basar ve
     * eleyeceği videoyu emniyet birimine gönderirdi. Aynı arızanın iki düğmede
     * iki ayrı yeri olmak zorunda.
     */
    REJECT_ERROR
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
    /**
     * Bu ihbarın kaç video taşıdığı (0 = sunucu söylemedi).
     *
     * Ekranda iki yerde okunuyor: kartın "3 videoluk ihbar" satırı ve geri
     * çekme penceresinin metni. Sağa atmak tek videoya karar vermek gibi
     * görünürken kaydın tamamını gönderiyor; bunu söylemenin tek yolu bu sayı.
     */
    val mediaCount: Int = 0,
    /** Bu ihbarda sahibin ZATEN elediği video sayısı. */
    val eliminatedCount: Int = 0,
    /** Onayı engelleyen alanlar; alt satır bunlardan yazılıyor. */
    val blockingFields: List<String> = emptyList(),
    /**
     * Sunucunun hangi anahtarla eşleştirdiği (mid / sentAt / url / index).
     * Ekranda gösterilmiyor; ileride "yanlış videoya yeşil düğme" şüphesi
     * doğduğunda bakılacak ilk alan bu ve o gün elde olması gerekiyor.
     */
    val matchedBy: String? = null
)

/**
 * Çipin söyleyecek bir şeyi var mı?
 *
 * ─── NEDEN "KARAR VERİLMEDİ" YAZMIYORUZ ────────────────────────────────────
 * Sahip videoyu YENİ açtı; karar vermediği zaten kesin. Ekranda duran her
 * etiket okunmayı hak etmek zorunda ve bu etiket hiçbir şey öğretmiyordu —
 * yalnızca kararın verileceği yerde, tam da videoya bakılması gereken anda yer
 * kaplıyordu. Görünen her çip artık bir HABER taşıyor: ihbar edildi, elendi,
 * bilgi eksik, istek düştü, belirteç yok.
 *
 * NÖTR EVREDE ÇİP OLMAMASI BİLGİ KAYBI DEĞİL: kararın nasıl verileceğini
 * kaydırmanın kendisi öğretiyor (kart parmakla hareket ediyor ve damga
 * beliriyor), ve boş bir ekran "bu videoya henüz dokunulmadı" demenin en kısa
 * yolu.
 */
val IhbarMark.saysSomething: Boolean
    get() = phase != IhbarPhase.UNKNOWN && phase != IhbarPhase.MARKABLE

private fun phaseOf(state: String, humanVerified: Boolean, notViolation: Boolean): IhbarPhase {
    // OLUMSUZ DOKUNUŞ HER ŞEYİN ÖNÜNDE. Sahip "ihlal değil" dedikten sonra
    // kaydın veritabanındaki durumu ne olursa olsun (reddedildi, kapandı, hatta
    // geri çekilmeden önce onaylanmıştı) düğmede görülmesi gereken tek şey
    // sahibin kendi kararı. Durum adına öncelik verseydik, geri çekilmiş bir
    // ihbar "kapandı" diye görünür ve sahip dokunuşunun işlenmediğini sanırdı.
    if (notViolation || state == STATE_IHLAL_DEGIL) return IhbarPhase.NOT_VIOLATION
    return whenState(state, humanVerified)
}

private fun whenState(state: String, humanVerified: Boolean): IhbarPhase = when (state) {
    STATE_ONAYLANDI -> IhbarPhase.APPROVED
    // BEKLEMEDE + TEYİT = dokunuş kaydedildi, kayıt yolda. Bu ayrım olmadan
    // sahibin sağa attığı video soluk "kayıt hazır değil" görünüyor ve aynı
    // videoya tekrar tekrar basılıyordu.
    STATE_BEKLEMEDE -> if (humanVerified) IhbarPhase.VERIFIED_PENDING else IhbarPhase.PENDING
    STATE_BILINMIYOR -> IhbarPhase.PENDING
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
    val phase = phaseOf(state, humanVerified, notViolation)
    val label = stateLabel.ifBlank { null }
    return IhbarMark(
        phase = phase,
        mediaCount = mediaCount,
        eliminatedCount = eliminatedCount,
        // Onaylanmış kayıtta ihbar kodu, kalanında sunucunun durum etiketi: kodu
        // görmek, aynı ihbarı yönetici konsolunda aramayı mümkün kılıyor.
        detail = if (phase == IhbarPhase.APPROVED) violationCode ?: label else label,
        blockingFields = blockingFields,
        matchedBy = matchedBy
    )
}

fun IhbarApproveResponse.toMark(): IhbarMark {
    // Onay ucu olumsuz bayrağı taşımıyor ve taşımamalı: bu uca basmak, varsa
    // önceki "ihlal değil" kararını ZATEN geçersiz kılar. false geçmek burada
    // eksik bilgi değil, dokunuşun anlamının kendisi.
    val phase = phaseOf(state, humanVerified, notViolation = false)
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
        mediaCount = mediaCount,
        blockingFields = blockingFields,
        matchedBy = matchedBy
    )
}

/**
 * "İhlal değil" yanıtının düğmeye çevrilmesi.
 *
 * Bayrak yoksa [IhbarPhase.PENDING]: 200 dönmüş ama dokunuş yazılmamışsa tek
 * sebep, videonun ihbar sisteminde henüz karşılığının olmaması. Bunu hata
 * saymak yanlış olurdu (yapılacak bir şey yok, kayıt birazdan oluşacak), ama
 * arduvaza boyamak daha da yanlış: sahip elemiş sanır, oysa kimse bir şey
 * kaydetmemiştir.
 */
fun IhbarRejectResponse.toMark(): IhbarMark = IhbarMark(
    phase = if (notViolation || state == STATE_IHLAL_DEGIL) {
        IhbarPhase.NOT_VIOLATION
    } else {
        IhbarPhase.PENDING
    },
    detail = message.ifBlank { stateLabel.ifBlank { null } },
    // AYIRMADAN SONRAKİ SAYI: kayıt ayakta kaldıysa kaç video kaldığı, kapandıysa
    // kaç video taşıdığı. Kartın "kayıt 2 videoyla devam ediyor" diyebilmesi
    // için tek kaynak bu.
    mediaCount = if (detached) remainingMedia else mediaCount,
    matchedBy = matchedBy
)

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

package com.dmrandevu.gallery.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Trafik İhbar sunucusuyla konuşan üç uç: bir ekran dolusu videonun durumu, tek
 * bir videonun onayı ve tek bir videonun elenmesi.
 *
 * NEDEN [GalleryRepository]'ye EKLENMEDİ: o sınıfın tamamı DMRandevu'nun
 * çerezli oturumuna dayanıyor (`requireBody` 401'i oturum kaybı sayıp
 * kullanıcıyı giriş ekranına atıyor). İhbar sunucusunda kimlik bir çerez değil,
 * cihaz belirteci; oradan gelen 401 "oturumun bitti" demek değil, "belirtecin
 * yanlış" demek. İkisini aynı sınıfta toplamak, ihbar belirteci hatalı olduğu
 * an sahibi galeri oturumundan atardı.
 *
 * AYNI OkHttp YIĞINI kullanılıyor (yeni bir HTTP kütüphanesi yok): istemci
 * ServiceLocator'daki tekil istemciden türetiliyor, yani bağlantı havuzunu ve
 * User-Agent başlığını paylaşıyor.
 */
class IhbarRepository(baseClient: OkHttpClient, private val settings: SettingsStore) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Çerezsiz istemci.
     *
     * DMRandevu yönetici oturum çerezi başka bir sunucuya HİÇBİR koşulda
     * gitmemeli. Çerez kavanozu zaten alan adına bakıp filtreliyor, ama bu
     * sınır bir filtrenin doğru yazılmış olmasına bırakılamayacak kadar önemli:
     * burada çerez diye bir şey yok, dolayısıyla sızdıracak bir şey de yok.
     */
    private val client: OkHttpClient =
        baseClient.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()

    private val base: String get() = settings.ihbarBaseUrl.trimEnd('/')

    /** Belirteç girilmiş mi. Düğme, girilmemişken kullanıcıya bunu söylüyor. */
    val hasToken: Boolean get() = settings.ihbarToken.isNotBlank()

    /**
     * Bir ekran dolusu videonun durumu — TEK istekte.
     *
     * Video başına istek atmak, her kaydırmada N istek demek olurdu: mobil
     * bağlantıda gözle görülür gecikme ve sunucudaki oran sınırının hiçbir iş
     * yapmadan dolması. Sunucu tek istekte en fazla [MAX_ITEMS] öğe kabul
     * ediyor; bölme işi çağırana ait, çünkü kaç gidiş geliş yapıldığı burada
     * gizlenmemeli.
     */
    suspend fun status(items: List<IhbarItem>): List<IhbarStatusItem> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        val payload = json.encodeToString(IhbarStatusRequest.serializer(), IhbarStatusRequest(items))
        val body = post("/api/galeri/durum", payload)
        json.decodeFromString<IhbarStatusResponse>(body).items
    }

    /**
     * Sahibin dokunuşu: "bu görüntü gerçekten bir ihlal gösteriyor".
     *
     * Tekil, çünkü bu bir dokunuş. Toplu onay, yanlışlıkla elli kaydın bir
     * kerede emniyet birimlerine gitmesi demek olurdu.
     *
     * Aynı videoya ikinci kez basmak zararsız: sunucu insan teyidini koşullu
     * yazıyor (ilk dokunuş kazanır) ve dağıtım satırları tekil.
     */
    suspend fun approve(item: IhbarItem): IhbarApproveResponse = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(IhbarItem.serializer(), item)
        val body = post("/api/galeri/onayla", payload)
        json.decodeFromString<IhbarApproveResponse>(body)
    }

    /**
     * Sahibin olumsuz dokunuşu: "bu görüntü bir trafik ihlali DEĞİL".
     *
     * NEDEN AYRI BİR UÇ (onayla'ya bir bayrak eklenmedi): iki dokunuşun sunucu
     * tarafındaki sonuçları hiç benzemiyor. Olumlu dokunuş kaydı memura doğru
     * iterken, olumsuz dokunuş onu ya reddediyor ya da MEMURDAN GERİ ÇEKİYOR —
     * ikisi ayrı yetki, ayrı denetim kaydı ve ayrı oran sınırı. Tek uçta
     * toplansaydı, gövdedeki tek bir bayrağın kaybolması (eski istemci, bozuk
     * JSON, yanlış varsayılan) sessizce ihbar ONAYLARDI. Ayrı yol, o hatanın
     * mümkün olmadığı yol.
     *
     * Tekil, olumlu uçla aynı sebeple: toplu eleme, bir kaydırma kazasında
     * onlarca ihbarın toptan geri çekilmesi demek olurdu.
     *
     * Aynı videoya ikinci kez basmak zararsız; sunucu son dokunuşu yazıyor ve
     * zaten elenmiş kayıt için hiçbir şey değişmiyor.
     */
    suspend fun reject(item: IhbarItem): IhbarRejectResponse =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(IhbarItem.serializer(), item)
            val body = post("/api/galeri/ihlal-degil", payload)
            json.decodeFromString<IhbarRejectResponse>(body)
        }

    private fun post(path: String, payload: String): String {
        val token = settings.ihbarToken
        // Ağa hiç çıkmadan duruyoruz: belirteçsiz istek sunucuda yalnızca
        // geçersiz-belirteç oran sınırını doldurur ve kullanıcıya "sunucu
        // hatası" gibi görünürdü. Eksik olan şey ayarda, sunucuda değil.
        if (token.isBlank()) throw IhbarTokenMissingException()

        val request = Request.Builder()
            .url("$base$path")
            .header("Authorization", "Bearer $token")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response -> return response.requireBody() }
    }

    /**
     * Başarılı yanıtın gövdesi.
     *
     * Hata gövdesi de JSON ve içindeki `error` alanı ZATEN TÜRKÇE, kullanıcıya
     * gösterilmek üzere yazılmış. Kendi metnimizi uydurmak yerine onu
     * taşıyoruz; sunucu bir reddi neden verdiğini bizden iyi biliyor.
     */
    private fun Response.requireBody(): String {
        val text = body?.string().orEmpty()
        if (isSuccessful) return text
        val parsed = runCatching {
            json.decodeFromString<IhbarErrorResponse>(text)
        }.getOrNull()
        throw IhbarException(
            code = parsed?.code ?: "HTTP_$code",
            message = parsed?.error?.ifBlank { null } ?: "İhbar sunucusu isteği reddetti (HTTP $code)"
        )
    }

    companion object {
        /** Sunucunun tek istekte kabul ettiği azami video sayısı. */
        const val MAX_ITEMS = 50

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Sunucunun gövdede anlattığı ret. [message] doğrudan ekrana yazılabilir —
 * Türkçe ve kullanıcı için yazılmış.
 */
class IhbarException(val code: String, message: String) : Exception(message)

/** Cihaz belirteci girilmemiş; ağa çıkmadan önce durduran hata. */
class IhbarTokenMissingException : Exception("İhbar cihaz belirteci girilmemiş")

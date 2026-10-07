package com.dmrandevu.gallery.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Trafik İhbar köprüsü: bir ekran dolusu videonun durumu, tek bir videonun onayı ve elenmesi.
 *
 * NEDEN ARTIK DMRandevu'YA KONUŞUYOR (İhbar sunucusuna değil): İhbar'ın cihaz belirteci artık
 * telefonda tutulmuyor. Giriş yapan kişi DMRandevu'da zaten belli; istekler onun oturumuyla
 * `/admin/ihbar/…` uçlarına gidiyor ve sunucu, yalnızca trafik_cezasi hesabına yetkili kişiler
 * için, kendi belirteciyle İhbar'a iletiyor. Kaybolan bir telefon hiçbir şey sızdırmıyor.
 *
 * İKİ HATA, İKİ ANLAM: DMRandevu'dan gelen 401 "oturumun bitti" demek ve [UnauthorizedException]
 * olarak çıkıyor; görünüm modeli kullanıcıyı giriş ekranına götürüyor. İhbar sunucusunun KENDİ
 * ret sebebi (sunucunun belirteci, oran sınırı, eksik alan) ise sunucuda 502/4xx'e çevriliyor ve
 * [IhbarException] oluyor: oturumu bitirmiyor, düğmede Türkçe sebebiyle görünüyor.
 *
 * AYNI OkHttp YIĞINI ve ÇEREZ KAVANOZU kullanılıyor (galeriyle aynı oturum).
 */
class IhbarRepository(private val client: OkHttpClient, private val settings: SettingsStore) {

    private val json = Json { ignoreUnknownKeys = true }

    private val base: String get() = settings.baseUrl.trimEnd('/')

    /**
     * Bir ekran dolusu videonun durumu — TEK istekte.
     *
     * Video başına istek, her kaydırmada N istek demek olurdu: mobil bağlantıda gözle görülür
     * gecikme ve oran sınırının hiçbir iş yapmadan dolması. Sunucu tek istekte en fazla
     * [MAX_ITEMS] öğe kabul ediyor; bölme işi çağırana ait, çünkü kaç gidiş geliş yapıldığı
     * burada gizlenmemeli.
     */
    suspend fun status(items: List<IhbarItem>): List<IhbarStatusItem> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyList()
        val payload = json.encodeToString(IhbarStatusRequest.serializer(), IhbarStatusRequest(items))
        val body = post("/admin/ihbar/durum", payload)
        json.decodeFromString<IhbarStatusResponse>(body).items
    }

    /**
     * Sahibin dokunuşu: "bu görüntü gerçekten bir ihlal gösteriyor".
     *
     * Tekil, çünkü bu bir dokunuş. Toplu onay, yanlışlıkla elli kaydın bir kerede emniyet
     * birimlerine gitmesi demek olurdu. Aynı videoya ikinci kez basmak zararsız: sunucu insan
     * teyidini koşullu yazıyor (ilk dokunuş kazanıyor) ve dağıtım satırları tekil.
     */
    suspend fun approve(item: IhbarItem): IhbarApproveResponse = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(IhbarItem.serializer(), item)
        val body = post("/admin/ihbar/onayla", payload)
        json.decodeFromString<IhbarApproveResponse>(body)
    }

    /**
     * Sahibin olumsuz dokunuşu: "bu görüntü bir trafik ihlali DEĞİL".
     *
     * NEDEN AYRI BİR UÇ (onayla'ya bir bayrak eklenmedi): iki dokunuşun sunucudaki sonuçları hiç
     * benzemiyor. Olumlu dokunuş kaydı memura doğru iterken, olumsuz dokunuş onu ya reddediyor ya
     * da MEMURDAN GERİ ÇEKİYOR; ikisi ayrı yetki, ayrı denetim kaydı ve ayrı oran sınırı. Tek uçta
     * toplansaydı, gövdedeki tek bir bayrağın kaybolması sessizce ihbar ONAYLARDI. Tekil, olumlu
     * uçla aynı sebeple: toplu eleme, bir kaydırma kazasında onlarca ihbarın toptan geri
     * çekilmesi demek olurdu.
     */
    suspend fun reject(item: IhbarItem): IhbarRejectResponse =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(IhbarItem.serializer(), item)
            val body = post("/admin/ihbar/ihlal-degil", payload)
            json.decodeFromString<IhbarRejectResponse>(body)
        }

    /**
     * Bir konuşmanın karar verilmemiş videolarını TEK istekte eler.
     *
     * Tek tek elemek N istek demek; iki yazma ucu TEK bir oran sınırı kovasını paylaşıyor
     * (dakikada yirmi) ve yirmi birinci dokunuş reddediliyor: konuşma yarım elenmiş kalıyor ve
     * tükenen bütçe o dakikadaki GERÇEK ihbarı da engelliyor. Toplu uçta bir istek tek hak
     * sayılıyor. ONAYLANMIŞ KAYITLARI SUNUCU ATLIYOR: toplu bir hareket memura asla geri çekme
     * bildirimi göndermiyor. TOPLU ONAY YOK: onay delili bir kamu birimine çıkarıyor.
     */
    suspend fun rejectBulk(items: List<IhbarItem>): IhbarBulkRejectResponse =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext IhbarBulkRejectResponse()
            val payload = json.encodeToString(
                IhbarBulkRejectRequest.serializer(), IhbarBulkRejectRequest(items),
            )
            val body = post("/admin/ihbar/ihlal-degil-toplu", payload)
            json.decodeFromString<IhbarBulkRejectResponse>(body)
        }

    private fun post(path: String, payload: String): String {
        val request = Request.Builder()
            .url("$base$path")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response -> return response.requireBody() }
    }

    /**
     * Başarılı yanıtın gövdesi.
     *
     * Hata gövdesi de JSON ve içindeki `error` alanı ZATEN TÜRKÇE, kullanıcıya gösterilmek üzere
     * yazılmış. Kendi metnimizi uydurmak yerine onu taşıyoruz.
     */
    private fun Response.requireBody(): String {
        if (code == 401) throw UnauthorizedException()
        val text = body?.string().orEmpty()
        if (isSuccessful) return text
        val parsed = runCatching {
            json.decodeFromString<IhbarErrorResponse>(text)
        }.getOrNull()
        throw IhbarException(
            code = parsed?.code ?: "HTTP_$code",
            message = parsed?.error?.ifBlank { null } ?: "İhbar isteği reddedildi (HTTP $code)"
        )
    }

    companion object {
        /** Sunucunun tek istekte kabul ettiği azami video sayısı. */
        const val MAX_ITEMS = 50

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Sunucunun gövdede anlattığı ret. [message] doğrudan ekrana yazılabilir — Türkçe ve kullanıcı
 * için yazılmış.
 */
class IhbarException(val code: String, message: String) : Exception(message)

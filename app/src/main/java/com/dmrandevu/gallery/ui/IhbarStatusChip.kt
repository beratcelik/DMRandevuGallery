package com.dmrandevu.gallery.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dmrandevu.gallery.R
import com.dmrandevu.gallery.data.IhbarMark
import com.dmrandevu.gallery.data.IhbarPhase
import com.dmrandevu.gallery.data.ihbarFieldLabel

/** Yeşil: bu görüntünün bir ihlal olduğunu bir insan teyit etti. */
private val IhbarGreen = Color(0xFF1B873F)

/** Amber: teyit alındı ama kayıt olduğu gibi emniyete gidemiyor. */
private val IhbarAmber = Color(0xFF9A6100)

/** Kırmızı: istek başarısız — tekrar denenebilir. Kaydın kendisiyle ilgisi yok. */
private val IhbarRed = Color(0xFFA02B2B)

/** Basılmayı bekleyen düğme; diğer denetimlerle aynı siyah cam. */
private val IhbarNeutral = Color.Black.copy(alpha = 0.55f)

/** Yapacak bir şey yokken: kayıt henüz yok ya da belirteç girilmemiş. */
private val IhbarMuted = Color.Black.copy(alpha = 0.4f)

/**
 * Arduvaz: sahip "bu ihlal değil" dedi.
 *
 * NEDEN YEŞİLİN KARŞITI KIRMIZI DEĞİL: kırmızı bu ekranda ZATEN "istek
 * başarısız, tekrar dene" demek ([IhbarRed]). Elenmiş bir videoyu da kırmızıya
 * boyasaydık, sahip başarıyla elediği kayda tekrar tekrar dokunurdu. Soğuk ve
 * mat bir gri, yeşille bir bakışta ayrılıyor ve "burada yapılacak bir şey yok"
 * diyor — söylemesi gereken de tam olarak bu.
 *
 * OPAK, camsı değil: basılmayı bekleyen düğme yarı saydam siyah ve videonun
 * üstünde her karede farklı görünüyor. Kararın kaydedildiğini anlatan renk,
 * altındaki görüntüden bağımsız olmak zorunda.
 */
private val IhbarSlate = Color(0xFF4A5058)

/**
 * O an ekrandaki videonun ihbar durumu — SALT OKUNUR bir çip.
 *
 * ─── NEDEN DÜĞME DEĞİL ─────────────────────────────────────────────────────
 * Karar artık kaydırmayla veriliyor: sağa at = ihbar et, sola at = ihlal değil.
 * Aynı kararı aynı ekranda ikinci bir yüzeyden de vermek, iki ayrı kas
 * hafızası ve iki ayrı yanlış basış yolu üretirdi. Çip yalnızca sonucu
 * gösteriyor: bu videoya ne dedim, sunucu ne yaptı.
 *
 * İKİ İSTİSNA DOKUNUŞ KABUL EDİYOR ve ikisi de karar değil:
 *  - Belirteç yokken dokunmak, belirteci yapıştırma penceresini açıyor.
 *    Sessizce çalışmayan bir ekran yerine eksik olanı sormak tek makul
 *    davranış; kaydırmanın neden hiçbir şey yapmadığının cevabı burada.
 *  - Uzun basış her hâlde aynı pencereyi açıyor: uygulama açık oturumla
 *    başladığında giriş ekranı hiç görünmüyor ve oradaki alan aylarca
 *    erişilemez kalabiliyor.
 *
 * ─── NEDEN ÜST ŞERİTTE ─────────────────────────────────────────────────────
 * Ekranın altı zaten katmanlı (eylem şeridi, oynatma çubuğu, küfür işaretleme)
 * ve kaydırma kartın TAMAMINI hareket ettiriyor. Karar yüzeyiyle aynı yerde
 * duran bir durum göstergesi, her kaydırmada parmağın altında kalırdı.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IhbarStatusChip(
    mark: IhbarMark,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busy = mark.phase == IhbarPhase.BUSY || mark.phase == IhbarPhase.REJECTING
    val detail = ihbarDetail(mark)

    Row(
        modifier = modifier
            // Uzun bir sunucu cümlesi ekranın yarısını kaplamasın.
            .widthIn(max = 260.dp)
            .background(backgroundOf(mark.phase), RoundedCornerShape(20.dp))
            .combinedClickable(
                // HER ZAMAN AÇIK (enabled = false DEĞİL): kapalı bir clickable
                // dokunuşu YUTMUYOR, altındaki video yüzeyine geçiriyor — yani
                // çipe dokunmak videoyu duraklatırdı.
                onClick = onTap,
                onLongClick = onLongPress
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Icon(
                imageVector = iconOf(mark.phase),
                // Metin hemen yanında; simgeyi ayrıca okutmak ekran okuyucuda
                // aynı şeyi iki kez söyletirdi.
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Column {
            Text(
                text = stringResource(titleOf(mark.phase)),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge
            )
            if (detail != null) {
                Text(
                    text = detail,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Kaydırırken kartın üstünde beliren damga.
 *
 * NEDEN VAR: kaydırma kararın KENDİSİ ve geri alma penceresi üç saniye. Sahip
 * parmağını kaldırmadan önce hangi kararı verdiğini görmek zorunda; rengin tek
 * başına söylediği şey "bir şey oluyor", hangi şey olduğu değil.
 *
 * Renkler kararların ZATEN kullandığı paletten: yeşil teyit, arduvaz eleme.
 * Yeni bir renk üretmek, aynı kararın iki farklı yerde iki farklı renkle
 * anlatılması demekti.
 */
@Composable
fun SwipeStamp(
    decision: SwipeDecision,
    alpha: Float,
    modifier: Modifier = Modifier
) {
    val report = decision == SwipeDecision.REPORT
    Row(
        modifier = modifier
            .background(
                (if (report) IhbarGreen else IhbarSlate).copy(alpha = alpha.coerceIn(0f, 1f)),
                RoundedCornerShape(12.dp)
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = if (report) Icons.Filled.Flag else Icons.Filled.Block,
            contentDescription = null,
            tint = Color.White.copy(alpha = alpha.coerceIn(0f, 1f)),
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = stringResource(if (report) R.string.swipe_report else R.string.swipe_dismiss),
            color = Color.White.copy(alpha = alpha.coerceIn(0f, 1f)),
            style = MaterialTheme.typography.titleMedium
        )
    }
}

/**
 * Bekleyen kararın geri alma çipi.
 *
 * NEDEN GEREKLİ: karar verildiği anda kart uçuyor ve akış bir sonraki videoya
 * geçiyor — yani sahip kararını verdiği videoyu ARTIK GÖRMÜYOR. Geri alma
 * yolunun, kararın kendisiyle aynı ekranda ve aynı anda durması gerekiyor.
 * (İkinci yol da var: o sayfaya geri kaydırmak kararı iptal ediyor.)
 */
@Composable
fun UndoChip(
    decision: SwipeOutcome,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.82f), RoundedCornerShape(20.dp))
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = if (decision == SwipeOutcome.REPORT) {
                Icons.Filled.Flag
            } else {
                Icons.Filled.Block
            },
            contentDescription = null,
            tint = if (decision == SwipeOutcome.REPORT) IhbarGreen else Color.White,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = stringResource(
                if (decision == SwipeOutcome.REPORT) {
                    R.string.swipe_reported
                } else {
                    R.string.swipe_dismissed
                }
            ),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge
        )
        TextButton(onClick = onUndo) {
            Text(stringResource(R.string.swipe_undo), color = Color.White)
        }
    }
}

private fun backgroundOf(phase: IhbarPhase): Color = when (phase) {
    IhbarPhase.VERIFIED, IhbarPhase.APPROVED, IhbarPhase.VERIFIED_PENDING -> IhbarGreen
    IhbarPhase.NEEDS_INFO, IhbarPhase.BLOCKED -> IhbarAmber
    IhbarPhase.ERROR, IhbarPhase.REJECT_ERROR -> IhbarRed
    IhbarPhase.PENDING, IhbarPhase.NO_TOKEN -> IhbarMuted
    // Sahibin elediği video: soğuk ve mat: "burada yapılacak bir şey yok".
    IhbarPhase.NOT_VIOLATION -> IhbarSlate
    IhbarPhase.UNKNOWN, IhbarPhase.MARKABLE, IhbarPhase.BUSY,
    IhbarPhase.REJECTING -> IhbarNeutral
}

private fun iconOf(phase: IhbarPhase): ImageVector = when (phase) {
    IhbarPhase.VERIFIED, IhbarPhase.APPROVED, IhbarPhase.VERIFIED_PENDING ->
        Icons.Filled.CheckCircle
    IhbarPhase.NEEDS_INFO, IhbarPhase.BLOCKED -> Icons.Filled.Warning
    IhbarPhase.ERROR, IhbarPhase.REJECT_ERROR -> Icons.Filled.ErrorOutline
    IhbarPhase.PENDING -> Icons.Filled.HourglassEmpty
    IhbarPhase.NO_TOKEN -> Icons.Filled.Lock
    IhbarPhase.NOT_VIOLATION -> Icons.Filled.Block
    // BUSY/REJECTING bu dala hiç gelmiyor (yerinde çember dönüyor), ama when
    // tam olmak zorunda.
    IhbarPhase.UNKNOWN, IhbarPhase.MARKABLE, IhbarPhase.BUSY,
    IhbarPhase.REJECTING -> Icons.Filled.Flag
}

@StringRes
private fun titleOf(phase: IhbarPhase): Int = when (phase) {
    // NÖTR EVRELER BU DALA HİÇ GELMİYOR: karar verilmemiş videoda çip
    // çizilmiyor (bkz. IhbarMark.saysSomething) — sahip videoyu yeni açtı,
    // karar vermediği zaten kesin ve o etiket hiçbir şey öğretmiyordu. Dal yine
    // de tam, çünkü eksik bir when derlenmiyor ve buraya bir gün gelinirse
    // söylenecek doğru şey "bekliyor"dur, "işaretle" değil.
    IhbarPhase.UNKNOWN, IhbarPhase.MARKABLE -> R.string.ihbar_pending
    IhbarPhase.BUSY -> R.string.ihbar_marking
    IhbarPhase.REJECTING -> R.string.ihbar_not_violation_sending
    IhbarPhase.VERIFIED -> R.string.ihbar_marked
    IhbarPhase.VERIFIED_PENDING -> R.string.ihbar_verified_pending
    IhbarPhase.APPROVED -> R.string.ihbar_approved
    IhbarPhase.NEEDS_INFO -> R.string.ihbar_needs_info
    IhbarPhase.BLOCKED -> R.string.ihbar_blocked
    IhbarPhase.PENDING -> R.string.ihbar_pending
    IhbarPhase.ERROR -> R.string.ihbar_error
    IhbarPhase.REJECT_ERROR -> R.string.ihbar_not_violation_error
    IhbarPhase.NO_TOKEN -> R.string.ihbar_no_token
    IhbarPhase.NOT_VIOLATION -> R.string.ihbar_not_violation_marked
}

/**
 * Çipin alt satırı.
 *
 * Eksik alanlar her şeyin önünde: "konum eksik" sahibin YAPABİLECEĞİ tek şeyi
 * söylüyor, sunucunun aynı şeyi anlatan uzun cümlesi ise ikinci satıra
 * sığmıyor. Kalan durumlarda sunucunun kendi Türkçe metni geçiyor — reddin
 * sebebini bizden iyi biliyor.
 */
@Composable
private fun ihbarDetail(mark: IhbarMark): String? = when {
    mark.phase == IhbarPhase.NO_TOKEN -> stringResource(R.string.ihbar_no_token_detail)
    // Kayıt yolda: cümle "işaretlendi" DEMEMELİ, ihbar henüz yola çıkmadı.
    mark.phase == IhbarPhase.VERIFIED_PENDING ->
        stringResource(R.string.ihbar_verified_pending_detail)
    mark.blockingFields.isNotEmpty() -> stringResource(
        R.string.ihbar_missing_fields,
        mark.blockingFields.joinToString(", ") { ihbarFieldLabel(it) }
    )
    // Sunucudan gelmeyen hata: sebebi ağ tarafında, metni burada.
    (mark.phase == IhbarPhase.ERROR || mark.phase == IhbarPhase.REJECT_ERROR) &&
        mark.detail == null -> stringResource(R.string.ihbar_network_error)
    mark.detail != null -> mark.detail
    // Kaç videoluk bir ihbar olduğu: sağa atmak tek videoya karar vermek gibi
    // görünürken kaydın tamamını gönderiyor.
    mark.mediaCount > 1 -> stringResource(R.string.ihbar_media_count, mark.mediaCount)
    else -> null
}

/**
 * Onaylanmış bir ihbara "ihlal değil" demeden önceki tek soru.
 *
 * NEDEN VAR — YANLIŞ KARARIN BEDELİ BU DALDA UYGULAMANIN DIŞINA ÇIKIYOR:
 * [IhbarPhase.APPROVED] evresinde sola atmak bir kaydı kapatmakla kalmıyor,
 * memura GİTMİŞ ihbarı geri çekiyor ve e-postası çıkmış her memura "bu kayıt
 * üzerinde işlem yapmayınız" bildirimi gönderiyor.
 *
 * VE O DALDA GERİ ALMA YOLU DAR: kayıt reddediliyor ve durum makinesinde
 * reddedilmiş kaydın tek çıkışı yeniden inceleme. Sahibin KENDİ kararıyla
 * reddedilen kayıt telefondan geri açılabiliyor (sunucuda reopenIfOwnerRejected)
 * ama memura çıkmış düzeltme çoktan gitmiş oluyor.
 *
 * NEDEN KAYDIRMA BU DALDA YETMİYOR: kaydırma hızlı olsun diye var ve geri alma
 * penceresi üç saniye. Memurun gelen kutusuna düşen bir düzeltme, üç saniyelik
 * bir pencereye bırakılamaz. Kart yerine dönüyor ve soru soruluyor.
 *
 * ─── İKİ AYRI SORU, ÇÜNKÜ İKİ AYRI SONUÇ ───────────────────────────────────
 * Kayıt birden çok video taşıyorsa sola atış kaydın tamamını geri ÇEKMİYOR,
 * yalnızca bu videoyu kayıttan ÇIKARIYOR (sunucuda detachMediaFromViolation) ve
 * ihbar kalan delille memurda kalmaya devam ediyor. Tek bir metin kullansaydık
 * pencere, gerçekleşmeyecek bir şeyi ("ihbar geri çekilir") vaat ederdi.
 */
@Composable
fun IhbarRetractDialog(
    mark: IhbarMark,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    // Kayıtta bu videodan başka, HENÜZ ELENMEMİŞ video var mı? Varsa sunucu
    // ayırma yapacak. Sayı gelmediyse (eski sunucu) geri çekme metni kalıyor:
    // daha ağır olanı vaat etmek, hafif olanı vaat edip ağırını yapmaktan iyi.
    val remainsAfterwards = mark.mediaCount - mark.eliminatedCount - 1
    val detachOnly = remainsAfterwards > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (detachOnly) R.string.ihbar_retract_detach_title
                    else R.string.ihbar_retract_title
                )
            )
        },
        text = {
            Text(
                text = if (detachOnly) {
                    stringResource(R.string.ihbar_retract_detach_explain, mark.mediaCount)
                } else {
                    stringResource(R.string.ihbar_retract_explain)
                },
                style = MaterialTheme.typography.bodySmall
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(
                        if (detachOnly) R.string.ihbar_retract_detach_confirm
                        else R.string.ihbar_retract_confirm
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Cihaz belirtecini yapıştırma penceresi.
 *
 * NEDEN GİRİŞ EKRANINDAKİ ALAN TEK BAŞINA YETMİYOR: sunucu oturumu yedi gün
 * yaşıyor ve uygulama açık oturumla açıldığında giriş ekranı HİÇ görünmüyor.
 * Belirteci yalnızca oraya koysaydık, sahip düğmenin neden çalışmadığını
 * gösteren bir yazıya bakıp ona ulaşamayacağı bir alana yönlendirilirdi.
 */
@Composable
fun IhbarTokenDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ihbar_token_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.ihbar_token_explain),
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.ihbar_token_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(token.trim()) },
                // Boş kaydetmek, çalışan bir belirteci silmek demek olurdu.
                enabled = token.isNotBlank()
            ) {
                Text(stringResource(R.string.ihbar_token_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

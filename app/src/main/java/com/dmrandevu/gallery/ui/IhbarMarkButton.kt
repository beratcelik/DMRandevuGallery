package com.dmrandevu.gallery.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 * Sahibin dokunuşu.
 *
 * Sahip zaten her videoyu izliyor (hangisini Reel yapacağına karar vermek
 * için). Bu düğme, o izleme sırasında sistemin kendi başına asla üretemeyeceği
 * bilgiyi topluyor: GÖRÜNTÜ GERÇEKTEN BİR İHLAL GÖSTERİYOR MU. Model ihlalin
 * NE olduğunu çıkarıyor; buradaki dokunuş İHLAL OLDUĞUNU teyit ediyor.
 *
 * NEDEN ALT SATIRDAKİ EYLEM ŞERİDİNE BİR SİMGE OLARAK EKLENMEDİ: o şerit dört
 * düğmeyle zaten dar ve hepsi videoyu DIŞA AKTARMAKLA ilgili. Bu ise videonun
 * kendisi hakkında bir karar ve tek görünür sonucu rengi — simge boyutunda bir
 * yüzeyde ne renk okunur ne de "eksik bilgi" yazılabilirdi.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IhbarMarkButton(
    mark: IhbarMark,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busy = mark.phase == IhbarPhase.BUSY
    val detail = ihbarDetail(mark)

    Row(
        modifier = modifier
            // Uzun bir sunucu cümlesi ekranın yarısını kaplamasın; iki satıra
            // sığmayan kısmı kırpılıyor.
            .widthIn(max = 260.dp)
            .background(backgroundOf(mark.phase), RoundedCornerShape(22.dp))
            .combinedClickable(
                // NEDEN HER ZAMAN AÇIK (enabled = false DEĞİL): kapalı bir
                // clickable dokunuşu YUTMUYOR, altındaki video yüzeyine
                // geçiriyor — yani yeşil düğmeye basmak videoyu duraklatırdı.
                // Reddetme kararı onClick'in içinde; iOS tarafındaki ActionButton
                // aynı tuzağa aynı çözümü koyuyor.
                onClick = { if (!busy) onClick() },
                // Belirteci değiştirmenin tek yolu: uygulama açık oturumla
                // başladığında giriş ekranı hiç görünmüyor, yani oradaki alan
                // aylarca erişilemez kalabilir.
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Icon(
                imageVector = iconOf(mark.phase),
                // Metni hemen yanında; simgeyi ayrıca okutmak ekran okuyucuda
                // aynı şeyi iki kez söyletirdi.
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
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

private fun backgroundOf(phase: IhbarPhase): Color = when (phase) {
    IhbarPhase.VERIFIED, IhbarPhase.APPROVED -> IhbarGreen
    IhbarPhase.NEEDS_INFO, IhbarPhase.BLOCKED -> IhbarAmber
    IhbarPhase.ERROR -> IhbarRed
    IhbarPhase.PENDING, IhbarPhase.NO_TOKEN -> IhbarMuted
    // OLUMSUZ EVRELER BU DÜĞMEDE SOLUK. Elenmiş bir videoda olumlu düğme hâlâ
    // basılabilir (geri almanın tek yolu o) ama davet etmemeli; olumsuz istek
    // yoldayken ya da başarısız olduğunda ise buraya basmak, düzeltmeye
    // çalışırken ihbarı emniyete göndermek demek olurdu.
    IhbarPhase.NOT_VIOLATION, IhbarPhase.REJECTING,
    IhbarPhase.REJECT_ERROR -> IhbarMuted
    IhbarPhase.UNKNOWN, IhbarPhase.MARKABLE, IhbarPhase.BUSY -> IhbarNeutral
}

private fun iconOf(phase: IhbarPhase): ImageVector = when (phase) {
    IhbarPhase.VERIFIED, IhbarPhase.APPROVED -> Icons.Filled.CheckCircle
    IhbarPhase.NEEDS_INFO, IhbarPhase.BLOCKED -> Icons.Filled.Warning
    IhbarPhase.ERROR -> Icons.Filled.ErrorOutline
    IhbarPhase.PENDING -> Icons.Filled.HourglassEmpty
    IhbarPhase.NO_TOKEN -> Icons.Filled.Lock
    // BUSY bu dala hiç gelmiyor (yerinde çember dönüyor), ama when tam olmalı.
    // Olumsuz evrelerde de bayrak duruyor: bu düğmenin anlamı değişmiyor, tek
    // değişen basılmaya davet etmemesi — onu da rengi söylüyor.
    IhbarPhase.UNKNOWN, IhbarPhase.MARKABLE, IhbarPhase.BUSY,
    IhbarPhase.NOT_VIOLATION, IhbarPhase.REJECTING,
    IhbarPhase.REJECT_ERROR -> Icons.Filled.Flag
}

@StringRes
private fun titleOf(phase: IhbarPhase): Int = when (phase) {
    // OLUMSUZ EVRELERDE DE "İhlal olarak işaretle" YAZIYOR ve yazmalı: yanlış
    // düğmeye basan sahibin geri alma yolu bu düğme ve üstünde ne yapacağını
    // söyleyen bir cümle olmazsa, elediği videoyu geri getiremez. Elendiğini
    // anlatan cümle komşusunda; iki düğmenin ikisi birden aynı şeyi söylerse
    // hangisine basılacağı belirsizleşir.
    IhbarPhase.UNKNOWN, IhbarPhase.MARKABLE, IhbarPhase.NOT_VIOLATION,
    IhbarPhase.REJECTING, IhbarPhase.REJECT_ERROR -> R.string.ihbar_mark
    IhbarPhase.BUSY -> R.string.ihbar_marking
    IhbarPhase.VERIFIED -> R.string.ihbar_marked
    IhbarPhase.APPROVED -> R.string.ihbar_approved
    IhbarPhase.NEEDS_INFO -> R.string.ihbar_needs_info
    IhbarPhase.BLOCKED -> R.string.ihbar_blocked
    IhbarPhase.PENDING -> R.string.ihbar_pending
    IhbarPhase.ERROR -> R.string.ihbar_error
    IhbarPhase.NO_TOKEN -> R.string.ihbar_no_token
}

/**
 * Düğmenin alt satırı.
 *
 * Eksik alanlar her şeyin önünde: "konum eksik" sahibin YAPABİLECEĞİ tek şeyi
 * söylüyor, sunucunun aynı şeyi anlatan uzun cümlesi ise ikinci satıra
 * sığmıyor. Kalan durumlarda sunucunun kendi Türkçe metni geçiyor — reddin
 * sebebini bizden iyi biliyor.
 */
@Composable
private fun ihbarDetail(mark: IhbarMark): String? = when {
    mark.phase == IhbarPhase.NO_TOKEN -> stringResource(R.string.ihbar_no_token_detail)
    // Olumsuz dokunuşun cümlesi ("İhbar geri çekildi") KOMŞU düğmede duruyor.
    // İki düğmede birden yazsaydı, sahip sunucunun iki ayrı şey söylediğini
    // sanır ve hangisinin gerçekleştiğini kestiremezdi.
    mark.phase == IhbarPhase.NOT_VIOLATION ||
        mark.phase == IhbarPhase.REJECTING ||
        mark.phase == IhbarPhase.REJECT_ERROR -> null
    mark.blockingFields.isNotEmpty() -> stringResource(
        R.string.ihbar_missing_fields,
        mark.blockingFields.joinToString(", ") { ihbarFieldLabel(it) }
    )
    // Sunucudan gelmeyen hata: sebebi ağ tarafında, metni burada.
    mark.phase == IhbarPhase.ERROR && mark.detail == null ->
        stringResource(R.string.ihbar_network_error)
    else -> mark.detail
}

/**
 * Sahibin olumsuz dokunuşu: "bu görüntü bir trafik ihlali DEĞİL".
 *
 * NEDEN AÇIK BİR DÜĞME (kaydırıp geçmek değil): galeri aynı zamanda Reel
 * seçmek için kullanılıyor. Bakılmayan ya da beğenilmeyen videolar da
 * kaydırılıp geçiliyor; "geçtim" ile "izledim ve eledim" aynı hareket. Sessiz
 * sinyal okusaydık, sahibin telefonu cebinde açık kaldığı bir öğleden sonra
 * gerçek ihlaller sessizce elenirdi. Dokunmamak hiçbir şey ifade etmiyor:
 * yapay zekâ kararını vermeye devam ediyor ve kayıt memura gidiyor.
 *
 * NEDEN AYRI BİR DÜĞME (aynı düğmede ikinci bir dokunuş değil): tek düğmeyi
 * yeşilden griye çeviren bir "geçiş" davranışı, sahibin ekranda gördüğü rengi
 * hatırlamasını gerektirirdi. İki ayrı yüzeyde iki ayrı karar var ve her ikisi
 * de tek dokunuşla ötekine dönüyor.
 *
 * NEDEN [IhbarMarkButton] KADAR BÜYÜK DEĞİL: bu ikincil karar. Küçük gövde ve
 * farklı simge, iki düğmenin yanlışlıkla birbiri sanılmasını zorlaştırıyor;
 * aralarındaki boşluğu çağıran koyuyor (bkz. ConversationPage).
 */
@Composable
fun IhbarNotViolationButton(
    mark: IhbarMark,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val busy = mark.phase == IhbarPhase.REJECTING
    val detail = notViolationDetail(mark)

    Row(
        modifier = modifier
            .widthIn(max = 240.dp)
            .background(notViolationBackgroundOf(mark.phase), RoundedCornerShape(20.dp))
            // Sade `clickable`, `combinedClickable` değil: belirteç penceresini
            // açan uzun basış komşu düğmede duruyor ve iki yüzeyde birden aynı
            // gizli hareketi tanıtmak, keşfedilmesini kolaylaştırmıyor.
            // KAPATILMIYOR (enabled = false): kapalı bir clickable dokunuşu
            // yutmaz, altındaki video yüzeyine geçirir — istek yoldayken buraya
            // basmak videoyu duraklatırdı.
            .clickable { if (!busy) onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
        } else {
            Icon(
                imageVector = Icons.Filled.Block,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
        }
        Column {
            Text(
                text = stringResource(notViolationTitleOf(mark.phase)),
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
 * Olumsuz düğmenin rengi.
 *
 * NEDEN `else` DALI VAR (komşusundaki when'ler tam sayarken): bu düğme yalnızca
 * KENDİ evrelerini tanıyor. Olumlu tarafta yarın yeni bir evre belirdiğinde
 * ("iletiliyor", "kapatıldı" …) burada yapılacak doğru şey nötr kalmaktır —
 * derleyiciyi her yeni evrede bu dosyaya sürüklemek, olumsuz düğmeye alakasız
 * renkler yazdırmaktan başka bir şeye yaramazdı.
 */
private fun notViolationBackgroundOf(phase: IhbarPhase): Color = when (phase) {
    IhbarPhase.NOT_VIOLATION -> IhbarSlate
    IhbarPhase.REJECT_ERROR -> IhbarRed
    // Olumlu istek yoldayken ya da belirteç hiç yokken bu düğme de davet
    // etmemeli: iki dokunuşun yarışması, sunucuda hangisinin sonuncu olduğunun
    // ağ gecikmesine kalması demek.
    IhbarPhase.BUSY, IhbarPhase.NO_TOKEN -> IhbarMuted
    else -> IhbarNeutral
}

@StringRes
private fun notViolationTitleOf(phase: IhbarPhase): Int = when (phase) {
    IhbarPhase.REJECTING -> R.string.ihbar_not_violation_sending
    IhbarPhase.NOT_VIOLATION -> R.string.ihbar_not_violation_marked
    IhbarPhase.REJECT_ERROR -> R.string.ihbar_not_violation_error
    else -> R.string.ihbar_not_violation
}

/**
 * Olumsuz düğmenin alt satırı.
 *
 * Yalnızca olumsuz evrelerde yazı çıkıyor. Kayıt eksik bilgili ya da onaylı
 * olduğunda sunucunun söyledikleri OLUMLU düğmeye ait; ikisinde birden
 * göstermek, aynı cümlenin iki karara birden ait olduğu izlenimini verirdi.
 */
@Composable
private fun notViolationDetail(mark: IhbarMark): String? = when (mark.phase) {
    IhbarPhase.NOT_VIOLATION -> mark.detail
    // Sebep sunucudan gelmediyse ağ tarafında: metni burada duruyor.
    IhbarPhase.REJECT_ERROR -> mark.detail ?: stringResource(R.string.ihbar_network_error)
    else -> null
}

/**
 * Onaylanmış bir ihbarı elemeden önceki tek soru.
 *
 * NEDEN VAR — YANLIŞ BASIŞIN BEDELİ BU DALDA UYGULAMANIN DIŞINA ÇIKIYOR:
 * [IhbarPhase.APPROVED] evresinde "İhlal değil" demek kaydı yalnızca kapatmıyor,
 * memura GİTMİŞ bir ihbarı geri çekiyor ve e-postası çıkmış her memura "bu kayıt
 * üzerinde işlem yapmayınız" bildirimi gönderiyor (sunucuda markNotViolation →
 * retractViolation).
 *
 * VE O DALDA GERİ ALMA YOLU YOK: kayıt REJECTED'a düşüyor, durum makinesinde
 * REJECTED'ın tek çıkışı PENDING_REVIEW (yönetici konsolundan "yeniden
 * incele"). Yani sahip yeşil düğmeye tekrar bassa bile sunucu "bu kayıt bu
 * hâliyle onaylanamaz" diyor — telefondan dönüş yok, kuruma çıkan düzeltme ise
 * çoktan gitmiş oluyor. Diğer bütün evrelerde eleme sıradan ve gerçekten geri
 * alınabilir bir karar (son dokunuş kazanır), o yüzden orada pencere AÇILMIYOR:
 * her videoda iki dokunuş istemek, düğmeyi sahibin kullanmayacağı kadar
 * yorucu yapardı.
 */
@Composable
fun IhbarRetractDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ihbar_retract_title)) },
        text = {
            Text(
                text = stringResource(R.string.ihbar_retract_explain),
                style = MaterialTheme.typography.bodySmall
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.ihbar_retract_confirm))
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

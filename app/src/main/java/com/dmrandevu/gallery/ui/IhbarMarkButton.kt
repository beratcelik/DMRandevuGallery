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
    IhbarPhase.UNKNOWN, IhbarPhase.MARKABLE, IhbarPhase.BUSY -> IhbarNeutral
}

private fun iconOf(phase: IhbarPhase): ImageVector = when (phase) {
    IhbarPhase.VERIFIED, IhbarPhase.APPROVED -> Icons.Filled.CheckCircle
    IhbarPhase.NEEDS_INFO, IhbarPhase.BLOCKED -> Icons.Filled.Warning
    IhbarPhase.ERROR -> Icons.Filled.ErrorOutline
    IhbarPhase.PENDING -> Icons.Filled.HourglassEmpty
    IhbarPhase.NO_TOKEN -> Icons.Filled.Lock
    // BUSY bu dala hiç gelmiyor (yerinde çember dönüyor), ama when tam olmalı.
    IhbarPhase.UNKNOWN, IhbarPhase.MARKABLE, IhbarPhase.BUSY -> Icons.Filled.Flag
}

@StringRes
private fun titleOf(phase: IhbarPhase): Int = when (phase) {
    IhbarPhase.UNKNOWN, IhbarPhase.MARKABLE -> R.string.ihbar_mark
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

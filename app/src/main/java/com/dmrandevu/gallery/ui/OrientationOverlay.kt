package com.dmrandevu.gallery.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.BrandingWatermark
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dmrandevu.gallery.R

/**
 * İlk açılış tanıtımı: akışın hangi harekete ne yaptığı ve hangi düğmenin ne olduğu.
 *
 * NEDEN VAR: bu ekranda tek bir yazı yok. Karar tamamen kaydırmayla veriliyor ve iki ayrı
 * "geri alma" yolu (çip ve geri kaydırma) hiçbir yerde yazmıyor; iki düğmenin de yalnızca
 * UZUN BASINCA ortaya çıkan ikinci bir ayarı var ve tek ipucu köşedeki minik bir rozet. Bunlar
 * kodda bilerek "keşfedilmesi gerekmeyen, bir kez kurulan düğmeler" diye anlatılıyor — ama bir
 * kez bile anlatılmazsa hiç kurulmuyorlar.
 *
 * NEDEN TAM EKRAN VE NEDEN AKIŞIN ÜSTÜNDE: altındaki akış dokunmaya ve kaydırmaya karşı çok
 * katmanlı; tanıtım yarı saydam bir katman olsaydı arkadaki karar yüzeyi tanıtımın üstünden
 * ihbar kararı verebilirdi. Dıştaki kutu bu yüzden tıklanabilir: hiçbir dokunuş aşağı geçmiyor.
 *
 * HESABA GÖRE DEĞİŞİYOR: yatay eksen yalnızca ihbar hesabında çalışıyor. Diğer hesaplarda
 * sağa/sola atma satırları hiç gösterilmiyor, çünkü olmayan bir hareketi öğretmek, olanı
 * öğretmemekten daha kötü.
 */
@Composable
fun OrientationOverlay(
    ihbarEnabled: Boolean,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.92f))
            // Altındaki akışa hiçbir dokunuş geçmesin diye. Dalgalanma efekti yok:
            // bu bir düğme değil, bir kalkan.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {}
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.tour_title),
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall
            )

            SectionTitle(R.string.tour_gestures)

            if (ihbarEnabled) {
                TourRow(
                    icon = Icons.AutoMirrored.Filled.ArrowForward,
                    title = R.string.tour_right_title,
                    detail = R.string.tour_right_detail
                )
                TourRow(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    title = R.string.tour_left_title,
                    detail = R.string.tour_left_detail
                )
            }
            TourRow(
                icon = Icons.Filled.ArrowUpward,
                title = R.string.tour_up_title,
                detail = R.string.tour_up_detail
            )
            TourRow(
                icon = Icons.Filled.ArrowDownward,
                title = R.string.tour_back_title,
                // İhbar kapalı hesapta geri kaydırmanın iptal edecek bir kararı yok;
                // silmeyi iptal etmesi ise her hesapta geçerli.
                detail = if (ihbarEnabled) {
                    R.string.tour_back_detail
                } else {
                    R.string.tour_back_detail_plain
                }
            )
            TourRow(
                icon = Icons.Filled.PlayArrow,
                title = R.string.tour_tap_title,
                detail = R.string.tour_tap_detail
            )
            TourRow(
                icon = Icons.Filled.FastForward,
                title = R.string.tour_hold_title,
                detail = R.string.tour_hold_detail
            )

            SectionTitle(R.string.tour_buttons)

            // Sıra ekrandaki sırayla AYNI: önce sağ raydakiler yukarıdan aşağıya,
            // sonra alt sıradakiler soldan sağa. Tanıtımın işi eşleştirme kurmak.
            TourRow(Icons.Filled.BlurOn, R.string.face_blur_toggle, R.string.tour_face_detail)
            TourRow(Icons.Filled.DirectionsCar, R.string.plate_blur_toggle, R.string.tour_plate_detail)
            TourRow(
                Icons.AutoMirrored.Filled.BrandingWatermark,
                R.string.watermark_toggle,
                R.string.tour_watermark_detail
            )
            TourRow(
                Icons.AutoMirrored.Filled.VolumeUp,
                R.string.censor_audio_toggle,
                R.string.tour_censor_detail
            )
            if (ihbarEnabled) {
                TourRow(Icons.Filled.Block, R.string.bulk_dismiss, R.string.tour_bulk_detail)
            }
            TourRow(Icons.Filled.Download, R.string.download, R.string.tour_download_detail)
            TourRow(Icons.Filled.AddCircleOutline, R.string.story, R.string.tour_story_detail)
            TourRow(Icons.Filled.Theaters, R.string.reels, R.string.tour_reels_detail)
            TourRow(Icons.Filled.AutoAwesome, R.string.caption, R.string.tour_caption_detail)

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.tour_done))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        text = stringResource(title),
        color = Color.White.copy(alpha = 0.55f),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(top = 24.dp, bottom = 4.dp)
    )
}

@Composable
private fun TourRow(icon: ImageVector, title: Int, detail: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Gerçek simgenin kendisi, benzeri değil: satırın tek işi ekranda görülen şeyle
        // buradaki cümleyi eşleştirmek.
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(24.dp)
        )
        Column {
            Text(
                text = stringResource(title),
                color = Color.White,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(detail),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * Tanıtımın gösterildiği yapıyı adlandıran etiket ("1.0+1").
 *
 * SÜRÜM KODUNA BAĞLI, salt bir "gösterildi" bayrağına değil: hareketler değiştiğinde
 * versionCode artırılınca tanıtım bir kez daha çıkıyor. Bugün kod 1'de duruyor, yani
 * `adb install -r` tanıtımı yeniden açmıyor — ayarlar silinmediği sürece bir kez görünüyor.
 */
fun buildTag(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName}+${info.longVersionCode}"
}.getOrDefault("bilinmiyor")

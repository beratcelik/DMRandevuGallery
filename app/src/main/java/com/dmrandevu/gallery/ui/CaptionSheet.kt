package com.dmrandevu.gallery.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dmrandevu.gallery.R
import com.dmrandevu.gallery.ServiceLocator
import com.dmrandevu.gallery.data.CaptionFailedException
import com.dmrandevu.gallery.data.Conversation
import com.dmrandevu.gallery.data.UnauthorizedException
import com.dmrandevu.gallery.media.Downloader
import com.dmrandevu.gallery.media.InstagramSharing
import com.dmrandevu.gallery.media.ExportOptions
import com.dmrandevu.gallery.media.VideoExporter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptionSheet(
    conversation: Conversation,
    rawMediaUrl: String,
    onSessionLost: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val repository = ServiceLocator.repository

    var caption by remember { mutableStateOf<String?>(null) }
    var explanation by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    // Sunucunun gerekçesi. "Caption üretilemedi" tek başına hiçbir şey söylemiyor; konuşmanın
    // Redis'ten düşmesi ile modelin yanıt vermemesi aynı ekranda apayrı iki sorun.
    var failureReason by remember { mutableStateOf<String?>(null) }
    var sharing by remember { mutableStateOf(false) }
    var shareProgress by remember { mutableStateOf<Int?>(null) }

    suspend fun generate(manualExplanation: String?) {
        generating = true
        failed = false
        failureReason = null
        try {
            caption = repository.generateCaption(
                salonId = conversation.salonId,
                clientId = conversation.clientId,
                rawMediaUrl = rawMediaUrl,
                manualExplanation = manualExplanation
            )
        } catch (e: UnauthorizedException) {
            onSessionLost()
            onDismiss()
        } catch (e: CaptionFailedException) {
            failed = true
            failureReason = e.serverMessage ?: "HTTP ${'$'}{e.status}"
        } catch (e: Exception) {
            failed = true
            // Ağ/çözümleme hatası: sunucuya hiç ulaşılamamış olabilir, sınıf adı bunu ayırt ediyor.
            failureReason = e.message ?: e::class.java.simpleName
        } finally {
            generating = false
        }
    }

    LaunchedEffect(rawMediaUrl) { generate(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // NEDEN ALTTA SABİT ŞERİT: büyük "Kopyala" düğmesi gelince "Reels olarak paylaş" ile
        // "Kapat" sayfanın altına itildi. İçerik kendi içinde kayıyor, ikisi her zaman ekranda
        // (iOS ikizi: CaptionSheetView.actionBar).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Stays clear of the keyboard. Typing a long explanation used to grow the sheet
                // until the buttons were under the keyboard with no way to reach them — the
                // caption could be regenerated or shared only by deleting what had been typed.
                .imePadding()
        ) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = stringResource(R.string.caption_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                when {
                    generating -> Row(
                        modifier = Modifier.padding(vertical = 24.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            text = stringResource(R.string.caption_generating),
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }

                    failed -> Column(modifier = Modifier.padding(vertical = 16.dp)) {
                        Text(
                            text = stringResource(R.string.caption_failed),
                            color = MaterialTheme.colorScheme.error
                        )
                        failureReason?.let { reason ->
                            Text(
                                text = reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }

                    // No scroll of its own any more: the sheet itself scrolls, and a scrollable box
                    // inside a scrollable column fights it for the drag. Capping the height instead
                    // would clip a long caption with no way to read the rest.
                    else -> Column {
                        Text(
                            text = caption.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        CopyCaptionButton(
                            caption = caption,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        )
                    }
                }

                OutlinedTextField(
                    value = explanation,
                    onValueChange = { explanation = it },
                    label = { Text(stringResource(R.string.caption_explanation_hint)) },
                    minLines = 2,
                    // Capped, so a long explanation scrolls inside the box instead of pushing
                    // everything below it off the screen.
                    maxLines = 4,
                    enabled = !generating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                )

                OutlinedButton(
                    onClick = { scope.launch { generate(explanation.ifBlank { null }) } },
                    enabled = !generating,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    Text(stringResource(R.string.caption_regenerate))
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 8.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
                Button(
                    onClick = {
                        val text = caption ?: return@Button
                        sharing = true
                        scope.launch {
                            try {
                                // Read at click time rather than collected: the sheet has no view
                                // model, and the toggles write through synchronously.
                                val settings = ServiceLocator.settings
                                val file = ServiceLocator.downloader.downloadForShare(
                                    rawMediaUrl,
                                    conversation.clientName,
                                    ExportOptions(
                                        blurFaces = settings.blurFaces,
                                        blurPlates = settings.blurPlates,
                                        fastPlates = settings.fastPlates,
                                        watermarkHandle = settings.igUsername
                                            .takeIf { settings.watermark && it.isNotBlank() },
                                        censorAudio = settings.censorAudio,
                                        censorInsults = settings.censorInsults,
                                        // ELLE İŞARETLEME KİPİ DE GEÇİYOR. Atlandığında varsayılan
                                        // false oluyordu: operatör "elle" kipini seçip küfürleri
                                        // kendi işaretlediği hâlde bu sayfadan paylaştığında video
                                        // baştan taranıyordu — iOS ikizi geçiriyordu, burası değil.
                                        censorByHand = settings.censorByHand,
                                        // The sheet has no view model, so the marks are read the
                                        // same way the toggles are — straight from the store.
                                        manualWindows = ServiceLocator.manualMarks.forMedia(
                                            conversation.key,
                                            conversation.urls.indexOf(rawMediaUrl).coerceAtLeast(0)
                                        )
                                    )
                                ) { shareProgress = it }
                                // Instagram drops EXTRA_TEXT, so the caption travels via the clipboard —
                                // the same trick the web gallery uses.
                                copyToClipboard(context, text)
                                Toast.makeText(context, R.string.caption_copied, Toast.LENGTH_LONG).show()
                                shareVideo(context, file)
                                onDismiss()
                            } catch (e: UnauthorizedException) {
                                onSessionLost()
                                onDismiss()
                            } catch (e: VideoExporter.ExportFailedException) {
                                Toast.makeText(
                                    context,
                                    if (ServiceLocator.exporter.isUnplacedProfanity(e)) {
                                        R.string.censor_unplaced
                                    } else {
                                        R.string.export_failed
                                    },
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
                            } finally {
                                sharing = false
                                shareProgress = null
                            }
                        }
                    },
                    enabled = !generating && !sharing && caption != null,
                    modifier = Modifier.weight(1f)
                ) {
                    if (sharing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = shareProgress
                                ?.let { stringResource(R.string.face_blur_progress, it) }
                                ?: stringResource(R.string.share_preparing),
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    } else {
                        Text(stringResource(R.string.caption_share))
                    }
                }
            }
        }
    }
}

/**
 * Caption'ın hemen altındaki büyük "Kopyala" düğmesi. iOS ikizi: `CaptionSheetView.copyButton`.
 *
 * NEDEN BÜYÜK VE NEDEN BURADA: iPhone'da caption Instagram'a kendiliğinden ulaşmıyor ve operatör
 * her paylaşımda buraya dönüp kopyalıyor; düğme eskiden "Kapat"ın yanında küçük bir yazıydı ve
 * sayfanın en altındaydı. Android'de pano devri çalışıyor, ama aynı düğme iki platformda aynı
 * yerde durmalı: ekipte iki telefonu birden kullananlar var.
 *
 * NEDEN "Kopyalandı" DURUMU: bildirim sayfanın arkasında kalabiliyor. Dokunuşun işe yaradığını
 * düğmenin kendisi söylemeli.
 */
@Composable
private fun CopyCaptionButton(caption: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var justCopied by remember { mutableStateOf(false) }
    LaunchedEffect(justCopied) {
        if (justCopied) {
            delay(2_000)
            justCopied = false
        }
    }
    FilledTonalButton(
        onClick = {
            caption?.let {
                copyToClipboard(context, it)
                Toast.makeText(context, R.string.caption_copied, Toast.LENGTH_SHORT).show()
                justCopied = true
            }
        },
        enabled = caption != null,
        modifier = modifier.heightIn(min = 52.dp)
    ) {
        Icon(
            imageVector = if (justCopied) Icons.Filled.Check else Icons.Filled.ContentCopy,
            // Metin hemen yanında; simgeyi ayrıca okutmak aynı şeyi iki kez söyletirdi.
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = stringResource(if (justCopied) R.string.caption_copied_short else R.string.caption_copy),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("caption", text))
}

private fun shareVideo(context: Context, file: java.io.File) {
    // REELS BESTECİSİ ÖNCE. Caption'ı bu sayfada ürettiysek gidilecek yer bellidir:
    // haber metniyle paylaşılan bir trafik videosu Reels'e gidiyor, Direct'e değil.
    //
    // ALTTAKİ YEDEK NE İŞE YARIYOR: ACTION_SEND Instagram'a ulaşıyor ama HANGİ
    // yüzeye düşeceğini söylemiyor — Samsung'un paylaşım sayfası hedefleri tek
    // girdiye indiriyor ve sonuç Direct oluyor. Yıllardır buradaki davranış buydu.
    // Yedek yine de duruyor: besteci kapalıysa ya da Instagram niyeti hiç
    // karşılamazsa elde bir şey kalması, hiç açılmamasından iyi.
    if (InstagramSharing.REELS_COMPOSER_ENABLED &&
        InstagramSharing.openReelComposer(context, file)
    ) {
        return
    }

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = Downloader.MIME_TYPE
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent(intent).setPackage("com.instagram.android"))
    } catch (e: ActivityNotFoundException) {
        // Instagram not installed — fall back to the system chooser.
        context.startActivity(Intent.createChooser(intent, null))
    }
}

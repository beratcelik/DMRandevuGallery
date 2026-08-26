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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.dmrandevu.gallery.data.Conversation
import com.dmrandevu.gallery.data.UnauthorizedException
import com.dmrandevu.gallery.media.Downloader
import com.dmrandevu.gallery.media.ExportOptions
import com.dmrandevu.gallery.media.VideoExporter
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
    var sharing by remember { mutableStateOf(false) }
    var shareProgress by remember { mutableStateOf<Int?>(null) }

    suspend fun generate(manualExplanation: String?) {
        generating = true
        failed = false
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
        } catch (e: Exception) {
            failed = true
        } finally {
            generating = false
        }
    }

    LaunchedEffect(rawMediaUrl) { generate(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
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

                failed -> Text(
                    text = stringResource(R.string.caption_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 16.dp)
                )

                else -> Box(
                    Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(text = caption.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                }
            }

            OutlinedTextField(
                value = explanation,
                onValueChange = { explanation = it },
                label = { Text(stringResource(R.string.caption_explanation_hint)) },
                minLines = 2,
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
                                    watermarkHandle = settings.igUsername
                                        .takeIf { settings.watermark && it.isNotBlank() }
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
                            Toast.makeText(context, R.string.export_failed, Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
                        } finally {
                            sharing = false
                            shareProgress = null
                        }
                    }
                },
                enabled = !generating && !sharing && caption != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    onClick = {
                        caption?.let {
                            copyToClipboard(context, it)
                            Toast.makeText(context, R.string.caption_copied, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = caption != null
                ) {
                    Text(stringResource(R.string.caption_copy))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("caption", text))
}

private fun shareVideo(context: Context, file: java.io.File) {
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

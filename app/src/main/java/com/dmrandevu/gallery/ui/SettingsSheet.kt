package com.dmrandevu.gallery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dmrandevu.gallery.R
import com.dmrandevu.gallery.ServiceLocator
import com.dmrandevu.gallery.data.PasswordChangeException
import com.dmrandevu.gallery.data.UnauthorizedException
import kotlinx.coroutines.launch

/** Yeni şifrenin asgari uzunluğu; sunucudaki kuralın (AdminCredentialsManager) aynısı. */
private const val MIN_PASSWORD_LENGTH = 10

/**
 * Ayarlar: hesap bilgisi, şifre değiştirme, çıkış ve sürüm.
 *
 * NEDEN ALT SAYFA: uygulamanın başka hiçbir ayar ekranı yok, ve tek amacı videoları gezmek olan
 * bir ekranın üstüne tam sayfa bir ekran eklemek gezinmeyi bozardı. Başlıktaki dişli açıyor.
 *
 * ŞİFRE: kişi yalnızca KENDİ şifresini değiştiriyor (sunucu hangi şifre olduğunu oturumdan
 * biliyor). Başarıda alanlar boşalıyor ve şifre hiçbir yerde saklanmıyor. Bir 401, kişinin kendi
 * oturumunun bittiği anlamına geliyor ve giriş ekranına dönülüyor; "mevcut şifre yanlış" ise
 * sunucudan 400 ile geliyor ve burada metniyle görünüyor.
 *
 * ÇIKIŞ: önce sorulur, çünkü yanlışlıkla dokunulabilir ve geri dönüşü yeniden şifre girmektir.
 * [onLoggedOut] çağrıldığında oturum zaten kapanmış olur.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    onLoggedOut: () -> Unit,
    onSessionLost: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val settings = ServiceLocator.settings
    val repository = ServiceLocator.repository

    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    var changing by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    val mismatch = repeat.isNotEmpty() && new != repeat
    val canSubmit = !busy && current.isNotEmpty() && new.length >= MIN_PASSWORD_LENGTH && new == repeat

    val changedText = stringResource(R.string.settings_password_changed)
    val failedText = stringResource(R.string.settings_password_failed)

    fun submit() {
        busy = true
        message = null
        scope.launch {
            try {
                repository.changePassword(current, new)
                current = ""; new = ""; repeat = ""
                changing = false
                message = changedText
                messageIsError = false
            } catch (e: UnauthorizedException) {
                // Oturum bitti: şifreyi değiştirmenin anlamı kalmadı, giriş ekranına dönülüyor.
                onSessionLost()
            } catch (e: PasswordChangeException) {
                message = e.message ?: failedText
                messageIsError = true
            } catch (e: Exception) {
                // Ağ yok: şifrenin değişip değişmediği belli değil, ama değişmediğini varsaymak güvenli.
                message = failedText
                messageIsError = true
            } finally {
                busy = false
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Klavye açılınca alanlar ve düğme ekranda kalıyor (bkz. CaptionSheet).
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            InfoRow(stringResource(R.string.settings_user), settings.adminUsername)
            InfoRow(stringResource(R.string.settings_instagram), "@${settings.igUsername}")

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            if (!changing) {
                OutlinedButton(
                    onClick = { changing = true; message = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_change_password))
                }
            } else {
                Text(
                    text = stringResource(R.string.settings_change_password),
                    style = MaterialTheme.typography.titleSmall
                )
                PasswordField(current, { current = it }, R.string.settings_current_password)
                PasswordField(new, { new = it }, R.string.settings_new_password)
                PasswordField(
                    value = repeat,
                    onChange = { repeat = it },
                    label = R.string.settings_repeat_password,
                    isError = mismatch
                )
                if (mismatch) {
                    Text(
                        text = stringResource(R.string.settings_password_mismatch),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = { changing = false; current = ""; new = ""; repeat = "" },
                        enabled = !busy
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(onClick = ::submit, enabled = canSubmit, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_change_password))
                    }
                }
            }

            message?.let {
                Text(
                    text = it,
                    color = if (messageIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 16.dp))

            OutlinedButton(
                onClick = { confirmLogout = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.logout))
            }

            if (version.isNotEmpty()) {
                Text(
                    text = "${stringResource(R.string.settings_version)} $version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text(stringResource(R.string.settings_logout_title)) },
            text = { Text(stringResource(R.string.settings_logout_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    scope.launch {
                        repository.logout()
                        onLoggedOut()
                    }
                }) {
                    Text(stringResource(R.string.logout))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    isError: Boolean = false
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    )
}

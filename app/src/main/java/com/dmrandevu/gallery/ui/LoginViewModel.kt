package com.dmrandevu.gallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dmrandevu.gallery.ServiceLocator
import com.dmrandevu.gallery.data.AccountNotFoundException
import com.dmrandevu.gallery.data.SettingsStore
import com.dmrandevu.gallery.data.UnauthorizedException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LoginError { CREDENTIALS, ACCOUNT_NOT_FOUND, NETWORK }

data class LoginUiState(
    val baseUrl: String = SettingsStore.DEFAULT_BASE_URL,
    val username: String = "",
    val password: String = "",
    val igUsername: String = SettingsStore.DEFAULT_IG_ACCOUNT,
    val probing: Boolean = true,
    val submitting: Boolean = false,
    val error: LoginError? = null
)

class LoginViewModel : ViewModel() {

    private val repo = ServiceLocator.repository
    private val settings = ServiceLocator.settings

    private val _state = MutableStateFlow(
        LoginUiState(
            baseUrl = settings.baseUrl,
            username = settings.adminUsername,
            igUsername = settings.igUsername
        )
    )
    val state: StateFlow<LoginUiState> = _state

    /** Emits the resolved Instagram id once the session is known good. */
    private val _authenticated = MutableStateFlow<String?>(null)
    val authenticated: StateFlow<String?> = _authenticated

    init {
        probeExistingSession()
    }

    fun onBaseUrl(value: String) = _state.update { it.copy(baseUrl = value, error = null) }
    fun onUsername(value: String) = _state.update { it.copy(username = value, error = null) }
    fun onPassword(value: String) = _state.update { it.copy(password = value, error = null) }
    fun onIgUsername(value: String) = _state.update { it.copy(igUsername = value, error = null) }

    /**
     * A stored cookie may still be valid (7-day server session). Resolving the account both
     * proves that and produces the id the gallery needs, so it replaces a separate ping.
     */
    private fun probeExistingSession() {
        viewModelScope.launch {
            val current = _state.value
            if (current.username.isBlank()) {
                _state.update { it.copy(probing = false) }
                return@launch
            }
            // Resolving may not touch the network (numeric ids and known handles are answered
            // locally), so the session has to be proven with a real authenticated call.
            val igId = runCatching { repo.resolveAccount(current.igUsername).igId }.getOrNull()
            val stillSignedIn = igId != null && runCatching { repo.isSessionValid(igId) }.getOrDefault(false)
            if (stillSignedIn) _authenticated.value = igId else _state.update { it.copy(probing = false) }
        }
    }

    fun submit() {
        val current = _state.value
        if (current.submitting) return
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val ok = repo.login(current.baseUrl.trim(), current.username.trim(), current.password)
                if (!ok) {
                    _state.update { it.copy(submitting = false, error = LoginError.CREDENTIALS) }
                    return@launch
                }
                settings.adminUsername = current.username
                settings.igUsername = current.igUsername
                val igId = repo.resolveAccount(current.igUsername).igId
                _authenticated.value = igId
            } catch (e: AccountNotFoundException) {
                _state.update { it.copy(submitting = false, error = LoginError.ACCOUNT_NOT_FOUND) }
            } catch (e: UnauthorizedException) {
                _state.update { it.copy(submitting = false, error = LoginError.CREDENTIALS) }
            } catch (e: Exception) {
                _state.update { it.copy(submitting = false, error = LoginError.NETWORK) }
            }
        }
    }
}

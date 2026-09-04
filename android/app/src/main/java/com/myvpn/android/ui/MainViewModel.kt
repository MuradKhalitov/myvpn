package com.myvpn.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvpn.android.data.PhoneAuthSource
import com.myvpn.android.data.PhoneVerificationStartResponse
import com.myvpn.android.data.Session
import com.myvpn.android.data.SessionExpiredException
import com.myvpn.android.data.VpnAccessResponse
import com.myvpn.android.data.VpnAccessSource
import com.myvpn.android.vpn.VpnConnectionState
import com.myvpn.android.vpn.VpnEngine
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException

enum class AppError { NETWORK_UNAVAILABLE, BACKEND_UNAVAILABLE, VERIFICATION_EXPIRED, VERIFICATION_FAILED, EXCHANGE_FAILED, VPN_PROVISIONING_FAILED, SESSION_EXPIRED }

sealed interface MainUiState {
    data object Initializing : MainUiState
    data class PhoneEntry(val validationMessage: String? = null) : MainUiState
    data class PhoneVerification(val verification: PhoneVerificationStartResponse, val waitingForCall: Boolean, val message: String? = null) : MainUiState
    data object Authenticated : MainUiState
    data class VpnProvisioning(val entitlement: String) : MainUiState
    data class Ready(val access: VpnAccessResponse, val session: Session, val message: String? = null) : MainUiState
    data class Expired(val message: String = "Срок доступа закончился. Выберите тариф, чтобы продолжить.") : MainUiState
    data class Error(val type: AppError, val message: String, val retryable: Boolean) : MainUiState
    data object AwaitingPermission : MainUiState
    data object Connecting : MainUiState
    data object Connected : MainUiState
    data object Disconnecting : MainUiState
}

class MainViewModel(
    private val auth: PhoneAuthSource,
    private val access: VpnAccessSource,
    private val engine: VpnEngine,
    private val pollDelayMillis: Long = 2_000
) : ViewModel() {
    private val _state = MutableStateFlow<MainUiState>(MainUiState.Initializing)
    val state = _state.asStateFlow()
    private var verification: PhoneVerificationStartResponse? = null
    private var session: Session? = null
    private var configuration: String? = null
    private var ready: VpnAccessResponse? = null
    private var pollingJob: Job? = null
    private var vpnJob: Job? = null
    private var exchangeStarted = false

    init {
        restoreAuth()
        viewModelScope.launch {
            engine.state.collect { connection ->
                when (connection) {
                    VpnConnectionState.Connected -> _state.value = MainUiState.Connected
                    VpnConnectionState.Connecting -> _state.value = MainUiState.Connecting
                    VpnConnectionState.Disconnecting -> _state.value = MainUiState.Disconnecting
                    VpnConnectionState.Disconnected -> ready?.let { current -> session?.let { _state.value = MainUiState.Ready(current, it) } }
                    VpnConnectionState.PermissionDenied -> ready?.let { current -> session?.let { _state.value = MainUiState.Ready(current, it, "Разрешение VPN не предоставлено") } }
                    is VpnConnectionState.Failed -> ready?.let { current -> session?.let { _state.value = MainUiState.Ready(current, it, "Не удалось подключить VPN") } }
                }
            }
        }
    }

    fun restoreAuth() {
        pollingJob?.cancel(); vpnJob?.cancel()
        viewModelScope.launch {
            val restored = runCatching { auth.restoreSession() }.getOrNull()
            if (restored == null) {
                _state.value = MainUiState.PhoneEntry()
            } else {
                session = restored
                _state.value = MainUiState.Authenticated
                loadVpn()
            }
        }
    }

    fun startPhoneVerification(phone: String) {
        if (!isPhoneValid(phone)) {
            _state.value = MainUiState.PhoneEntry("Введите номер в формате +7 999 123-45-67")
            return
        }
        viewModelScope.launch {
            runCatching { auth.startVerification(phone) }
                .onSuccess { started ->
                    verification = started
                    exchangeStarted = false
                    _state.value = MainUiState.PhoneVerification(started, waitingForCall = true)
                }
                .onFailure { _state.value = errorFor(it, AppError.BACKEND_UNAVAILABLE) }
        }
    }

    /** Called only after the system dialer returns. A single job owns status polling. */
    fun onReturnedFromDialer() {
        val current = verification ?: return
        if (pollingJob?.isActive == true || exchangeStarted) return
        pollingJob = viewModelScope.launch {
            _state.value = MainUiState.PhoneVerification(current, waitingForCall = false, message = "Ожидаем подтверждение звонка")
            while (isActive && !exchangeStarted) {
                val status = runCatching { auth.verificationStatus(current.verificationId) }
                val response = status.getOrElse {
                    _state.value = errorFor(it, AppError.BACKEND_UNAVAILABLE)
                    return@launch
                }
                when (response.status) {
                        "PENDING" -> delay(pollDelayMillis)
                        "VERIFIED" -> {
                            val exchangeToken = response.exchangeToken
                            if (exchangeToken.isNullOrBlank()) {
                                _state.value = MainUiState.Error(AppError.EXCHANGE_FAILED, "Подтверждение не содержит токен входа", true)
                            } else {
                                exchangeStarted = true
                                exchange(current.verificationId, exchangeToken)
                            }
                            return@launch
                        }
                        "EXPIRED" -> { _state.value = MainUiState.Error(AppError.VERIFICATION_EXPIRED, "Время подтверждения звонка истекло", true); return@launch }
                        "FAILED" -> { _state.value = MainUiState.Error(AppError.VERIFICATION_FAILED, "Не удалось подтвердить звонок", true); return@launch }
                        else -> { _state.value = MainUiState.Error(AppError.BACKEND_UNAVAILABLE, "Получен неизвестный статус подтверждения", true); return@launch }
                }
            }
        }
    }

    /** Used by the host lifecycle and tests; onCleared invokes the same cancellation. */
    fun stopPhoneVerificationPolling() { pollingJob?.cancel(); pollingJob = null }

    private fun exchange(verificationId: String, exchangeToken: String) {
        viewModelScope.launch {
            runCatching { auth.exchange(verificationId, exchangeToken) }
                .onSuccess { authenticated ->
                    session = authenticated
                    _state.value = MainUiState.Authenticated
                    loadVpn()
                }
                .onFailure { _state.value = errorFor(it, AppError.EXCHANGE_FAILED) }
        }
    }

    fun retry() {
        when (val current = _state.value) {
            is MainUiState.PhoneEntry -> _state.value = MainUiState.PhoneEntry()
            is MainUiState.PhoneVerification -> onReturnedFromDialer()
            is MainUiState.Error -> when (current.type) {
                AppError.VERIFICATION_EXPIRED, AppError.VERIFICATION_FAILED -> { verification = null; exchangeStarted = false; _state.value = MainUiState.PhoneEntry() }
                AppError.EXCHANGE_FAILED -> { exchangeStarted = false; onReturnedFromDialer() }
                AppError.SESSION_EXPIRED -> restoreAuth()
                else -> if (session == null) _state.value = MainUiState.PhoneEntry() else loadVpn()
            }
            else -> loadVpn()
        }
    }

    private fun loadVpn() {
        vpnJob?.cancel()
        vpnJob = viewModelScope.launch {
            while (isActive) {
                val result = runCatching { access.current() }
                val vpn = result.getOrElse { failure ->
                    if (failure is SessionExpiredException) {
                            session = null
                            _state.value = MainUiState.PhoneEntry("Сессия истекла. Войдите по номеру телефона.")
                    } else _state.value = errorFor(failure, AppError.VPN_PROVISIONING_FAILED)
                    return@launch
                }
                when (vpn.status) {
                            "READY" -> {
                                if (vpn.entitlement == "EXPIRED") _state.value = MainUiState.Expired()
                                else {
                                    configuration = vpn.configuration
                                    ready = vpn
                                    session?.let { _state.value = MainUiState.Ready(vpn, it) }
                                }
                                return@launch
                            }
                            "RETRY_REQUIRED" -> { _state.value = MainUiState.Error(AppError.VPN_PROVISIONING_FAILED, "Настройка VPN требует повторной попытки", true); return@launch }
                            else -> { _state.value = MainUiState.VpnProvisioning(vpn.entitlement); delay(pollDelayMillis) }
                }
            }
        }
    }

    fun connect() { if (configuration != null) _state.value = MainUiState.AwaitingPermission }
    fun onPermissionResult(granted: Boolean) {
        val currentConfiguration = configuration
        val currentReady = ready
        if (!granted) { if (currentReady != null && session != null) _state.value = MainUiState.Ready(currentReady, session!!, "Разрешение VPN не предоставлено"); return }
        if (currentConfiguration != null) viewModelScope.launch {
            _state.value = MainUiState.Connecting
            runCatching { engine.start(currentConfiguration) }.onFailure { if (currentReady != null && session != null) _state.value = MainUiState.Ready(currentReady, session!!, "Не удалось подключить VPN") }
        }
    }
    fun disconnect() = viewModelScope.launch { _state.value = MainUiState.Disconnecting; engine.stop() }

    override fun onCleared() { stopPhoneVerificationPolling(); vpnJob?.cancel(); super.onCleared() }

    private fun isPhoneValid(value: String): Boolean { val digits = value.filter(Char::isDigit); return digits.length in 10..15 && (value.startsWith("+") || digits.startsWith("7")) }
    private fun errorFor(error: Throwable, fallback: AppError): MainUiState.Error = when (error) {
        is IOException -> MainUiState.Error(AppError.NETWORK_UNAVAILABLE, "Нет подключения к сети", true)
        is HttpException -> MainUiState.Error(if (error.code() >= 500) AppError.BACKEND_UNAVAILABLE else fallback, if (error.code() >= 500) "Сервис временно недоступен" else "Не удалось выполнить запрос", true)
        else -> MainUiState.Error(fallback, when (fallback) { AppError.EXCHANGE_FAILED -> "Не удалось завершить вход"; AppError.VPN_PROVISIONING_FAILED -> "Не удалось настроить VPN"; else -> "Сервис временно недоступен" }, true)
    }
}

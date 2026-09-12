package com.myvpn.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.myvpn.android.data.PhoneAuthSource
import com.myvpn.android.data.PhoneVerificationStartResponse
import com.myvpn.android.data.Session
import com.myvpn.android.data.SessionExpiredException
import com.myvpn.android.data.SessionRefreshUnavailableException
import com.myvpn.android.data.SessionRecoveryException
import com.myvpn.android.data.AuthDiagnostics
import kotlinx.coroutines.CancellationException
import com.myvpn.android.data.VpnAccessResponse
import com.myvpn.android.data.VpnAccessSource
import com.myvpn.android.vpn.VpnConnectionState
import com.myvpn.android.vpn.VpnEngine
import java.time.Instant
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException

enum class AppError { NETWORK_UNAVAILABLE, BACKEND_UNAVAILABLE, PHONE_START_REJECTED, VERIFICATION_EXPIRED, VERIFICATION_FAILED, EXCHANGE_FAILED, VPN_PROVISIONING_FAILED, SESSION_EXPIRED, SESSION_RECOVERY_FAILED, RESPONSE_FORMAT }

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
    data class Connecting(val message: String? = null) : MainUiState
    data class Connected(val access: VpnAccessResponse? = null, val session: Session? = null, val message: String? = null) : MainUiState
    data class Disconnecting(val message: String? = null) : MainUiState
}

/** Backend state remains available after stop; it never hides an active engine. */
private fun withEngineState(backend: MainUiState, connection: VpnConnectionState): MainUiState {
    val detail = when (backend) {
        is MainUiState.Error -> backend.message
        is MainUiState.PhoneEntry -> backend.validationMessage ?: "Войдите по номеру телефона"
        is MainUiState.VpnProvisioning -> "Настраиваем VPN"
        is MainUiState.Expired -> backend.message
        else -> null
    }?.let { "Данные доступа: $it" }
    val ready = backend as? MainUiState.Ready
    return when (connection) {
        VpnConnectionState.Connected -> MainUiState.Connected(ready?.access, ready?.session, detail)
        VpnConnectionState.Connecting -> MainUiState.Connecting(detail)
        VpnConnectionState.Disconnecting -> MainUiState.Disconnecting(detail)
        else -> backend
    }
}

class MainViewModel(
    private val auth: PhoneAuthSource,
    private val access: VpnAccessSource,
    private val engine: VpnEngine,
    private val pollDelayMillis: Long = 2_000,
    private val now: () -> Instant = Instant::now
) : ViewModel() {
    private val _state = MutableStateFlow(withEngineState(MainUiState.Initializing, engine.state.value))
    val state = _state.asStateFlow()
    private var backendState: MainUiState = MainUiState.Initializing
        set(value) {
            field = value
            _state.value = withEngineState(value, engine.state.value)
        }
    private data class ActivePhoneVerification(
        val canonicalPhone: String,
        val response: PhoneVerificationStartResponse
    ) {
        fun isPendingAt(now: Instant): Boolean = runCatching { Instant.parse(response.expiresAt).isAfter(now) }.getOrDefault(false)
    }

    private var activeVerification: ActivePhoneVerification? = null
    private var session: Session? = null
    private var configuration: String? = null
    private var ready: VpnAccessResponse? = null
    private var pollingJob: Job? = null
    private var vpnJob: Job? = null
    private var exchangeStarted = false
    private var restoreAttempt = 0L

    init {
        viewModelScope.launch {
            state.collect { current ->
                AuthDiagnostics.event("UI_STATE_CHANGED", "state=${current::class.java.simpleName}" +
                    if (current is MainUiState.Error) " category=${current.type}" else "")
            }
        }
        restoreAuth()
        viewModelScope.launch {
            engine.state.collect { connection ->
                when (connection) {
                    VpnConnectionState.PermissionDenied -> ready?.let { current -> session?.let { backendState = MainUiState.Ready(current, it, "Разрешение VPN не предоставлено") } }
                    is VpnConnectionState.Failed -> ready?.let { current -> session?.let { backendState = MainUiState.Ready(current, it, "Не удалось подключить VPN") } }
                    else -> if (backendState is MainUiState.Connecting) {
                        ready?.let(::renderVpnState)
                    }
                }
                _state.value = withEngineState(backendState, engine.state.value)
            }
        }
    }

    fun restoreAuth() {
        pollingJob?.cancel(); vpnJob?.cancel()
        configuration = null
        ready = null
        val attempt = ++restoreAttempt
        AuthDiagnostics.event("AUTH_RESTORE_STARTED", "attempt=$attempt")
        backendState = MainUiState.Initializing
        viewModelScope.launch {
            val restored = try {
                auth.restoreSession()
            } catch (failure: CancellationException) {
                AuthDiagnostics.event("AUTH_RESTORE_FINISHED", "attempt=$attempt result=CANCELLED")
                throw failure
            } catch (failure: SessionRefreshUnavailableException) {
                AuthDiagnostics.event("AUTH_RESTORE_FINISHED", "attempt=$attempt result=ERROR category=${AuthDiagnostics.category(failure)}")
                backendState = errorFor(failure.cause ?: failure, AppError.SESSION_EXPIRED)
                return@launch
            } catch (failure: Throwable) {
                AuthDiagnostics.event("AUTH_RESTORE_FINISHED", "attempt=$attempt result=ERROR category=${AuthDiagnostics.category(failure)}")
                backendState = errorFor(failure, AppError.SESSION_EXPIRED)
                return@launch
            }
            AuthDiagnostics.event("AUTH_RESTORE_FINISHED", "attempt=$attempt result=${if (restored == null) "NO_SESSION" else "SUCCESS"}")
            if (restored == null) {
                safeAuthLog("Auth restore result=LOCAL_MISSING_CORRUPTED_OR_REVOKED")
                session = null
                backendState = MainUiState.PhoneEntry()
            } else {
                safeAuthLog("Auth restore result=AUTHENTICATED")
                session = restored
                backendState = MainUiState.Authenticated
                loadVpn()
            }
        }
    }

    fun startPhoneVerification(phone: String) {
        val canonicalPhone = PhoneNumberInputFormatter.fromUserInput(phone).canonical
        if (!isPhoneValid(canonicalPhone)) {
            backendState = MainUiState.PhoneEntry("Введите номер в формате +7 999 123-45-67")
            return
        }
        val existing = activeVerification
        if (existing != null && !existing.isPendingAt(now())) {
            activeVerification = null
        } else if (existing?.canonicalPhone == canonicalPhone) {
            stopPhoneVerificationPolling()
            backendState = MainUiState.PhoneVerification(existing.response, waitingForCall = true)
            return
        }
        viewModelScope.launch {
            runCatching { auth.startVerification(canonicalPhone) }
                .onSuccess { started ->
                    activeVerification = ActivePhoneVerification(canonicalPhone, started)
                    exchangeStarted = false
                    backendState = MainUiState.PhoneVerification(started, waitingForCall = true)
                }
                .onFailure { backendState = phoneStartErrorFor(it) }
        }
    }

    /** Called only after the system dialer returns. A single job owns status polling. */
    fun onReturnedFromDialer() {
        val active = activeVerification ?: return
        if (!active.isPendingAt(now())) {
            activeVerification = null
            backendState = MainUiState.Error(AppError.VERIFICATION_EXPIRED, "Время подтверждения звонка истекло", true)
            return
        }
        val current = active.response
        if (pollingJob?.isActive == true || exchangeStarted) return
        pollingJob = viewModelScope.launch {
            backendState = MainUiState.PhoneVerification(current, waitingForCall = false, message = "Ожидаем подтверждение звонка")
            while (isActive && !exchangeStarted) {
                val status = runCatching { auth.verificationStatus(current.verificationId) }
                val response = status.getOrElse {
                    backendState = errorFor(it, AppError.BACKEND_UNAVAILABLE)
                    return@launch
                }
                if (response.status == "EXPIRED" || response.status == "FAILED") activeVerification = null
                when (response.status) {
                        "PENDING" -> delay(pollDelayMillis)
                        "VERIFIED" -> {
                            val exchangeToken = response.exchangeToken
                            if (exchangeToken.isNullOrBlank()) {
                                backendState = MainUiState.Error(AppError.EXCHANGE_FAILED, "Подтверждение не содержит токен входа", true)
                            } else {
                                exchangeStarted = true
                                activeVerification = null
                                exchange(current.verificationId, exchangeToken)
                            }
                            return@launch
                        }
                        "EXPIRED" -> { backendState = MainUiState.Error(AppError.VERIFICATION_EXPIRED, "Время подтверждения звонка истекло", true); return@launch }
                        "FAILED" -> { backendState = MainUiState.Error(AppError.VERIFICATION_FAILED, "Не удалось подтвердить звонок", true); return@launch }
                        else -> { backendState = MainUiState.Error(AppError.BACKEND_UNAVAILABLE, "Получен неизвестный статус подтверждения", true); return@launch }
                }
            }
        }
    }

    /** Used by the host lifecycle and tests; onCleared invokes the same cancellation. */
    fun stopPhoneVerificationPolling() { pollingJob?.cancel(); pollingJob = null }

    fun changePhoneNumber() {
        stopPhoneVerificationPolling()
        backendState = MainUiState.PhoneEntry()
    }

    private fun exchange(verificationId: String, exchangeToken: String) {
        viewModelScope.launch {
            runCatching { auth.exchange(verificationId, exchangeToken) }
                .onSuccess { authenticated ->
                    session = authenticated
                    backendState = MainUiState.Authenticated
                    loadVpn()
                }
                .onFailure { backendState = errorFor(it, AppError.EXCHANGE_FAILED) }
        }
    }

    fun retry() {
        AuthDiagnostics.event("AUTH_RETRY_CLICKED", "state=${backendState::class.java.simpleName}")
        when (val current = backendState) {
            is MainUiState.PhoneEntry -> backendState = MainUiState.PhoneEntry()
            is MainUiState.PhoneVerification -> onReturnedFromDialer()
            is MainUiState.Error -> when (current.type) {
                AppError.VERIFICATION_EXPIRED, AppError.VERIFICATION_FAILED -> { activeVerification = null; exchangeStarted = false; backendState = MainUiState.PhoneEntry() }
                AppError.EXCHANGE_FAILED -> { exchangeStarted = false; onReturnedFromDialer() }
                AppError.SESSION_EXPIRED, AppError.SESSION_RECOVERY_FAILED, AppError.NETWORK_UNAVAILABLE, AppError.BACKEND_UNAVAILABLE -> restoreAuth()
                else -> restoreAuth()
            }
            else -> loadVpn()
        }
    }

    private fun loadVpn() {
        vpnJob?.cancel()
        configuration = null
        ready = null
        backendState = MainUiState.Authenticated
        vpnJob = viewModelScope.launch {
            AuthDiagnostics.event("VPN_LOAD_STARTED")
            while (isActive) {
                val result = runCatching { access.current() }
                val vpn = result.getOrElse { failure ->
                    if (failure is CancellationException) throw failure
                    AuthDiagnostics.event("VPN_LOAD_FINISHED", "result=ERROR category=${AuthDiagnostics.category(failure)}")
                    if (failure is SessionExpiredException) {
                            session = null
                            backendState = MainUiState.PhoneEntry("Сессия истекла. Войдите по номеру телефона.")
                    } else backendState = errorFor(failure, AppError.VPN_PROVISIONING_FAILED)
                    return@launch
                }
                AuthDiagnostics.event("VPN_LOAD_FINISHED", "result=SUCCESS")
                when (vpn.status) {
                            "READY" -> {
                                if (vpn.entitlement == "EXPIRED") backendState = MainUiState.Expired()
                                else {
                                    configuration = vpn.configuration
                                    ready = vpn
                                    renderVpnState(vpn)
                                }
                                return@launch
                            }
                            "RETRY_REQUIRED" -> { backendState = MainUiState.Error(AppError.VPN_PROVISIONING_FAILED, "Настройка VPN требует повторной попытки", true); return@launch }
                            else -> { backendState = MainUiState.VpnProvisioning(vpn.entitlement); delay(pollDelayMillis) }
                }
            }
        }
    }

    fun connect() {
        if (engine.state.value is VpnConnectionState.Connected
            || engine.state.value is VpnConnectionState.Connecting
            || engine.state.value is VpnConnectionState.Disconnecting) return
        if (backendState is MainUiState.Ready && !configuration.isNullOrBlank() && session != null) {
            backendState = MainUiState.AwaitingPermission
        }
    }
    fun onPermissionResult(granted: Boolean) {
        val currentConfiguration = configuration
        val currentReady = ready
        if (!granted) { if (currentReady != null && session != null) backendState = MainUiState.Ready(currentReady, session!!, "Разрешение VPN не предоставлено"); return }
        if (!currentConfiguration.isNullOrBlank() && session != null && currentReady != null
            && backendState is MainUiState.AwaitingPermission
            && engine.state.value !is VpnConnectionState.Connected
            && engine.state.value !is VpnConnectionState.Connecting
            && engine.state.value !is VpnConnectionState.Disconnecting) viewModelScope.launch {
            backendState = MainUiState.Connecting()
            runCatching { engine.start(currentConfiguration) }.onFailure { session?.let { backendState = MainUiState.Ready(currentReady, it, "Не удалось подключить VPN") } }
        }
    }
    fun disconnect() = viewModelScope.launch { engine.stop() }

    private fun renderVpnState(access: VpnAccessResponse) {
        val authenticated = session ?: return
        backendState = MainUiState.Ready(access, authenticated)
    }

    override fun onCleared() { stopPhoneVerificationPolling(); vpnJob?.cancel(); super.onCleared() }

    private fun isPhoneValid(value: String): Boolean = PhoneNumberInputFormatter.fromUserInput(value).isComplete
    private fun phoneStartErrorFor(error: Throwable): MainUiState.Error = when (error) {
        is IOException -> MainUiState.Error(AppError.NETWORK_UNAVAILABLE, "Нет подключения к интернету", true)
        is HttpException -> when {
            error.code() >= 500 -> MainUiState.Error(AppError.BACKEND_UNAVAILABLE, "Сервис временно недоступен", true)
            // The current API uses 401/INVALID_AUTHENTICATION for both active and throttled starts.
            error.code() == 401 -> MainUiState.Error(AppError.PHONE_START_REJECTED, "Для этого номера уже запущено подтверждение или нужно немного подождать", true)
            else -> MainUiState.Error(AppError.PHONE_START_REJECTED, "Не удалось начать подтверждение номера", true)
        }
        else -> MainUiState.Error(AppError.PHONE_START_REJECTED, "Не удалось начать подтверждение номера", true)
    }
    private fun errorFor(error: Throwable, fallback: AppError): MainUiState.Error = when (error) {
        is kotlinx.serialization.SerializationException -> MainUiState.Error(AppError.RESPONSE_FORMAT, "Не удалось обработать ответ сервиса. Повторите попытку.", true)
        is SessionRecoveryException -> MainUiState.Error(AppError.SESSION_RECOVERY_FAILED, "Не удалось восстановить сессию", true)
        is SessionRefreshUnavailableException -> errorFor(error.cause ?: error, AppError.SESSION_EXPIRED)
        is IOException -> MainUiState.Error(AppError.NETWORK_UNAVAILABLE, "Нет подключения к сети", true)
        is HttpException -> MainUiState.Error(
            if (error.code() == 408 || error.code() == 429 || error.code() >= 500) AppError.BACKEND_UNAVAILABLE else fallback,
            if (error.code() == 408 || error.code() == 429 || error.code() >= 500) "Сервис временно недоступен" else "Не удалось выполнить запрос",
            true)
        else -> MainUiState.Error(fallback, when (fallback) { AppError.EXCHANGE_FAILED -> "Не удалось завершить вход"; AppError.VPN_PROVISIONING_FAILED -> "Не удалось настроить VPN"; else -> "Сервис временно недоступен" }, true)
    }

    private fun safeAuthLog(message: String) {
        runCatching { android.util.Log.i("MyVpnAuth", message) }
    }
}

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
import com.myvpn.android.data.PaymentSource
import com.myvpn.android.data.TariffResponse
import com.myvpn.android.data.validCheckoutUrl
import kotlinx.coroutines.CancellationException
import com.myvpn.android.data.VpnAccessResponse
import com.myvpn.android.data.VpnAccessSource
import com.myvpn.android.vpn.VpnConnectionState
import com.myvpn.android.vpn.VpnEngine
import java.time.Instant
import java.io.IOException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import retrofit2.HttpException

enum class AppError { NETWORK_UNAVAILABLE, BACKEND_UNAVAILABLE, PHONE_START_REJECTED, VERIFICATION_EXPIRED, VERIFICATION_FAILED, EXCHANGE_FAILED, VPN_PROVISIONING_FAILED, SESSION_EXPIRED, SESSION_RECOVERY_FAILED, RESPONSE_FORMAT }

data class PaymentCheckState(val busy: Boolean = false, val message: String? = null)

sealed interface MainUiState {
    data object Initializing : MainUiState
    data class PhoneEntry(val validationMessage: String? = null) : MainUiState
    data class PhoneVerification(val verification: PhoneVerificationStartResponse, val waitingForCall: Boolean, val message: String? = null) : MainUiState
    data object Authenticated : MainUiState
    data class VpnProvisioning(val entitlement: String) : MainUiState
    data class Ready(val access: VpnAccessResponse, val session: Session, val message: String? = null) : MainUiState
    data class Expired(val message: String = "Срок доступа закончился. Выберите тариф, чтобы продолжить.") : MainUiState
    data class Tariffs(
        val items: List<TariffResponse> = emptyList(), val loading: Boolean = false,
        val busy: Boolean = false, val message: String? = null,
        val checkoutUrl: String? = null, val awaitingPayment: Boolean = false,
        val connection: VpnConnectionState = VpnConnectionState.Disconnected
    ) : MainUiState
    data class Error(val type: AppError, val message: String, val retryable: Boolean) : MainUiState
    data object AwaitingPermission : MainUiState
    data class Connecting(val message: String? = null) : MainUiState
    data class Connected(val access: VpnAccessResponse? = null, val session: Session? = null, val message: String? = null, val retryable: Boolean = false) : MainUiState
    data class Disconnecting(val message: String? = null) : MainUiState
}

/** Backend state remains available after stop; it never hides an active engine. */
private fun withEngineState(backend: MainUiState, connection: VpnConnectionState): MainUiState {
    // Explicit tariff navigation keeps live VPN controls instead of hiding the screen.
    if (backend is MainUiState.Tariffs) return backend.copy(connection = connection)
    val detail = when (backend) {
        is MainUiState.Error -> backend.message
        is MainUiState.PhoneEntry -> backend.validationMessage ?: "Войдите по номеру телефона"
        is MainUiState.VpnProvisioning -> "Настраиваем VPN"
        is MainUiState.Expired -> backend.message
        else -> null
    }?.let { "Данные доступа: $it" }
    val ready = backend as? MainUiState.Ready
    return when (connection) {
        VpnConnectionState.Connected -> MainUiState.Connected(ready?.access, ready?.session, detail,
            (backend as? MainUiState.Error)?.retryable == true)
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
    private val payments: PaymentSource? = null,
    private val now: () -> Instant = Instant::now
) : ViewModel() {
    private val _state = MutableStateFlow(withEngineState(MainUiState.Initializing, engine.state.value))
    val state = _state.asStateFlow()
    private val _paymentCheck = MutableStateFlow(PaymentCheckState())
    val paymentCheck = _paymentCheck.asStateFlow()
    private var awaitingBrowserReturn = false
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
    private var paymentJob: Job? = null
    private var tariffOriginExpired: Boolean? = null
    private var paymentAccessRefreshPending = false

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
                    is VpnConnectionState.Failed -> ready?.let { current -> session?.let { backendState = MainUiState.Ready(current, it,
                        if (connection.message == com.myvpn.android.vpn.NO_SELECTED_APPS_MESSAGE) connection.message else "Не удалось подключить VPN") } }
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
        if (paymentAccessRefreshPending && session != null) {
            if (vpnJob?.isActive != true) loadVpn()
            return
        }
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

    fun openTariffs() {
        val source = payments ?: return
        if (backendState !is MainUiState.Expired && backendState !is MainUiState.Tariffs
            && (backendState as? MainUiState.Ready)?.access?.entitlement !in listOf("TRIAL", "PREMIUM")) return
        if (paymentJob?.isActive == true) return
        if (backendState !is MainUiState.Tariffs) tariffOriginExpired = backendState is MainUiState.Expired
        backendState = MainUiState.Tariffs(loading = true)
        paymentJob = viewModelScope.launch {
            try {
                val items = source.tariffs()
                currentCoroutineContext().ensureActive()
                backendState = MainUiState.Tariffs(items, message = if (items.isEmpty()) "Тарифы пока недоступны" else null)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                backendState = MainUiState.Tariffs(message = paymentError(failure, "Не удалось загрузить тарифы"))
            }
        }
    }

    fun closeTariffs() {
        val current = backendState as? MainUiState.Tariffs ?: return
        // Do not cancel an in-flight payment check or restore a snapshot after activation.
        if (current.busy) return
        val wasExpired = tariffOriginExpired ?: return
        paymentJob?.cancel()
        paymentJob = null
        tariffOriginExpired = null
        if (!current.awaitingPayment) {
            awaitingBrowserReturn = false
            _paymentCheck.value = PaymentCheckState()
        }
        // Rebuild from current access/session and let the setter project live engine state.
        val currentAccess = ready
        when {
            currentAccess != null && session != null -> renderVpnState(currentAccess)
            wasExpired -> backendState = MainUiState.Expired()
            session != null -> loadVpn()
            else -> restoreAuth()
        }
    }

    fun chooseTariff(code: String) {
        val source = payments ?: return
        val current = backendState as? MainUiState.Tariffs ?: return
        if (current.loading || current.busy || current.awaitingPayment || current.items.none { it.code == code }) return
        backendState = current.copy(busy = true, message = null)
        paymentJob = viewModelScope.launch {
            try {
                val checkout = source.checkout(code)
                check(validCheckoutUrl(checkout.confirmationUrl)) { "Invalid checkout URL" }
                backendState = current.copy(checkoutUrl = checkout.confirmationUrl, awaitingPayment = true,
                    message = "Завершите оплату в браузере, затем проверьте статус")
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                backendState = current.copy(message = paymentError(failure, "Не удалось открыть оплату. Проверьте статус перед повторной попыткой."))
            }
        }
    }

    fun consumeCheckoutUrl(): String? {
        val current = backendState as? MainUiState.Tariffs ?: return null
        if (current.checkoutUrl != null) awaitingBrowserReturn = true
        backendState = current.copy(checkoutUrl = null)
        return current.checkoutUrl
    }

    fun checkoutOpenFailed() {
        awaitingBrowserReturn = false
        val current = backendState as? MainUiState.Tariffs ?: return
        backendState = current.copy(awaitingPayment = false, message = "Не удалось открыть браузер. Проверьте статус оплаты.")
    }

    fun checkPayment() {
        val source = payments ?: return
        if (paymentJob?.isActive == true || vpnJob?.isActive == true) return
        val current = backendState as? MainUiState.Tariffs
        if (current != null && (current.loading || current.busy)) return
        if (current == null && backendState !is MainUiState.Ready && backendState !is MainUiState.Expired
            && state.value !is MainUiState.Connected) return
        if (paymentAccessRefreshPending) { retry(); return }
        if (current != null) backendState = current.copy(busy = true, checkoutUrl = null)
        _paymentCheck.value = PaymentCheckState(busy = true)
        paymentJob = viewModelScope.launch {
            try {
                val payment = source.current()
                if (payment.paymentStatus == "SUCCEEDED" && payment.activationStatus == "ACTIVATED") {
                    // Access/config, not the redirect URL, decides whether Connect is available.
                    tariffOriginExpired = null
                    paymentAccessRefreshPending = true
                    loadVpn()
                } else {
                    val terminal = payment.paymentStatus in listOf("CANCELED", "EXPIRED", "FAILED") || payment.outcome == "NOT_FOUND"
                    val message = when {
                        payment.outcome == "NOT_FOUND" -> "Оплата не найдена"
                        terminal -> "Оплата отменена или не завершена. Можно выбрать тариф снова."
                        payment.paymentStatus == "SUCCEEDED" -> "Оплата получена. Доступ ещё активируется — проверьте позже."
                        payment.outcome in listOf("MANUAL_REVIEW_REQUIRED", "AMBIGUOUS_PAYMENT_STATE") -> "Оплата требует проверки. Не оплачивайте повторно."
                        payment.outcome in listOf("PROVIDER_UNAVAILABLE", "PROVIDER_RESULT_UNCERTAIN") -> "Статус оплаты временно недоступен. Проверьте позже."
                        else -> "Оплата ещё не подтверждена. Проверьте позже."
                    }
                    val detail = message + (payment.nextCheckAt?.let { " Следующая проверка: $it" } ?: "")
                    _paymentCheck.value = PaymentCheckState(message = detail)
                    if (current != null) backendState = current.copy(busy = false, checkoutUrl = null,
                        awaitingPayment = !terminal, message = detail)
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) {
                val message = paymentError(failure, "Не удалось проверить оплату")
                _paymentCheck.value = PaymentCheckState(message = message)
                if (current != null) backendState = current.copy(busy = false, checkoutUrl = null, message = message)
            } finally {
                _paymentCheck.value = _paymentCheck.value.copy(busy = false)
            }
        }
    }

    fun onPaymentReturned() {
        if (!awaitingBrowserReturn) return
        awaitingBrowserReturn = false
        closeTariffs()
        checkPayment()
    }

    private fun paymentError(failure: Exception, fallback: String): String = when (failure) {
        is IOException, is HttpException, is kotlinx.serialization.SerializationException,
        is SessionRefreshUnavailableException, is SessionRecoveryException -> errorFor(failure, AppError.BACKEND_UNAVAILABLE).message
        is SessionExpiredException -> "Сессия истекла. Вернитесь и войдите снова."
        else -> fallback
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
                    } else {
                        val error = errorFor(failure, AppError.VPN_PROVISIONING_FAILED)
                        backendState = if (paymentAccessRefreshPending) error.copy(message = "Оплата подтверждена. ${error.message}") else error
                    }
                    return@launch
                }
                AuthDiagnostics.event("VPN_LOAD_FINISHED", "result=SUCCESS")
                when (vpn.status) {
                            "READY" -> {
                                paymentAccessRefreshPending = false
                                if (vpn.entitlement == "EXPIRED") backendState = MainUiState.Expired()
                                else {
                                    configuration = vpn.configuration
                                    ready = vpn
                                    renderVpnState(vpn)
                                }
                                return@launch
                            }
                            "RETRY_REQUIRED" -> { backendState = MainUiState.Error(AppError.VPN_PROVISIONING_FAILED,
                                (if (paymentAccessRefreshPending) "Оплата подтверждена. " else "") + "Настройка VPN требует повторной попытки", true); return@launch }
                            else -> {
                                if (paymentAccessRefreshPending) {
                                    backendState = MainUiState.Error(AppError.VPN_PROVISIONING_FAILED,
                                        "Оплата подтверждена. Данные доступа ещё обновляются. Повторите проверку.", true)
                                    return@launch
                                }
                                backendState = MainUiState.VpnProvisioning(vpn.entitlement); delay(pollDelayMillis)
                            }
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

    override fun onCleared() { stopPhoneVerificationPolling(); vpnJob?.cancel(); paymentJob?.cancel(); super.onCleared() }

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

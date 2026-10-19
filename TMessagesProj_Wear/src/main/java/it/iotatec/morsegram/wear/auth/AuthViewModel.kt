package it.iotatec.morsegram.wear.auth

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateClosed
import dev.g000sha256.tdl.dto.AuthorizationStateClosing
import dev.g000sha256.tdl.dto.AuthorizationStateLoggingOut
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.ConnectionState
import it.iotatec.morsegram.wear.R
import it.iotatec.morsegram.wear.tdlib.TdlException
import it.iotatec.morsegram.wear.tdlib.Tdlib
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Stato della schermata di accesso: derivato da `Tdlib.authState` + percorso scelto dall'utente. */
sealed interface AuthUiState {
    /** TDLib non ancora pronto (parametri, chiusura, riavvio su altro DC, logout in corso). */
    data object Loading : AuthUiState

    /** Prima schermata: avviso "client non ufficiale" e scelta del percorso. */
    data object Intro : AuthUiState

    /** Percorso telefono (bridge Data Layer). */
    data class Bridge(val step: BridgeStep) : AuthUiState

    /** Percorso QR: [link] null finché TDLib non emette `authorizationStateWaitOtherDeviceConfirmation`. */
    data class Qr(val link: String?, val error: String? = null) : AuthUiState

    /** Password cloud (verifica in due passaggi): unico caso in cui l'orologio chiede una password. */
    data class Password(val hint: String, val busy: Boolean = false, val error: String? = null) : AuthUiState

    /** Codice SMS / registrazione / email / Premium: non gestibili dall'orologio. */
    data class FinishOnPhone(val busy: Boolean = false, val error: String? = null) : AuthUiState
}

sealed interface BridgeStep {
    /** Cerco i nodi connessi e invio l'offer. */
    data object Searching : BridgeStep

    /** Offer consegnata: l'utente deve confermare sul telefono (foglio "Usa con Wear OS"). */
    data object WaitingPhone : BridgeStep

    /** Answer ricevuta: mostrare le 4 emoji SAS da confrontare col telefono. */
    data class CompareEmojis(val emojis: List<String>) : BridgeStep

    /** Token ricevuto e decifrato: `checkAuthenticationWebToken` in corso. */
    data object LoggingIn : BridgeStep

    /** Fallito: [message] da mostrare; [suggestQr] propone il percorso QR come alternativa. */
    data class Failed(val message: String, val suggestQr: Boolean) : BridgeStep
}

class AuthViewModel(app: Application) : AndroidViewModel(app) {

    private enum class Path { NONE, BRIDGE, QR }

    private data class Local(
        val path: Path = Path.NONE,
        val bridge: BridgeStep = BridgeStep.Searching,
        val qrError: String? = null,
        val passwordBusy: Boolean = false,
        val passwordError: String? = null,
        val phoneStepBusy: Boolean = false,
        val phoneStepError: String? = null,
    )

    private val local = MutableStateFlow(Local())

    private var bridge: WearAuthBridge? = null
    private var bridgeJob: Job? = null
    private var qrRequestJob: Job? = null

    val uiState: StateFlow<AuthUiState> = combine(Tdlib.authState, local) { auth, l -> map(auth, l) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthUiState.Loading)

    val connectionState: StateFlow<ConnectionState?> = Tdlib.connectionState

    init {
        // Reset quando si entra (login riuscito) e quando TDLib torna a WaitPhoneNumber con il QR scelto
        // (es. dopo un riavvio del client): il QR va richiesto di nuovo.
        viewModelScope.launch {
            Tdlib.authState.collect { auth ->
                if (auth is AuthorizationStateReady) {
                    cancelBridge(notifyPhone = false)
                    local.value = Local()
                }
            }
        }
        viewModelScope.launch {
            combine(Tdlib.authState, local.map { it.path }.distinctUntilChanged()) { auth, path -> auth to path }
                .collect { (auth, path) ->
                    if (path == Path.QR && auth is AuthorizationStateWaitPhoneNumber) requestQr()
                }
        }
    }

    private fun map(auth: AuthorizationState?, l: Local): AuthUiState = when (auth) {
        null,
        is AuthorizationStateWaitTdlibParameters,
        is AuthorizationStateLoggingOut,
        is AuthorizationStateClosing,
        is AuthorizationStateClosed,
        is AuthorizationStateReady -> AuthUiState.Loading

        is AuthorizationStateWaitPhoneNumber,
        is AuthorizationStateWaitOtherDeviceConfirmation -> when (l.path) {
            Path.NONE -> AuthUiState.Intro
            Path.BRIDGE -> AuthUiState.Bridge(l.bridge)
            Path.QR -> AuthUiState.Qr(
                link = (auth as? AuthorizationStateWaitOtherDeviceConfirmation)?.link,
                error = l.qrError,
            )
        }

        is AuthorizationStateWaitPassword -> AuthUiState.Password(
            hint = auth.passwordHint,
            busy = l.passwordBusy,
            error = l.passwordError,
        )

        // WaitCode, WaitRegistration, WaitEmailAddress, WaitEmailCode, WaitPremiumPurchase
        else -> AuthUiState.FinishOnPhone(busy = l.phoneStepBusy, error = l.phoneStepError)
    }

    // ---- percorso telefono (bridge) ------------------------------------------------------------

    fun startBridge() {
        cancelBridge(notifyPhone = true)
        local.update { it.copy(path = Path.BRIDGE, bridge = BridgeStep.Searching) }
        val b = WearAuthBridge(getApplication())
        bridge = b
        bridgeJob = viewModelScope.launch {
            try {
                val token = b.run(
                    onOfferSent = { local.update { it.copy(bridge = BridgeStep.WaitingPhone) } },
                    onEmojis = { emojis -> local.update { it.copy(bridge = BridgeStep.CompareEmojis(emojis)) } },
                )
                local.update { it.copy(bridge = BridgeStep.LoggingIn) }
                loginWithToken(token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WearAuthBridge.NoPhoneException) {
                failBridge(str(R.string.auth_bridge_no_phone))
            } catch (e: WearAuthBridge.TimeoutException) {
                failBridge(str(R.string.auth_bridge_timeout))
            } catch (e: WearAuthBridge.ProtocolException) {
                failBridge(str(R.string.auth_bridge_protocol_error))
            } catch (e: Exception) {
                failBridge(str(R.string.auth_error_generic, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private suspend fun loginWithToken(t: WearAuthBridge.Token) {
        if (t.isTest != Tdlib.useTestDc) {
            // Il telefono è su un altro DC (test/prod): ricrea il client, poi aspetta che sia pronto.
            Tdlib.restart(t.isTest)
            val ready = withTimeoutOrNull(30_000) {
                Tdlib.authState.first { it is AuthorizationStateWaitPhoneNumber }
            }
            if (ready == null) {
                failBridge(str(R.string.auth_error_generic, "TDLib not ready"))
                return
            }
        } else {
            withTimeoutOrNull(10_000) {
                Tdlib.authState.first {
                    it is AuthorizationStateWaitPhoneNumber || it is AuthorizationStateWaitOtherDeviceConfirmation
                }
            }
        }
        val r = Tdlib.req { checkAuthenticationWebToken(t.token, t.dcId) }
        r.exceptionOrNull()?.let { e ->
            // Chiamata "solo per app ufficiali": con il nostro api_id può essere rifiutata → proponi il QR.
            failBridge(bridgeTokenError(e))
        }
        // In caso di successo TDLib emette Ready (o WaitPassword): la UI segue authState.
    }

    private fun failBridge(message: String) {
        local.update { it.copy(bridge = BridgeStep.Failed(message, suggestQr = true)) }
    }

    private fun cancelBridge(notifyPhone: Boolean) {
        bridgeJob?.cancel()
        bridgeJob = null
        val b = bridge ?: return
        bridge = null
        if (notifyPhone && b.needsCancel) {
            // Scope dell'app, non del ViewModel: il /cancel deve partire anche da onCleared().
            Tdlib.scope.launch { b.sendCancel() }
        }
    }

    // ---- percorso QR -----------------------------------------------------------------------------

    fun startQr() {
        cancelBridge(notifyPhone = true)
        local.update { it.copy(path = Path.QR, qrError = null) }
        // Se TDLib è già in WaitPhoneNumber il collector in init chiama requestQr(); se è già in
        // WaitOtherDeviceConfirmation il link c'è già e viene mostrato subito.
    }

    /** Da FinishOnPhone: riprova il QR (TDLib lo permette da WaitCode/WaitPassword/… senza query pendenti). */
    fun retryQr() {
        local.update { it.copy(path = Path.QR, qrError = null, phoneStepError = null, phoneStepBusy = true) }
        viewModelScope.launch {
            val r = Tdlib.req { requestQrCodeAuthentication(longArrayOf()) }
            local.update { it.copy(phoneStepBusy = false, phoneStepError = r.exceptionOrNull()?.let(::friendly)) }
        }
    }

    private fun requestQr() {
        if (qrRequestJob?.isActive == true) return
        qrRequestJob = viewModelScope.launch {
            local.update { it.copy(qrError = null) }
            val r = Tdlib.req { requestQrCodeAuthentication(longArrayOf()) }
            r.exceptionOrNull()?.let { e -> local.update { it.copy(qrError = friendly(e)) } }
        }
    }

    // ---- password cloud --------------------------------------------------------------------------

    fun submitPassword(password: String) {
        if (password.isEmpty() || local.value.passwordBusy) return
        viewModelScope.launch {
            local.update { it.copy(passwordBusy = true, passwordError = null) }
            val r = Tdlib.req { checkAuthenticationPassword(password) }
            local.update { it.copy(passwordBusy = false, passwordError = r.exceptionOrNull()?.let(::friendly)) }
        }
    }

    // ---- navigazione -----------------------------------------------------------------------------

    /** Torna alla prima schermata (bridge → /cancel al telefono; QR → resta attivo in TDLib, innocuo). */
    fun back() {
        cancelBridge(notifyPhone = true)
        local.update { it.copy(path = Path.NONE, qrError = null) }
    }

    /**
     * Ricomincia da zero: `logOut` (chiude la sessione parziale lato server) con fallback a `destroy`
     * se non risponde (es. senza rete). TDLib emette Closed → il core ricrea il client → WaitPhoneNumber.
     */
    fun restart() {
        cancelBridge(notifyPhone = true)
        viewModelScope.launch {
            local.update { it.copy(passwordBusy = true, phoneStepBusy = true, passwordError = null, phoneStepError = null) }
            val loggedOut = withTimeoutOrNull(15_000) { Tdlib.req { logOut() } }?.isSuccess == true
            if (!loggedOut) Tdlib.req { destroy() }
            local.value = Local()
        }
    }

    override fun onCleared() {
        cancelBridge(notifyPhone = true)
        super.onCleared()
    }

    // ---- messaggi --------------------------------------------------------------------------------

    private fun str(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun friendly(e: Throwable): String = when {
        e is TdlException && e.message.contains("PASSWORD_HASH_INVALID") -> str(R.string.auth_error_wrong_password)
        e is TdlException && e.message.contains("FLOOD_WAIT") -> str(R.string.auth_error_flood)
        e is TdlException -> str(R.string.auth_error_generic, e.message)
        else -> str(R.string.auth_error_generic, e.message ?: e.javaClass.simpleName)
    }

    private fun bridgeTokenError(e: Throwable): String = when {
        e is TdlException && (
            e.message.contains("API_ID", ignoreCase = true) ||
                e.message.contains("TOKEN", ignoreCase = true) ||
                e.message.contains("unexpected", ignoreCase = true) ||
                e.message.contains("official", ignoreCase = true)
            ) -> str(R.string.auth_bridge_token_rejected, e.message)
        else -> friendly(e)
    }
}

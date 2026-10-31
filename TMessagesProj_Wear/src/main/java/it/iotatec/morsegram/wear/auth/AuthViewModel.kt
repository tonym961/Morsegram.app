package it.iotatec.morsegram.wear.auth

import android.app.Application
import android.util.Base64
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
import it.iotatec.morsegram.wear.settings.AirplaneMode
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

    /** Il telefono ha chiesto il token: spedito, `auth.acceptLoginToken` in corso lì. */
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

    /** Modalità aereo in-app ancora attiva senza sessione: la schermata iniziale lo dice e offre di spegnerla. */
    val airplane: StateFlow<Boolean> = AirplaneMode.enabled

    fun disableAirplane() {
        viewModelScope.launch { AirplaneMode.set(getApplication(), false) }
    }

    init {
        // Reset quando si entra (login riuscito); e quando TDLib torna a WaitPhoneNumber con un
        // percorso scelto (es. dopo un riavvio del client) il token QR va richiesto di nuovo —
        // serve a entrambi i percorsi, perche' anche il ponte col telefono manda quel token.
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
                    // Anche il percorso telefono passa dal QR: il token che il ponte manda al
                    // telefono e' quello di `requestQrCodeAuthentication`, solo senza inquadrare nulla.
                    if (path != Path.NONE && auth is AuthorizationStateWaitPhoneNumber) requestQr()
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
        // qrError azzerato: nel ramo NoToken viene letto come "motivo vero", quindi non deve
        // sopravvivere dal tentativo precedente.
        local.update { it.copy(path = Path.BRIDGE, bridge = BridgeStep.Searching, qrError = null) }
        val b = WearAuthBridge(getApplication())
        bridge = b
        bridgeJob = viewModelScope.launch {
            try {
                val outcome = b.run(
                    onOfferSent = { local.update { it.copy(bridge = BridgeStep.WaitingPhone) } },
                    onEmojis = { emojis -> local.update { it.copy(bridge = BridgeStep.CompareEmojis(emojis)) } },
                    onAccepting = { local.update { it.copy(bridge = BridgeStep.LoggingIn) } },
                    freshToken = { currentLoginToken() },
                )
                when (outcome) {
                    // In caso di successo TDLib emette Ready (o WaitPassword con la 2FA): la UI segue authState.
                    WearAuthBridge.Outcome.Accepted -> Unit
                    // Il telefono ha chiuso l'attesa: tempo scaduto, non un rifiuto di Telegram, e
                    // soprattutto non un problema della versione dell'app sul telefono.
                    WearAuthBridge.Outcome.PhoneGaveUp -> failBridge(str(R.string.auth_bridge_phone_gave_up))
                    // Foglio chiuso sul telefono prima di confermare, o telefono che non puo'
                    // procedere (nessun account, nessuna finestra): niente attesa di tre minuti.
                    WearAuthBridge.Outcome.PhoneDeclined -> failBridge(str(R.string.auth_bridge_declined))
                    is WearAuthBridge.Outcome.Refused ->
                        // Niente chip QR: il rifiuto viene da `auth.acceptLoginToken`, la stessa
                        // chiamata che farebbe il telefono inquadrando il codice. Resta "Riprova".
                        failBridge(str(R.string.auth_bridge_refused, outcome.message), suggestQr = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failBridge(
                    when (e) {
                        is WearAuthBridge.NoPhoneException -> str(R.string.auth_bridge_no_phone)
                        is WearAuthBridge.TimeoutException -> str(R.string.auth_bridge_timeout)
                        // Se `requestQrCodeAuthentication` ha fallito, il motivo vero e' quello:
                        // dire "nessun codice da passare" nasconderebbe l'errore del server.
                        is WearAuthBridge.NoTokenException ->
                            local.value.qrError ?: str(R.string.auth_bridge_no_token)
                        is WearAuthBridge.ProtocolException -> str(R.string.auth_bridge_protocol_error)
                        else -> str(R.string.auth_error_generic, e.message ?: e.javaClass.simpleName)
                    }
                )
                // Il foglio sul telefono puo' essere ancora aperto ad aspettare un token che non
                // arrivera' mai: chiudiglielo invece di lasciarlo scadere da solo.
                if (b.needsCancel) Tdlib.scope.launch { b.sendCancel() }
            }
        }
    }

    /**
     * Il token di login del momento, per il telefono che ha appena chiesto `/ready`. Lo chiede il
     * ponte **solo** in quell'istante: TDLib lo rinnova ogni pochi secondi riemettendo
     * `WaitOtherDeviceConfirmation` con un link nuovo, e `StateFlow.first` restituisce subito il
     * valore corrente, cioè il più fresco. Null = il percorso QR non è ancora partito.
     */
    private suspend fun currentLoginToken(): ByteArray? {
        val state = withTimeoutOrNull(QR_LINK_TIMEOUT_MS) {
            Tdlib.authState.first { it is AuthorizationStateWaitOtherDeviceConfirmation }
        } as? AuthorizationStateWaitOtherDeviceConfirmation ?: return null
        return tokenFromLink(state.link)
    }

    /**
     * `tg://login?token=<base64url>` → byte grezzi, gli stessi che il telefono mette in
     * `TL_auth_acceptLoginToken.token`. La normalizzazione `/`→`_`, `+`→`-` è quella di
     * `SessionsActivity.processQr` di upstream: TDLib emette già base64url, ma un link con
     * l'alfabeto standard verrebbe decodificato male in silenzio.
     */
    private fun tokenFromLink(link: String): ByteArray? {
        if (!link.startsWith(QR_LINK_PREFIX)) return null
        val code = link.substring(QR_LINK_PREFIX.length).replace('/', '_').replace('+', '-')
        return runCatching { Base64.decode(code, Base64.URL_SAFE) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun failBridge(message: String, suggestQr: Boolean = true) {
        local.update { it.copy(bridge = BridgeStep.Failed(message, suggestQr)) }
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

    private companion object {
        const val QR_LINK_PREFIX = "tg://login?token="

        /**
         * Quanto si aspetta il primo link QR quando il telefono chiede il token. Il percorso QR
         * parte insieme al ponte, quindi di norma il link c'è già; questo copre il caso di una rete
         * lenta sull'orologio. Sta sotto al `RESULT_TIMEOUT_MS` del ponte perché l'attesa qui
         * consuma il tempo che il telefono dà alla risposta.
         */
        const val QR_LINK_TIMEOUT_MS = 20_000L
    }
}

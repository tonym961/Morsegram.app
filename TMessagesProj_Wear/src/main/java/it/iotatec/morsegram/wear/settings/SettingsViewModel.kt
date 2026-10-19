package it.iotatec.morsegram.wear.settings

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.g000sha256.tdl.dto.ConnectionState
import dev.g000sha256.tdl.dto.ConnectionStateConnectingToProxy
import dev.g000sha256.tdl.dto.ConnectionStateReady
import dev.g000sha256.tdl.dto.ConnectionStateUpdating
import dev.g000sha256.tdl.dto.ConnectionStateWaitingForNetwork
import dev.g000sha256.tdl.dto.UpdateUser
import dev.g000sha256.tdl.dto.User
import it.iotatec.morsegram.wear.R
import it.iotatec.morsegram.wear.notify.MessageNotifications
import it.iotatec.morsegram.wear.tdlib.Tdlib
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Intestazione account mostrata in cima alle impostazioni. */
data class AccountInfo(
    val name: String,
    /** Username con la chiocciola (es. `@mario`), `null` se l'utente non ne ha uno. */
    val username: String?,
    /** Numero mascherato (es. `+39 ••• ••• •• 67`), `null` se TDLib non lo espone. */
    val phoneMasked: String?,
)

sealed interface AccountState {
    data object Loading : AccountState
    data object Unavailable : AccountState
    data class Loaded(val info: AccountInfo) : AccountState
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val _account = MutableStateFlow<AccountState>(AccountState.Loading)
    val account: StateFlow<AccountState> = _account.asStateFlow()

    /** Modalità aereo in-app (vedi [AirplaneMode]). */
    val airplane: StateFlow<Boolean> = AirplaneMode.enabled

    private val _notifyPreview = MutableStateFlow(SettingsPrefs.notifyPreview(app))
    val notifyPreview: StateFlow<Boolean> = _notifyPreview.asStateFlow()

    val connection: StateFlow<ConnectionState?> = Tdlib.connectionState

    private val _loggingOut = MutableStateFlow(false)
    val loggingOut: StateFlow<Boolean> = _loggingOut.asStateFlow()

    /** Ultimo errore da mostrare (testo già localizzato da TDLib o messaggio generico). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var myId: Long = 0L

    init {
        // Rete di sicurezza: se il core non ha ancora chiamato applyPersisted all'avvio, lo facciamo qui (idempotente).
        AirplaneMode.applyPersisted(app)
        loadMe()
        viewModelScope.launch {
            Tdlib.updatesOf<UpdateUser>()
                .filter { myId != 0L && it.user.id == myId }
                .collect { _account.value = AccountState.Loaded(it.user.toAccountInfo()) }
        }
    }

    private fun loadMe() {
        viewModelScope.launch {
            Tdlib.req { getMe() }
                .onSuccess { me ->
                    myId = me.id
                    _account.value = AccountState.Loaded(me.toAccountInfo())
                }
                .onFailure { _account.value = AccountState.Unavailable }
        }
    }

    fun setAirplane(on: Boolean) {
        viewModelScope.launch { AirplaneMode.set(getApplication(), on) }
    }

    fun setNotifyPreview(on: Boolean) {
        _notifyPreview.value = on
        SettingsPrefs.setNotifyPreview(getApplication(), on)
        MessageNotifications.refreshAll()
    }

    /**
     * Esce dall'account. `logOut` di TDLib richiede la rete: se la modalità aereo è attiva viene
     * prima disattivata (e persistita a OFF, così il prossimo login non parte offline).
     * Dopo la risposta TDLib passa per `authorizationStateClosed` e il core ricrea il client:
     * la navigazione torna da sola alla schermata di accesso.
     */
    fun logOut() {
        if (_loggingOut.value) return
        _loggingOut.value = true
        _error.value = null
        viewModelScope.launch {
            if (AirplaneMode.enabled.value) AirplaneMode.set(getApplication(), false)
            Tdlib.req { logOut() }.onFailure { e ->
                _loggingOut.value = false
                _error.value = e.message ?: getApplication<Application>().getString(R.string.settings_error_generic)
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}

internal fun User.toAccountInfo(): AccountInfo {
    val name = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
    val uname = usernames?.let { u ->
        u.editableUsername.takeIf { it.isNotBlank() } ?: u.activeUsernames.firstOrNull { it.isNotBlank() }
    }
    return AccountInfo(
        name = name,
        username = uname?.let { "@$it" },
        phoneMasked = maskPhone(phoneNumber),
    )
}

/**
 * Maschera un numero in formato internazionale senza `+` (come lo dà TDLib): restano visibili il
 * prefisso (2 cifre) e le ultime 2 cifre, il resto diventa `•` a gruppi di tre.
 */
internal fun maskPhone(phone: String): String? {
    val digits = phone.filter { it.isDigit() }
    if (digits.isEmpty()) return null
    if (digits.length <= 4) return "+" + "•".repeat(digits.length)
    val hidden = "•".repeat(digits.length - 4).chunked(3).joinToString(" ")
    return "+${digits.take(2)} $hidden ${digits.takeLast(2)}"
}

/** Testo della riga "Stato connessione". La modalità aereo prevale su qualunque stato TDLib. */
@StringRes
internal fun connectionLabelRes(state: ConnectionState?, airplane: Boolean): Int = when {
    airplane -> R.string.settings_conn_airplane
    state is ConnectionStateReady -> R.string.settings_conn_ready
    state is ConnectionStateUpdating -> R.string.settings_conn_updating
    state is ConnectionStateConnectingToProxy -> R.string.settings_conn_proxy
    state is ConnectionStateWaitingForNetwork -> R.string.settings_conn_waiting
    // ConnectionStateConnecting, stato non ancora noto (null) o tipi futuri
    else -> R.string.settings_conn_connecting
}

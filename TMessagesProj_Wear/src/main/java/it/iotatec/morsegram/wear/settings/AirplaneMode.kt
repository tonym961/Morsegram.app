package it.iotatec.morsegram.wear.settings

import android.content.Context
import android.util.Log
import dev.g000sha256.tdl.dto.AuthorizationState
import dev.g000sha256.tdl.dto.AuthorizationStateWaitCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailAddress
import dev.g000sha256.tdl.dto.AuthorizationStateWaitEmailCode
import dev.g000sha256.tdl.dto.AuthorizationStateWaitOtherDeviceConfirmation
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPassword
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPhoneNumber
import dev.g000sha256.tdl.dto.AuthorizationStateWaitPremiumPurchase
import dev.g000sha256.tdl.dto.AuthorizationStateWaitRegistration
import dev.g000sha256.tdl.dto.AuthorizationStateWaitTdlibParameters
import dev.g000sha256.tdl.dto.NetworkTypeNone
import dev.g000sha256.tdl.dto.NetworkTypeOther
import dev.g000sha256.tdl.dto.UpdateAuthorizationState
import it.iotatec.morsegram.wear.tdlib.Tdlib
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Modalità aereo interna all'app: è solo una pausa della connettività di TDLib.
 *
 * ON  -> `setNetworkType(networkTypeNone)`: TDLib smette di usare la rete e passa a
 *        `connectionStateWaitingForNetwork` ("offline").
 * OFF -> `setNetworkType(networkTypeOther)`: TDLib si riconnette da solo.
 *
 * Regola di prodotto: niente viene scartato. Le `viewMessages` (conferme di lettura) e gli invii
 * fatti mentre si è offline restano in coda dentro TDLib e partono normalmente al ritorno online.
 *
 * La scelta è persistita in [SettingsPrefs] e va riapplicata all'avvio con [applyPersisted]
 * (da `MorsegramWearApp.onCreate`, dopo `Tdlib.start`). [applyPersisted] resta in ascolto e
 * riapplica la scelta anche a ogni client TDLib successivo (dopo logout o cambio DC il core ne
 * crea uno nuovo, che riparte da `authorizationStateWaitTdlibParameters`).
 *
 * Senza una sessione (stati `authorizationStateWait*` delle schermate di accesso) la modalità
 * aereo non ha senso e lascerebbe l'accesso "in attesa di rete" per sempre: appena TDLib segnala
 * uno di quegli stati con la modalità attiva, viene spenta e persistita a OFF ([isLoggedOut]);
 * nel frattempo [applyCurrent] non manda mai `networkTypeNone` in quegli stati.
 *
 * Per mostrare un badge "✈ offline" (es. nella lista chat) o l'avviso nella schermata di accesso
 * basta osservare [enabled]; [set] la spegne.
 */
object AirplaneMode {
    private const val TAG = "AirplaneMode"

    private val _enabled = MutableStateFlow(false)
    /** Stato corrente della modalità aereo (aggiornato subito, prima della risposta di TDLib). */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val applyMutex = Mutex()
    private var watchJob: Job? = null
    private lateinit var appContext: Context

    /** Vero negli stati di autorizzazione in cui non c'è una sessione (schermate di accesso). */
    fun isLoggedOut(state: AuthorizationState?): Boolean = when (state) {
        is AuthorizationStateWaitPhoneNumber,
        is AuthorizationStateWaitOtherDeviceConfirmation,
        is AuthorizationStateWaitCode,
        is AuthorizationStateWaitPassword,
        is AuthorizationStateWaitEmailAddress,
        is AuthorizationStateWaitEmailCode,
        is AuthorizationStateWaitRegistration,
        is AuthorizationStateWaitPremiumPurchase -> true
        else -> false
    }

    /** Imposta, persiste e applica la modalità aereo. Sicura da chiamare da qualunque dispatcher. */
    suspend fun set(context: Context, on: Boolean) {
        SettingsPrefs.prefs(context).edit().putBoolean(SettingsPrefs.KEY_AIRPLANE, on).apply()
        _enabled.value = on
        applyCurrent()
    }

    /**
     * Legge la preferenza salvata e la applica al client TDLib corrente (o al primo che nasce) e a
     * tutti i client successivi. Idempotente: la seconda chiamata rilegge solo la preferenza.
     */
    fun applyPersisted(context: Context) {
        appContext = context.applicationContext
        _enabled.value = SettingsPrefs.prefs(context).getBoolean(SettingsPrefs.KEY_AIRPLANE, false)
        synchronized(this) {
            if (watchJob?.isActive == true) return
            watchJob = Tdlib.scope.launch {
                launch {
                    Tdlib.updates
                        // il client esiste già: applica subito (onSubscription evita la corsa con il primo update)
                        .onSubscription { if (Tdlib.authState.value != null) launch { applyCurrent() } }
                        .filterIsInstance<UpdateAuthorizationState>()
                        // primo stato di ogni nuovo client: setNetworkType è accettata anche prima di setTdlibParameters
                        .filter { it.authorizationState is AuthorizationStateWaitTdlibParameters }
                        .collect { launch { applyCurrent() } }
                }
                // Nessuna sessione (avvio con preferenza ON ma account assente, o sessione finita
                // con la modalità attiva): spegni e persisti OFF, altrimenti l'accesso resta offline.
                Tdlib.authState.collect { state ->
                    if (_enabled.value && isLoggedOut(state)) {
                        Log.i(TAG, "nessuna sessione (${state?.javaClass?.simpleName}): modalità aereo disattivata")
                        set(appContext, false)
                    }
                }
            }
        }
    }

    /**
     * Manda a TDLib lo stato desiderato *corrente*; serializzata così l'ultima scelta vince sempre.
     * Senza sessione non manda mai `networkTypeNone` (vedi [isLoggedOut]).
     */
    private suspend fun applyCurrent() = applyMutex.withLock {
        val on = _enabled.value && !isLoggedOut(Tdlib.authState.value)
        Tdlib.req { setNetworkType(if (on) NetworkTypeNone() else NetworkTypeOther()) }
            .onFailure { Log.w(TAG, "setNetworkType(${if (on) "none" else "other"}) non riuscita: ${it.message}") }
    }
}

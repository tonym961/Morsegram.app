package it.iotatec.morsegram.wear

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import dev.g000sha256.tdl.dto.OptionValueBoolean
import it.iotatec.morsegram.wear.tdlib.Tdlib
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Stato "online" dell'account, allineato alla visibilità dell'app.
 *
 * Termini dell'API Telegram (2.2, niente ghost mode): mentre l'utente usa davvero il client deve
 * risultare online e il suo "ultimo accesso" deve aggiornarsi. TDLib non imposta mai da solo
 * l'opzione `online` (parte a false): senza questa classe l'utente dell'orologio non apparirebbe
 * mai online pur leggendo, scrivendo e mandando lo stato "sta registrando".
 *
 * - Ogni activity avviata/fermata ([Application.ActivityLifecycleCallbacks]) tiene un contatore:
 *   0 -> 1 manda `setOption("online", true)`, 1 -> 0 manda `false`. L'offline parte con un breve
 *   ritardo, così l'activity di sistema di tastiera/dettatura (che ferma la nostra per qualche
 *   istante) non fa lampeggiare lo stato.
 * - L'opzione è per client: viene rimandata a ogni nuovo client TDLib (avvio, restart test/prod,
 *   dopo logout) al primo stato di autorizzazione che pubblica, come fa `MessageNotifications`
 *   per le sue opzioni. `setOption` è accettata anche prima di `setTdlibParameters`; finché
 *   l'opzione resta true è TDLib a rinnovare lo stato online da solo.
 *
 * Avvio: [start] da `MorsegramWearApp.onCreate`, prima che esista qualsiasi activity.
 */
object OnlineStatus {
    private const val TAG = "OnlineStatus"

    /** Ritardo prima di dichiararsi offline: assorbe activity -> activity di sistema -> activity. */
    private const val OFFLINE_DELAY_MS = 1_500L

    private val started = AtomicBoolean(false)

    /** Activity in stato started; toccato solo sul main thread (callback di Application). */
    private var startedActivities = 0

    private val _foreground = MutableStateFlow(false)
    /** Vero mentre almeno un'activity dell'app è visibile. */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    /** Registra i callback di lifecycle e l'osservatore del client TDLib. Idempotente. */
    fun start(app: Application) {
        if (!started.compareAndSet(false, true)) return
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (startedActivities++ == 0) _foreground.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) _foreground.value = false
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        Tdlib.scope.launch {
            // false finché il client non ha pubblicato il primo stato; torna false->true a ogni nuovo client
            val hasClient = Tdlib.authState.map { it != null }.distinctUntilChanged()
            combine(hasClient, _foreground) { client, online -> client to online }
                .collectLatest { (client, online) ->
                    if (!client) return@collectLatest
                    if (!online) delay(OFFLINE_DELAY_MS)
                    apply(online)
                }
        }
    }

    private suspend fun apply(online: Boolean) {
        Tdlib.req { setOption("online", OptionValueBoolean(online)) }
            .onSuccess { Log.d(TAG, "online=$online") }
            .onFailure { Log.w(TAG, "setOption(online=$online) non riuscita: ${it.message}") }
    }
}

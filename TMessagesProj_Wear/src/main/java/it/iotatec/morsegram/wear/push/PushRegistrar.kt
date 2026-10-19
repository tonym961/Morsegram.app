package it.iotatec.morsegram.wear.push

import android.content.Context
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import dev.g000sha256.tdl.dto.AuthorizationStateReady
import dev.g000sha256.tdl.dto.DeviceTokenFirebaseCloudMessaging
import it.iotatec.morsegram.wear.tdlib.Tdlib
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Registra il token FCM su TDLib (`registerDevice`) ogni volta che la sessione diventa
 * `authorizationStateReady`, e quando Firebase rinnova il token ([onNewToken]).
 *
 * Idempotente: l'ultimo token registrato è in SharedPreferences e non viene rimandato finché la
 * sessione resta loggata; al logout (o a un nuovo client TDLib) viene dimenticato così il prossimo
 * login lo registra di nuovo. TDLib stesso deduplica i token identici, quindi il costo è nullo.
 *
 * Firebase può non essere configurato (google-services.json segnaposto): ogni chiamata è protetta
 * e in quel caso si logga soltanto; l'app non deve mai crashare per questo.
 */
object PushRegistrar {
    private const val TAG = "PushRegistrar"
    private const val PREFS = "push"
    private const val KEY_TOKEN = "fcm_token"
    private const val KEY_REGISTERED = "registered_token"

    private val started = AtomicBoolean(false)
    private lateinit var app: Context
    private val mutex = Mutex()

    private val prefs get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Avvia l'osservazione dello stato di autorizzazione. Idempotente, non blocca. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        app = context.applicationContext
        Tdlib.scope.launch {
            Tdlib.authState.map { it is AuthorizationStateReady }.distinctUntilChanged().collect { ready ->
                if (ready) registerCurrentToken() else forgetRegistration()
            }
        }
    }

    /** Nuovo token da [MorsegramFcmService.onNewToken]: salvato e, se loggati, registrato subito. */
    fun onNewToken(context: Context, token: String) {
        start(context)
        prefs.edit().putString(KEY_TOKEN, token).apply()
        if (Tdlib.authState.value is AuthorizationStateReady) {
            Tdlib.scope.launch { register(token) }
        } else {
            Log.d(TAG, "token FCM salvato, registrazione rimandata al login")
        }
    }

    private suspend fun registerCurrentToken() {
        val token = prefs.getString(KEY_TOKEN, null) ?: fetchToken() ?: return
        register(token)
    }

    /** Chiede il token a Firebase; null se Firebase non è configurato o non raggiungibile. */
    private suspend fun fetchToken(): String? = try {
        val token = FirebaseMessaging.getInstance().token.await()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "token FCM vuoto")
            null
        } else {
            prefs.edit().putString(KEY_TOKEN, token).apply()
            token
        }
    } catch (e: Exception) {
        Log.w(TAG, "token FCM non disponibile (Firebase non configurato?): ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private suspend fun register(token: String) {
        mutex.withLock {
            if (Tdlib.authState.value !is AuthorizationStateReady) return
            if (prefs.getString(KEY_REGISTERED, null) == token) {
                Log.d(TAG, "token già registrato")
                return
            }
            Tdlib.req { registerDevice(DeviceTokenFirebaseCloudMessaging(token, true), longArrayOf()) }
                .onSuccess {
                    prefs.edit().putString(KEY_REGISTERED, token).apply()
                    Log.i(TAG, "registerDevice ok (push receiver id ${it.id})")
                }
                .onFailure { Log.w(TAG, "registerDevice fallita: ${it.message}") }
        }
    }

    private fun forgetRegistration() {
        prefs.edit().remove(KEY_REGISTERED).apply()
    }
}

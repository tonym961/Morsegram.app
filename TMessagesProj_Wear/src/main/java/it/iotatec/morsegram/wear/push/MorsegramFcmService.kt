package it.iotatec.morsegram.wear.push

import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import it.iotatec.morsegram.wear.notify.MessageNotifications
import it.iotatec.morsegram.wear.tdlib.TdlException
import it.iotatec.morsegram.wear.tdlib.Tdlib
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Riceve i push FCM e li passa a TDLib (`processPushNotification`), che scarica i messaggi e
 * produce le notifiche (vedi [MessageNotifications]). Il payload atteso da TDLib è la mappa
 * `data` del push serializzata come oggetto JSON.
 *
 * Il servizio può essere il primo componente del processo: [MessageNotifications.ensureStarted]
 * è chiamato in [onCreate]. `onMessageReceived` gira su un thread di lavoro di FCM: si attende
 * TDLib bloccando quel thread (con timeout) e tenendo un wake lock breve, così il processo resta
 * vivo finché la notifica è stata elaborata.
 *
 * Con google-services.json segnaposto Firebase non consegna nulla e può loggare errori: nessuna
 * chiamata qui può far crashare l'app.
 */
class MorsegramFcmService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        try {
            MessageNotifications.ensureStarted(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "init notifiche: ${e.message}", e)
        }
    }

    override fun onNewToken(token: String) {
        Log.i(TAG, "nuovo token FCM")
        try {
            PushRegistrar.onNewToken(applicationContext, token)
        } catch (e: Exception) {
            Log.e(TAG, "onNewToken: ${e.message}", e)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data.isEmpty()) {
            Log.d(TAG, "push senza data: ignorato")
            return
        }
        val payload = try {
            JSONObject(data as Map<*, *>).toString()
        } catch (e: Exception) {
            Log.w(TAG, "payload push non serializzabile: ${e.message}")
            return
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // non dovrebbe succedere (FCM usa un executor), ma mai bloccare il main thread
            Tdlib.scope.launch { withWakeLock { process(payload) } }
            return
        }
        runBlocking { withWakeLock { process(payload) } }
    }

    private suspend fun withWakeLock(block: suspend () -> Unit) {
        val wakeLock = try {
            getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "morsegram:push")?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "wake lock: ${e.message}")
            null
        }
        try {
            block()
        } finally {
            try {
                if (wakeLock?.isHeld == true) wakeLock.release()
            } catch (e: Exception) {
                Log.w(TAG, "wake lock release: ${e.message}")
            }
        }
    }

    private suspend fun process(payload: String) {
        try {
            val done = withTimeoutOrNull(PROCESS_TIMEOUT_MS) {
                // il client TDLib viene creato in modo asincrono da Tdlib.start(): si aspetta il primo stato
                Tdlib.authState.filterNotNull().first()
                val r = Tdlib.req { processPushNotification(payload) }
                r.onSuccess { Log.i(TAG, "push elaborato da TDLib") }
                r.onFailure { e ->
                    if (e is TdlException && e.code == 406) {
                        Log.i(TAG, "push non gestibile offline (406): TDLib sincronizza alla prossima connessione")
                    } else {
                        Log.w(TAG, "processPushNotification: ${e.message}")
                    }
                }
                true
            }
            if (done == null) Log.w(TAG, "processPushNotification: timeout dopo ${PROCESS_TIMEOUT_MS} ms")
        } catch (e: Exception) {
            Log.e(TAG, "push: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "MorsegramFcm"
        private const val WAKE_LOCK_MS = 20_000L
        private const val PROCESS_TIMEOUT_MS = 15_000L
    }
}

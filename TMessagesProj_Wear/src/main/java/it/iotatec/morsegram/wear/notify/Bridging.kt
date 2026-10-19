package it.iotatec.morsegram.wear.notify

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log

/**
 * Bridging delle notifiche telefono → orologio.
 *
 * L'app Wear è standalone e mostra da sé le notifiche di TDLib: le notifiche dell'app telefono
 * (stesso package) NON devono essere replicate sull'orologio, altrimenti arrivano doppie.
 *
 * `BridgingManager`/`BridgingConfig` vivono in `androidx.wear:wear-phone-interactions`, che non è
 * tra le dipendenze del modulo (`androidx.wear:wear` 1.3.0 non li contiene). Si usa quindi la
 * configurazione statica documentata da Android, equivalente a `BridgingConfig.Builder(ctx, false)`:
 *
 * ```xml
 * <meta-data android:name="com.google.android.wearable.notificationBridgeMode" android:value="NO_BRIDGING" />
 * ```
 *
 * dichiarata nell'AndroidManifest dell'app Wear. Wear OS la legge all'installazione; questo oggetto
 * si limita a verificarla all'avvio e a segnalare nel log se manca, così una modifica accidentale del
 * manifest non passa inosservata. Le notifiche generate dall'orologio sono inoltre `setLocalOnly(true)`.
 */
object Bridging {
    private const val TAG = "Bridging"

    /** Chiave del meta-data riconosciuta da Wear OS. */
    const val META_BRIDGE_MODE = "com.google.android.wearable.notificationBridgeMode"

    /** Valore che disattiva il bridging per questo package. */
    const val NO_BRIDGING = "NO_BRIDGING"

    @Volatile
    private var checked = false

    /** Da chiamare una volta all'avvio (lo fa [MessageNotifications.ensureStarted]). Idempotente, mai lancia. */
    fun configure(context: Context) {
        if (checked) return
        checked = true
        val mode = declaredMode(context)
        if (mode == NO_BRIDGING) {
            Log.i(TAG, "bridging telefono→orologio disattivato dal manifest ($META_BRIDGE_MODE=$mode)")
        } else {
            Log.w(TAG, "manifest senza $META_BRIDGE_MODE=$NO_BRIDGING (trovato: $mode): le notifiche del telefono arriverebbero doppie")
        }
    }

    /** Vero se il manifest dichiara il bridging disattivato. */
    fun isBridgingDisabled(context: Context): Boolean = declaredMode(context) == NO_BRIDGING

    private fun declaredMode(context: Context): String? = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(context.packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        }
        info.metaData?.getString(META_BRIDGE_MODE)
    } catch (e: Exception) {
        Log.w(TAG, "lettura meta-data fallita: ${e.message}")
        null
    }
}

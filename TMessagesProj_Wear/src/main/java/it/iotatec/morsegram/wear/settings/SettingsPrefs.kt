package it.iotatec.morsegram.wear.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Preferenze utente dell'app Wear (file = default shared preferences, `<package>_preferences`,
 * lo stesso che restituisce `PreferenceManager.getDefaultSharedPreferences`).
 *
 * Chiavi lette anche da altre feature:
 *  - [KEY_NOTIFY_PREVIEW] (Boolean, default `true`): mostrare il testo del messaggio nelle
 *    notifiche. Il modulo notifiche lo legge con [notifyPreview].
 *  - [KEY_AIRPLANE] (Boolean, default `false`): modalità aereo in-app, gestita da [AirplaneMode].
 */
object SettingsPrefs {
    const val KEY_NOTIFY_PREVIEW = "notify_preview"
    const val KEY_AIRPLANE = "airplane_mode"

    fun prefs(context: Context): SharedPreferences {
        val app = context.applicationContext
        return app.getSharedPreferences("${app.packageName}_preferences", Context.MODE_PRIVATE)
    }

    /** Vero se le notifiche devono mostrare l'anteprima del messaggio (default: sì). */
    fun notifyPreview(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFY_PREVIEW, true)

    fun setNotifyPreview(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_PREVIEW, on).apply()
    }
}

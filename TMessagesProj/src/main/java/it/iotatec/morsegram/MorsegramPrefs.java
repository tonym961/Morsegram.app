/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * SharedPreferences di Morsegram.
 * <ul>
 * <li>{@link #global()}: file "morsegram", impostazioni app-wide (modalità aereo, foldable, vocali, perf, PIN chat nascoste).</li>
 * <li>{@link #account(int)}: file "morsegram_u&lt;userId&gt;", impostazioni legate all'utente loggato nello slot
 *     (chiave per user id, non per slot: un altro utente nello stesso slot non eredita nulla, nessun cleanup su logout).</li>
 * </ul>
 * Null-safe: senza {@code ApplicationLoader.applicationContext} (o con userId 0) restituisce uno stub vuoto che
 * legge i default e non persiste nulla. Le letture calde (comparatore, isNetworkOnline) NON passano da qui ma da
 * cache volatile nelle classi che le usano.
 */
public final class MorsegramPrefs {

    public static final String GLOBAL_FILE = "morsegram";
    public static final String ACCOUNT_FILE_PREFIX = "morsegram_u";

    /* namespace chiavi: global */
    public static final String KEY_AIRPLANE_ON = "airplane.on";
    public static final String KEY_AIRPLANE_SINCE = "airplane.since";
    public static final String KEY_AIRPLANE_TILE_PROMPTED = "airplane.tilePrompted";
    public static final String KEY_FOLD_TWO_PANES = "fold.twoPanes";
    public static final String KEY_FOLD_HINGE_SPLIT = "fold.hingeSplit";
    public static final String KEY_PERF_LOG = "perf.log";
    /* namespace chiavi: global (identita' visiva, it.iotatec.morsegram.theme.MorsegramTheme) */
    public static final String KEY_THEME_APPLIED = "theme.applied";
    /* namespace chiavi: global (S4 trascrizione vocali on-device, it.iotatec.morsegram.voice.VoiceSettings) */
    public static final String KEY_VOICE_MODE = "voice.mode";
    public static final String KEY_VOICE_LANG = "voice.lang";
    public static final String KEY_VOICE_ALLOW_SYSTEM = "voice.allowSystem";
    public static final String KEY_VOICE_PROBE = "voice.probe";
    /* namespace chiavi: global (S6 chat nascoste: PIN Morsegram device-wide, separato dal passcode Telegram) */
    public static final String KEY_HIDDEN_PIN_SALT = "hidden.pin.salt";
    public static final String KEY_HIDDEN_PIN_HASH = "hidden.pin.hash";
    public static final String KEY_HIDDEN_PIN_LEN = "hidden.pin.len";
    public static final String KEY_HIDDEN_PIN_TRIES = "hidden.pin.tries";
    public static final String KEY_HIDDEN_PIN_RETRY_AT = "hidden.pin.retryAt";
    public static final String KEY_HIDDEN_BIOMETRIC = "hidden.bio";
    public static final String KEY_HIDDEN_LOCK_TIMEOUT = "hidden.lock.timeout";
    /* namespace chiavi: account (S1 pin locali: CSV di dialog id, ordine = ordine di visualizzazione) */
    public static final String KEY_PINS_ORDER = "pins.order";
    /* namespace chiavi: account (S6 chat nascoste: CSV di dialog id nascosti per l'utente dello slot) */
    public static final String KEY_HIDDEN_IDS = "hidden.ids";

    private MorsegramPrefs() {
    }

    public static SharedPreferences global() {
        final Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return Empty.INSTANCE;
        }
        try {
            return ctx.getSharedPreferences(GLOBAL_FILE, Context.MODE_PRIVATE);
        } catch (Throwable ignore) {
            return Empty.INSTANCE;
        }
    }

    public static SharedPreferences account(int account) {
        final Context ctx = ApplicationLoader.applicationContext;
        final long userId = userId(account);
        if (ctx == null || userId == 0) {
            return Empty.INSTANCE;
        }
        try {
            return ctx.getSharedPreferences(ACCOUNT_FILE_PREFIX + userId, Context.MODE_PRIVATE);
        } catch (Throwable ignore) {
            return Empty.INSTANCE;
        }
    }

    /** user id dello slot, 0 se lo slot è vuoto o non ancora inizializzato. */
    public static long userId(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return 0;
        }
        try {
            return UserConfig.getInstance(account).getClientUserId();
        } catch (Throwable ignore) {
            return 0;
        }
    }

    /** Stub immutabile: le letture restituiscono i default, le scritture sono no-op. */
    static final class Empty implements SharedPreferences, SharedPreferences.Editor {
        static final Empty INSTANCE = new Empty();

        private Empty() {
        }

        @Override public Map<String, ?> getAll() { return Collections.emptyMap(); }
        @Override public String getString(String key, String defValue) { return defValue; }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { return defValues; }
        @Override public int getInt(String key, int defValue) { return defValue; }
        @Override public long getLong(String key, long defValue) { return defValue; }
        @Override public float getFloat(String key, float defValue) { return defValue; }
        @Override public boolean getBoolean(String key, boolean defValue) { return defValue; }
        @Override public boolean contains(String key) { return false; }
        @Override public Editor edit() { return this; }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }

        @Override public Editor putString(String key, String value) { return this; }
        @Override public Editor putStringSet(String key, Set<String> values) { return this; }
        @Override public Editor putInt(String key, int value) { return this; }
        @Override public Editor putLong(String key, long value) { return this; }
        @Override public Editor putFloat(String key, float value) { return this; }
        @Override public Editor putBoolean(String key, boolean value) { return this; }
        @Override public Editor remove(String key) { return this; }
        @Override public Editor clear() { return this; }
        @Override public boolean commit() { return false; }
        @Override public void apply() { }
    }
}

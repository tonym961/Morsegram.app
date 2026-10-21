/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.voice;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.messenger.LocaleController;

import java.util.Locale;

import it.iotatec.morsegram.MorsegramPrefs;

/**
 * Preferenze della trascrizione vocali on-device (mission 5), su {@link MorsegramPrefs#global()}:
 * <ul>
 * <li>{@code voice.mode}: {@link #MODE_AUTO} (locale per chi non è Premium, server Telegram per i Premium, default),
 *     {@link #MODE_LOCAL} (sempre locale), {@link #MODE_TELEGRAM} (solo il percorso upstream).</li>
 * <li>{@code voice.lang}: tag BCP-47 forzato ("it-IT"), oppure "" = lingua dell'app Telegram.</li>
 * <li>{@code voice.allowSystem}: consente il riconoscitore di sistema (createSpeechRecognizer + EXTRA_PREFER_OFFLINE)
 *     quando manca quello on-device: NON garantisce l'elaborazione offline, quindi opt-in (default false).</li>
 * <li>{@code voice.probe}: esito della probe di sicurezza ({@code <chiave>=ok|ko}), gestita da {@link LocalTranscriber}.</li>
 * <li>{@code voice.lockMode}: blocca la modalità di registrazione sul vocale, così il tocco breve sul microfono
 *     non passa più al videomessaggio (vedi {@link #recordModeLocked()}). Non c'entra con la trascrizione, ma
 *     tiene tutte le preferenze {@code voice.*} in un posto solo.</li>
 * </ul>
 * Letture con cache volatile: {@link LocalTranscriber#canTranscribe} è nel percorso di bind di ogni bolla vocale.
 * Le righe della pagina 'Morsegram' (sezione Vocali) sono descritte nei getter/setter qui sotto; la pagina stessa è
 * {@code MorsegramSettingsActivity} (file di un altro agente).
 */
public final class VoiceSettings {

    public static final int MODE_AUTO = 0;
    public static final int MODE_LOCAL = 1;
    public static final int MODE_TELEGRAM = 2;

    /** Lingue proposte nel selettore; "" = lingua dell'app. */
    public static final String[] LANGUAGE_TAGS = {
            "", "it-IT", "en-US", "en-GB", "de-DE", "fr-FR", "es-ES", "pt-BR", "pt-PT", "nl-NL", "ru-RU", "uk-UA", "pl-PL",
            "tr-TR", "ar-SA", "hi-IN", "ja-JP", "ko-KR", "zh-CN"
    };

    private static volatile int mode = -1;
    private static volatile String language;
    private static volatile Boolean allowSystem;
    private static volatile Boolean lockRecordMode;

    private VoiceSettings() {
    }

    /* ------------------------------------------------------------------------------------------ modalità */

    public static int mode() {
        int v = mode;
        if (v < 0) {
            v = MorsegramPrefs.global().getInt(MorsegramPrefs.KEY_VOICE_MODE, MODE_AUTO);
            if (v < MODE_AUTO || v > MODE_TELEGRAM) {
                v = MODE_AUTO;
            }
            mode = v;
        }
        return v;
    }

    public static void setMode(int m) {
        if (m < MODE_AUTO || m > MODE_TELEGRAM) {
            m = MODE_AUTO;
        }
        mode = m;
        MorsegramPrefs.global().edit().putInt(MorsegramPrefs.KEY_VOICE_MODE, m).apply();
        LocalTranscriber.onSettingsChanged();
    }

    /** Etichette per AlertsCreator.createSingleChoiceDialog, indice = modalità. */
    public static String[] modeLabels() {
        return new String[]{modeLabel(MODE_AUTO), modeLabel(MODE_LOCAL), modeLabel(MODE_TELEGRAM)};
    }

    public static String modeLabel(int m) {
        switch (m) {
            case MODE_LOCAL:
                return LocaleController.getString("MorsegramVoiceModeLocal");
            case MODE_TELEGRAM:
                return LocaleController.getString("MorsegramVoiceModeTelegram");
            default:
                return LocaleController.getString("MorsegramVoiceModeAuto");
        }
    }

    /* --------------------------------------------------------------- blocco della modalità di registrazione */

    /**
     * Vero se il tocco breve sul microfono NON deve commutare fra vocale e videomessaggio.
     *
     * <p>Upstream un tocco breve sul pulsante del microfono è uno scambio di modalità: chi registra molti
     * vocali si ritrova in modalità videomessaggio per un tocco finito male. Con questa preferenza attiva
     * l'aggancio in {@code ChatActivityEnterView} consuma il tocco e la modalità resta quella che è.
     *
     * <p>Letta nel percorso del tocco (non in un {@code onDraw}): cache volatile, come le altre qui.
     */
    public static boolean recordModeLocked() {
        Boolean v = lockRecordMode;
        if (v == null) {
            v = MorsegramPrefs.global().getBoolean(MorsegramPrefs.KEY_VOICE_LOCK_MODE, false);
            lockRecordMode = v;
        }
        return v;
    }

    /**
     * Accendendo il blocco si rimette anche la modalità corrente sul vocale, scrivendo le preferenze di
     * upstream ({@code currentModeVideo} e {@code currentModeVideoChannel} in
     * {@code MessagesController.getGlobalMainSettings()}, le stesse che {@code ChatActivityEnterView} rilegge
     * all'apertura di ogni chat; nei canali il default upstream è il videomessaggio). Senza questo, chi
     * accende l'interruttore mentre la barra è in modalità video resterebbe bloccato sul video, cioè
     * l'opposto di quello che ha chiesto. Nessuna riga upstream: sono API pubbliche.
     */
    public static void setRecordModeLocked(boolean locked) {
        lockRecordMode = locked;
        MorsegramPrefs.global().edit().putBoolean(MorsegramPrefs.KEY_VOICE_LOCK_MODE, locked).apply();
        if (locked) {
            try {
                org.telegram.messenger.MessagesController.getGlobalMainSettings().edit()
                        .putBoolean("currentModeVideo", false)
                        .putBoolean("currentModeVideoChannel", false)
                        .apply();
            } catch (Throwable ignore) {
            }
        }
    }

    /* -------------------------------------------------------------------------------------------- lingua */

    /** Tag forzato oppure "" (lingua dell'app). */
    public static String language() {
        String v = language;
        if (v == null) {
            v = MorsegramPrefs.global().getString(MorsegramPrefs.KEY_VOICE_LANG, "");
            if (v == null) {
                v = "";
            }
            language = v;
        }
        return v;
    }

    public static void setLanguage(String tag) {
        if (tag == null) {
            tag = "";
        }
        language = tag;
        MorsegramPrefs.global().edit().putString(MorsegramPrefs.KEY_VOICE_LANG, tag).apply();
        LocalTranscriber.onSettingsChanged();
    }

    /** Tag effettivamente usato: override, altrimenti lingua dell'app normalizzata a lingua-REGIONE. */
    public static String effectiveLanguageTag() {
        final String override = language();
        if (!TextUtils.isEmpty(override)) {
            return override;
        }
        Locale appLocale = null;
        try {
            appLocale = LocaleController.getInstance().getCurrentLocale();
        } catch (Throwable ignore) {
        }
        if (appLocale == null) {
            appLocale = Locale.getDefault();
        }
        return normalize(appLocale.toLanguageTag());
    }

    /** "it" -> "it-IT", "en" -> "en-US", ...: i modelli offline sono installati per lingua-REGIONE. */
    static String normalize(String tag) {
        if (TextUtils.isEmpty(tag)) {
            return "en-US";
        }
        if (tag.indexOf('-') >= 0) {
            return tag;
        }
        final Locale def = Locale.getDefault();
        if (tag.equalsIgnoreCase(def.getLanguage()) && !TextUtils.isEmpty(def.getCountry())) {
            return tag + "-" + def.getCountry();
        }
        switch (tag.toLowerCase(Locale.ROOT)) {
            case "it": return "it-IT";
            case "en": return "en-US";
            case "de": return "de-DE";
            case "fr": return "fr-FR";
            case "es": return "es-ES";
            case "pt": return "pt-BR";
            case "nl": return "nl-NL";
            case "ru": return "ru-RU";
            case "uk": return "uk-UA";
            case "pl": return "pl-PL";
            case "tr": return "tr-TR";
            case "ja": return "ja-JP";
            case "ko": return "ko-KR";
            case "zh": return "zh-CN";
            case "ar": return "ar-SA";
            case "hi": return "hi-IN";
            default: return tag;
        }
    }

    /** Valore della riga 'Lingua': nome della lingua forzata o 'Lingua dell'app (it-IT)'. */
    public static String languageLabel() {
        final String override = language();
        if (TextUtils.isEmpty(override)) {
            return LocaleController.formatString("MorsegramVoiceLanguageApp", 0, effectiveLanguageTag());
        }
        return displayName(override);
    }

    public static String displayName(String tag) {
        try {
            final String name = Locale.forLanguageTag(tag).getDisplayName();
            if (!TextUtils.isEmpty(name)) {
                return Character.toUpperCase(name.charAt(0)) + name.substring(1);
            }
        } catch (Throwable ignore) {
        }
        return tag;
    }

    /** Etichette per il selettore (stesso ordine di {@link #LANGUAGE_TAGS}). */
    public static String[] languageChoiceLabels() {
        final String[] labels = new String[LANGUAGE_TAGS.length];
        for (int i = 0; i < LANGUAGE_TAGS.length; i++) {
            labels[i] = TextUtils.isEmpty(LANGUAGE_TAGS[i]) ? LocaleController.formatString("MorsegramVoiceLanguageApp", 0, normalize(appLanguageTag())) : displayName(LANGUAGE_TAGS[i]);
        }
        return labels;
    }

    private static String appLanguageTag() {
        try {
            final Locale l = LocaleController.getInstance().getCurrentLocale();
            if (l != null) {
                return l.toLanguageTag();
            }
        } catch (Throwable ignore) {
        }
        return Locale.getDefault().toLanguageTag();
    }

    /** Indice selezionato nel selettore (0 = lingua dell'app; -1 se il tag forzato non è tra i proposti). */
    public static int languageChoiceIndex() {
        final String cur = language();
        for (int i = 0; i < LANGUAGE_TAGS.length; i++) {
            if (LANGUAGE_TAGS[i].equalsIgnoreCase(cur)) {
                return i;
            }
        }
        return TextUtils.isEmpty(cur) ? 0 : -1;
    }

    public static void setLanguageChoice(int index) {
        if (index >= 0 && index < LANGUAGE_TAGS.length) {
            setLanguage(LANGUAGE_TAGS[index]);
        }
    }

    /* ------------------------------------------------------------------------- riconoscitore di sistema */

    public static boolean allowSystem() {
        Boolean v = allowSystem;
        if (v == null) {
            v = MorsegramPrefs.global().getBoolean(MorsegramPrefs.KEY_VOICE_ALLOW_SYSTEM, false);
            allowSystem = v;
        }
        return v;
    }

    public static void setAllowSystem(boolean on) {
        allowSystem = on;
        MorsegramPrefs.global().edit().putBoolean(MorsegramPrefs.KEY_VOICE_ALLOW_SYSTEM, on).apply();
        LocalTranscriber.onSettingsChanged();
    }

    /* ---------------------------------------------------------------------------------------- stato */

    /** Valore della riga di stato (motore on-device / di sistema / non disponibile / verifica in corso). */
    public static String probeStatus() {
        return LocalTranscriber.statusText();
    }

    static String probe() {
        final String v = MorsegramPrefs.global().getString(MorsegramPrefs.KEY_VOICE_PROBE, "");
        return v == null ? "" : v;
    }

    static void setProbe(String value) {
        final SharedPreferences.Editor e = MorsegramPrefs.global().edit();
        if (TextUtils.isEmpty(value)) {
            e.remove(MorsegramPrefs.KEY_VOICE_PROBE);
        } else {
            e.putString(MorsegramPrefs.KEY_VOICE_PROBE, value);
        }
        e.apply();
    }
}

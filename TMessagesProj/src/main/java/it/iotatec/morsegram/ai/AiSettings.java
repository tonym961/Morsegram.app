/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import org.telegram.messenger.LocaleController;

import it.iotatec.morsegram.MorsegramPrefs;

/**
 * Impostazioni dell'assistente che NON sono segreti: interruttore generale e scelta del fornitore principale e di
 * ripiego. Stanno in {@code MorsegramPrefs.global()}; le chiavi API stanno cifrate in {@link AiKeys}.
 *
 * <p>Default: assistente SPENTO, principale Groq, nessun ripiego. Spento di default è un requisito, non una
 * preferenza: finché l'utente non accende l'interruttore e non accetta l'anteprima su una chat, nessun testo esce
 * dal dispositivo.</p>
 *
 * <p>Cambiare fornitore cambia il DESTINATARIO del testo, quindi {@link #setPrimary} e {@link #setFallback}
 * dimenticano le accettazioni dell'anteprima ({@link AiGate#forgetPreviews()}): l'anteprima nomina i destinatari,
 * e un sì dato per Groq non è un sì per Google.</p>
 */
public final class AiSettings {

    private AiSettings() {
    }

    /** Interruttore generale. Spento di default. */
    public static boolean enabled() {
        return MorsegramPrefs.global().getBoolean(MorsegramPrefs.KEY_AI_ENABLED, false);
    }

    public static void setEnabled(boolean on) {
        MorsegramPrefs.global().edit().putBoolean(MorsegramPrefs.KEY_AI_ENABLED, on).apply();
        AiGate.notifyChanged();
    }

    public static int primary() {
        final int id = MorsegramPrefs.global().getInt(MorsegramPrefs.KEY_AI_PRIMARY, AiProvider.GROQ);
        return id == AiProvider.GEMINI ? AiProvider.GEMINI : AiProvider.GROQ;
    }

    public static void setPrimary(int providerId) {
        final int next = providerId == AiProvider.GEMINI ? AiProvider.GEMINI : AiProvider.GROQ;
        final boolean changed = next != primary();
        MorsegramPrefs.global().edit().putInt(MorsegramPrefs.KEY_AI_PRIMARY, next).apply();
        if (changed) {
            AiGate.forgetPreviews();
        }
    }

    /** Fornitore di ripiego, o {@link AiProvider#NONE}. */
    public static int fallback() {
        final int id = MorsegramPrefs.global().getInt(MorsegramPrefs.KEY_AI_FALLBACK, AiProvider.NONE);
        if (id != AiProvider.GROQ && id != AiProvider.GEMINI) {
            return AiProvider.NONE;
        }
        return id == primary() ? AiProvider.NONE : id;
    }

    public static void setFallback(int providerId) {
        final boolean changed = providerId != fallback();
        MorsegramPrefs.global().edit().putInt(MorsegramPrefs.KEY_AI_FALLBACK, providerId).apply();
        if (changed) {
            AiGate.forgetPreviews();
        }
    }

    /** Se c'è almeno una chiave salvata: senza chiavi l'assistente non può fare nulla. */
    /**
     * Modello scelto dall'utente per un fornitore, vuoto = quello predefinito.
     *
     * <p>I fornitori ritirano i modelli ogni pochi mesi: Groq ha spento
     * llama-3.3-70b-versatile il 16/08/2026 e Google gemini-2.0-flash il 01/06/2026. Con il nome
     * cablato nel codice l'assistente smette di funzionare e serve una nuova versione dell'app;
     * lasciandolo cambiare dalle impostazioni, l'utente se la cava da solo il giorno stesso.
     */
    public static String model(int providerId) {
        return MorsegramPrefs.global().getString("ai.model." + providerId, "");
    }

    public static void setModel(int providerId, String model) {
        final String v = model == null ? "" : model.trim();
        MorsegramPrefs.global().edit().putString("ai.model." + providerId, v).apply();
    }

    public static boolean hasAnyKey() {
        return AiKeys.hasKey(AiProvider.GROQ) || AiKeys.hasKey(AiProvider.GEMINI);
    }

    /** Pronto all'uso: interruttore acceso e chiave presente per il fornitore principale. */
    public static boolean ready() {
        return enabled() && AiKeys.hasKey(primary());
    }

    /* ======================================================================================= righe */

    public static String[] providerLabels() {
        return new String[]{AiProvider.labelOf(AiProvider.GROQ), AiProvider.labelOf(AiProvider.GEMINI)};
    }

    /** Etichette per la scelta del ripiego: la prima voce è "nessuno". */
    public static String[] fallbackLabels() {
        return new String[]{
                LocaleController.getString("MorsegramAiFallbackNone"),
                AiProvider.labelOf(AiProvider.GROQ),
                AiProvider.labelOf(AiProvider.GEMINI)
        };
    }

    public static int fallbackIndex() {
        final int id = fallback();
        return id == AiProvider.GROQ ? 1 : id == AiProvider.GEMINI ? 2 : 0;
    }

    public static int fallbackIdForIndex(int index) {
        return index == 1 ? AiProvider.GROQ : index == 2 ? AiProvider.GEMINI : AiProvider.NONE;
    }

    /** Valore della riga di una chiave: mai la chiave, solo se c'è. */
    public static String keyStatus(int providerId) {
        return LocaleController.getString(AiKeys.hasKey(providerId) ? "MorsegramAiKeySet" : "MorsegramAiKeyNotSet");
    }
}

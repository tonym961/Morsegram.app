/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import org.telegram.messenger.BuildConfig;

/**
 * Gate compile-time di Morsegram: costanti {@code static final} derivate dai {@code buildConfigField} di
 * TMessagesProj/build.gradle, così R8 in full mode elimina i rami morti.
 * {@code ./gradlew ... -PMORSEGRAM_PERF=off} produce la build A/B per le misure di performance.
 * I toggle UTENTE (runtime) non stanno qui ma in {@link MorsegramPrefs}.
 */
public final class MorsegramGates {

    /** Interruttore generale delle ottimizzazioni "velocissimo" (mission 1). */
    public static final boolean PERF = BuildConfig.MORSEGRAM_PERF;

    /** Niente pack sticker regalo (Premium/Stars/TON) all'avvio: MediaDataController.checkPremiumGiftStickers/checkTonGiftStickers. */
    public static final boolean GIFTS_HIDDEN = PERF;
    /**
     * Niente bind Play Billing al cold start. NOTA: l'hook in BuildVars.useInvoiceBilling() antepone
     * {@code IS_BILLING_UNAVAILABLE} (policy zero-IAP del fork, sempre true), non questa costante: la build A/B
     * {@code -PMORSEGRAM_PERF=off} resta quindi in invoice mode. Costante tenuta per gli hook futuri (S2-B/feature hiding).
     */
    public static final boolean BILLING_OFF = PERF;
    /** RISERVATA: hook differiti al patch feature-hiding (StoriesController). */
    public static final boolean STORIES_HIDDEN = PERF;
    /** RISERVATA: hook differiti al patch feature-hiding (AppGlobalConfig wallet_available). */
    public static final boolean WALLET_HIDDEN = PERF;
    /** Differita a misura fatta (ApplicationLoader). */
    public static final boolean SKIP_EMPTY_ACCOUNTS = false;
    /** Log prestazioni (StartupReporter / logcat locale, tag MorsegramPerf), solo nelle build di debug; a runtime anche con MorsegramPrefs.KEY_PERF_LOG. */
    public static final boolean PERF_LOG = PERF && BuildConfig.DEBUG_VERSION;

    /**
     * Modello dell'assistente SUL DISPOSITIVO (ML Kit Prompt API su Gemini Nano via AICore):
     * {@code -PMORSEGRAM_LOCALAI=on|off}, acceso di default nell'edizione sideload e spento in quella per Google
     * Play (vedi {@link MorsegramEdition}, e {@code -PMORSEGRAM_LOCALAI=on} per accenderlo anche là).
     *
     * <p>Perché compile-time e non una preferenza: misurato sulla pipeline di release vera, la dipendenza
     * {@code com.google.mlkit:genai-prompt} costa +178 KB nell'APK quando il codice la usa, e +11 KB quando è
     * dichiarata ma non referenziata — R8 in full mode la elimina interamente, mapping.txt non contiene una riga
     * {@code mlkit.genai} e i dex restano identici al baseline. Con questa costante a {@code false} l'edizione che
     * non vuole il modello locale non paga quasi niente e il codice è <b>inerte</b>, non solo nascosto, come
     * pretende il javadoc di {@link MorsegramEdition}.</p>
     *
     * <p><b>Dove il gate NON arriva: il manifest.</b> La dipendenza {@code genai-prompt} è dichiarata senza
     * condizioni in {@code TMessagesProj/build.gradle} (il gate è solo un {@code buildConfigField}), quindi il
     * manifest FUSO conserva comunque le due voci dell'AAR anche con {@code -PMORSEGRAM_LOCALAI=off}:
     * {@code uses-permission com.google.android.apps.aicore.service.BIND_SERVICE} e la {@code <queries>} su
     * {@code com.google.android.aicore} (verificato il 21/10/2026 sul merged_manifest di afatDebug). Nell'edizione
     * per Play il codice non fa niente, ma la scheda dello store mostrerebbe un permesso per una funzione che non
     * c'è: va chiuso prima di pubblicare là, con un secondo manifest condizionato alla stessa proprietà
     * ({@code tools:node="remove"} sulle due voci). Vedi il punto 14 della checklist di MORSEGRAM.md.</p>
     */
    public static final boolean LOCAL_AI = BuildConfig.MORSEGRAM_LOCALAI;

    private MorsegramGates() {
    }

    /** Pronto per AppGlobalConfig.ConfigBoolean: per ora restituisce il valore upstream invariato. */
    public static boolean configBoolean(String key, boolean value) {
        return value;
    }
}

/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import org.telegram.messenger.BuildConfig;

/**
 * Gate compile-time di Morsegram: costanti {@code static final} derivate da {@code BuildConfig.MORSEGRAM_PERF}
 * ({@code buildConfigField} in TMessagesProj/build.gradle), così R8 in full mode elimina i rami morti.
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

    private MorsegramGates() {
    }

    /** Pronto per AppGlobalConfig.ConfigBoolean: per ora restituisce il valore upstream invariato. */
    public static boolean configBoolean(String key, boolean value) {
        return value;
    }
}

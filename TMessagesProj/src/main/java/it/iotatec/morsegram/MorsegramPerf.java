/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.telegram.messenger.ApplicationLoader;

/**
 * "Velocissimo" (mission 1), parte runtime: helper puri chiamati dagli hook one-line nei file upstream e da
 * {@link it.iotatec.morsegram.perf.StartupReporter}. Nessuna telemetria: il log va solo su logcat locale
 * (tag {@link #TAG}) e solo se {@link MorsegramGates#PERF_LOG} o la preferenza {@link MorsegramPrefs#KEY_PERF_LOG}.
 * Tutto ciò che è gated da {@link MorsegramGates#PERF} si piega a compile time (build A/B {@code -PMORSEGRAM_PERF=off}).
 */
public final class MorsegramPerf {

    /** Tag logcat: {@code adb logcat -s MorsegramPerf ActivityTaskManager}. */
    public static final String TAG = "MorsegramPerf";

    private static final int MB = 1024 * 1024;
    /** Tetto della cache bitmap di ImageLoader: oltre, il GC del grande heap costa più di quanto renda lo scroll. */
    private static final int IMAGE_CACHE_CAP_MB = 96;

    /** Fallback per API 23 (Process.getStartElapsedRealtime è API 24): impostato da StartupReporter.install in Application.onCreate. */
    private static volatile long fallbackProcessStart = SystemClock.elapsedRealtime();
    private static volatile Boolean logEnabled;

    private MorsegramPerf() {
    }

    /**
     * Hook ImageLoader (costruttore): byte della cache bitmap in memoria.
     * Upstream: {@code min(maxSizeMb, memoryClass / 7)} MB con {@code getMemoryClass()} (≤ 30 MB anche su un Fold con 12 GB).
     * L'app gira con {@code android:largeHeap="true"} (manifest upstream): su dispositivi non low-RAM si usa
     * {@code getLargeMemoryClass() / 8}, con la formula upstream come pavimento e {@link #IMAGE_CACHE_CAP_MB} come tetto.
     * Low-RAM ({@code isLowRamDevice}), errori o {@code MORSEGRAM_PERF=off} → formula upstream identica.
     *
     * @param memoryClass {@code ActivityManager.getMemoryClass()} già letto da ImageLoader
     * @param maxSizeMb   tetto upstream (30 se memoryClass ≥ 192, altrimenti 15)
     */
    public static int imageCacheBytes(int memoryClass, int maxSizeMb) {
        final int upstreamMb = Math.min(maxSizeMb, memoryClass / 7);
        int mb = upstreamMb;
        if (MorsegramGates.PERF) {
            try {
                final Context ctx = ApplicationLoader.applicationContext;
                final ActivityManager am = ctx == null ? null : (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null && !am.isLowRamDevice()) {
                    mb = Math.max(upstreamMb, Math.min(am.getLargeMemoryClass() / 8, IMAGE_CACHE_CAP_MB));
                }
            } catch (Throwable ignore) {
                mb = upstreamMb;
            }
        }
        return mb * MB;
    }

    /* ---- startup reporting (StartupReporter) ---- */

    /** Da chiamare una volta in Application.onCreate: fissa il fallback di {@link #processStartElapsed()} per API 23. */
    public static void markProcessStart() {
        fallbackProcessStart = SystemClock.elapsedRealtime();
    }

    /** Istante (elapsedRealtime) di avvio del processo: {@code Process.getStartElapsedRealtime()} da API 24, altrimenti Application.onCreate. */
    public static long processStartElapsed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                return Process.getStartElapsedRealtime();
            } catch (Throwable ignore) {
                // fall through
            }
        }
        return fallbackProcessStart;
    }

    /** Millisecondi trascorsi dall'avvio del processo. */
    public static long sinceProcessStart() {
        return SystemClock.elapsedRealtime() - processStartElapsed();
    }

    /** Log attivo: build di debug con PERF on (compile time) oppure preferenza {@code perf.log} (runtime, letta una volta). */
    public static boolean logEnabled() {
        if (MorsegramGates.PERF_LOG) {
            return true;
        }
        Boolean enabled = logEnabled;
        if (enabled == null) {
            enabled = MorsegramPrefs.global().getBoolean(MorsegramPrefs.KEY_PERF_LOG, false);
            if (ApplicationLoader.applicationContext != null) {
                logEnabled = enabled;
            }
        }
        return enabled;
    }

    /** Riga su logcat (tag {@link #TAG}) con il tempo dall'avvio del processo; no-op se il log è spento. */
    public static void log(String what) {
        if (logEnabled()) {
            Log.i(TAG, "+" + sinceProcessStart() + " ms  " + what);
        }
    }
}

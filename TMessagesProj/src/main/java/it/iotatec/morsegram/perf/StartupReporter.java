/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.perf;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Choreographer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.LaunchActivity;

import java.util.concurrent.atomic.AtomicBoolean;

import it.iotatec.morsegram.MorsegramPerf;

/**
 * Strumentazione di avvio "velocissimo" (mission 1), 0 righe upstream: installata da {@code MorsegramApp.onCreate},
 * alimentata da {@code MorsegramApp.onResume} (LaunchActivity.onResume → ApplicationLoaderImpl). Fa tre cose e basta:
 * <ol>
 * <li>{@link Activity#reportFullyDrawn()} una sola volta per processo, sul primo frame dopo che la lista chat è caricata
 *     ({@code NotificationCenter.dialogsNeedReload}, o subito se già caricata / nessun account): alimenta "Fully drawn"
 *     di ActivityTaskManager e {@code timeToFullDisplayMs} di Macrobenchmark. Fallback a {@link #FULLY_DRAWN_TIMEOUT_MS}.</li>
 * <li>Log TTID≈/TTFD su logcat (tag {@link MorsegramPerf#TAG}) solo con {@link MorsegramPerf#logEnabled()}.</li>
 * <li>ML Kit: il manifest app rimuove {@code MlKitInitProvider} (ContentProvider al process start), quindi
 *     {@code MlKit.initialize} viene differito su {@code Utilities.globalQueue} {@link #MLKIT_DELAY_MS} dopo il primo
 *     resume di QUALSIASI activity (ActivityLifecycleCallbacks: BubbleActivity non passa da ApplicationLoader.onResume
 *     e da una bubble si arriva all'editor media, dove {@code SubjectSegmentation.getClient} NON si auto-inizializza).
 *     {@code LanguageDetector} upstream si auto-inizializza da sé ({@code MlKitContext.zza}): se arriva prima,
 *     la nostra chiamata riceve "already initialized" e la ignora.</li>
 * </ol>
 * Nessuna telemetria, nessun effetto sul wire: solo logcat locale e un'API di sistema.
 */
public final class StartupReporter {

    /** Ritardo del MlKit.initialize dopo il primo resume: fuori dalla finestra calda dell'avvio, ben prima di qualsiasi tap sull'editor. */
    static final long MLKIT_DELAY_MS = 1500;
    /** reportFullyDrawn comunque entro questo tempo dal primo resume (lista mai caricata: account vuoto offline, ecc.). */
    static final long FULLY_DRAWN_TIMEOUT_MS = 10_000;

    private static final AtomicBoolean installed = new AtomicBoolean();
    private static final AtomicBoolean resumed = new AtomicBoolean();
    private static final AtomicBoolean mlKitScheduled = new AtomicBoolean();
    private static final AtomicBoolean fullyDrawn = new AtomicBoolean();

    private static int observedAccount = -1;
    private static NotificationCenter.NotificationCenterDelegate dialogsObserver;
    private static Runnable timeoutRunnable;

    private StartupReporter() {
    }

    /** Application.onCreate (via MorsegramApp.onCreate): timestamp di fallback + safety net ML Kit per le activity non-Launch. */
    public static void install(Application app) {
        if (app == null || !installed.compareAndSet(false, true)) {
            return;
        }
        MorsegramPerf.markProcessStart();
        try {
            app.registerActivityLifecycleCallbacks(new ResumeWatcher());
        } catch (Throwable e) {
            FileLog.e(e, false);
        }
    }

    /** LaunchActivity.onResume (via MorsegramApp.onResume), UI thread. Idempotente: solo il primo resume conta. */
    public static void onFirstResume() {
        scheduleMlKit();
        if (!resumed.compareAndSet(false, true)) {
            return;
        }
        final long resumeAt = SystemClock.elapsedRealtime();
        nextFrame(() -> {
            MorsegramPerf.log("TTID≈ first frame after first LaunchActivity.onResume (resume at +" + (resumeAt - MorsegramPerf.processStartElapsed()) + " ms); system value: logcat 'Displayed'");
            if (LaunchActivity.instance == null) {
                return;
            }
            if (dialogsReady()) {
                nextFrame(() -> reportFullyDrawn("dialogs already loaded"));
                return;
            }
            observeDialogs();
            timeoutRunnable = () -> reportFullyDrawn("timeout " + FULLY_DRAWN_TIMEOUT_MS + " ms");
            AndroidUtilities.runOnUIThread(timeoutRunnable, FULLY_DRAWN_TIMEOUT_MS);
        });
    }

    /* ---- fully drawn ---- */

    private static boolean dialogsReady() {
        try {
            final int account = UserConfig.selectedAccount;
            if (!UserConfig.getInstance(account).isClientActivated()) {
                return true; // schermata di login: non c'è lista da aspettare
            }
            return MessagesController.getInstance(account).dialogsLoaded;
        } catch (Throwable e) {
            FileLog.e(e, false);
            return true;
        }
    }

    private static void observeDialogs() {
        final int account = UserConfig.selectedAccount;
        dialogsObserver = (id, acc, args) -> {
            if (id == NotificationCenter.dialogsNeedReload) {
                nextFrame(() -> reportFullyDrawn("dialogsNeedReload"));
            }
        };
        observedAccount = account;
        NotificationCenter.getInstance(account).addObserver(dialogsObserver, NotificationCenter.dialogsNeedReload);
    }

    private static void reportFullyDrawn(String why) {
        if (!fullyDrawn.compareAndSet(false, true)) {
            return;
        }
        if (dialogsObserver != null && observedAccount >= 0) {
            NotificationCenter.getInstance(observedAccount).removeObserver(dialogsObserver, NotificationCenter.dialogsNeedReload);
            dialogsObserver = null;
        }
        if (timeoutRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(timeoutRunnable);
            timeoutRunnable = null;
        }
        final LaunchActivity activity = LaunchActivity.instance;
        try {
            if (activity != null) {
                activity.reportFullyDrawn();
            }
        } catch (Throwable e) {
            FileLog.e(e, false);
        }
        MorsegramPerf.log("TTFD reportFullyDrawn (" + why + "); system value: logcat 'Fully drawn'");
    }

    /** Esegue {@code r} sul thread UI dopo che il prossimo frame è stato disegnato (post da dentro il frame callback). */
    private static void nextFrame(Runnable r) {
        try {
            Choreographer.getInstance().postFrameCallback(frameTimeNanos -> AndroidUtilities.runOnUIThread(r));
        } catch (Throwable e) {
            AndroidUtilities.runOnUIThread(r);
        }
    }

    /* ---- ML Kit ---- */

    static void scheduleMlKit() {
        if (!mlKitScheduled.compareAndSet(false, true)) {
            return;
        }
        Utilities.globalQueue.postRunnable(StartupReporter::initMlKit, MLKIT_DELAY_MS);
    }

    private static void initMlKit() {
        final Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            mlKitScheduled.set(false);
            return;
        }
        final long t0 = SystemClock.elapsedRealtime();
        try {
            com.google.mlkit.common.MlKit.initialize(ctx);
            MorsegramPerf.log("MlKit.initialize deferred on globalQueue: " + (SystemClock.elapsedRealtime() - t0) + " ms (MlKitInitProvider removed from manifest)");
        } catch (IllegalStateException alreadyInitialized) {
            MorsegramPerf.log("MlKit already initialized (upstream LanguageDetector got there first)");
        } catch (Throwable e) {
            FileLog.e(e, false);
        }
    }

    /** Safety net: primo resume di qualsiasi activity (BubbleActivity, ExternalActionActivity…) → ML Kit differito. */
    private static final class ResumeWatcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityResumed(Activity activity) { scheduleMlKit(); }
        @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityPaused(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
        @Override public void onActivityDestroyed(Activity activity) { }
    }
}

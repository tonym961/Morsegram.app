/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import android.app.Application;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.LaunchActivity;

import it.iotatec.morsegram.hidden.HiddenChatsActivity;
import it.iotatec.morsegram.hidden.HiddenLock;
import it.iotatec.morsegram.hidden.HiddenLockSheet;
import it.iotatec.morsegram.perf.StartupReporter;
import it.iotatec.morsegram.ui.MorsegramSettingsActivity;

/**
 * Unico bootstrap di Morsegram, richiamato dai 4 override one-line di {@code ApplicationLoaderImpl} (modulo app):
 * onCreate / addItemOptions / consumePush / onResume. È l'UNICO punto in cui gli id risorsa del modulo app
 * ({@code org.telegram.messenger.regular.R}) entrano nella libreria: vietato {@code Resources.getIdentifier}.
 */
public final class MorsegramApp {

    private static volatile int airplaneIconRes;
    private static volatile int logoIconRes;
    private static volatile int hideIconRes;
    private static volatile int unhideIconRes;
    private static volatile int lockIconRes;

    private MorsegramApp() {
    }

    /**
     * ApplicationLoaderImpl.onCreate (dopo super.onCreate): registra ciò che serve e memorizza gli id risorsa.
     * {@code pinLocalIconRes} = morsegram_pin_local (glifo dei pin locali disegnato da DialogCell via LocalPins.glyph());
     * {@code hideIconRes}/{@code unhideIconRes}/{@code lockIconRes} = morsegram_hide / morsegram_unhide / morsegram_lock
     * (menu e impostazioni delle chat nascoste, S6). Registra anche l'auto-lock della sezione protetta
     * ({@link HiddenLock#install}: ActivityLifecycleCallbacks, 0 righe upstream) e installa la UI di sblocco/setup PIN
     * della sezione ({@link HiddenLockSheet#install}: foglio con biometria + dialogo PIN a due campi, S6-UI).
     */
    public static void onCreate(Application app, int airplaneIconRes, int logoIconRes, int pinLocalIconRes, int hideIconRes, int unhideIconRes, int lockIconRes) {
        MorsegramApp.airplaneIconRes = airplaneIconRes;
        MorsegramApp.logoIconRes = logoIconRes;
        MorsegramApp.hideIconRes = hideIconRes;
        MorsegramApp.unhideIconRes = unhideIconRes;
        MorsegramApp.lockIconRes = lockIconRes;
        AirplaneMode.setIconRes(airplaneIconRes);
        LocalPins.setGlyphRes(pinLocalIconRes);
        HiddenLock.install(app);
        HiddenLockSheet.install();
        StartupReporter.install(app);
        // S3 (piano): app.registerActivityLifecycleCallbacks(FoldState.get()).
    }

    /** DialogsActivity, menu '⋯' della lista chat (extension point upstream ApplicationLoader.addItemOptions): aereo, Chat nascoste (via HiddenLock), Morsegram. */
    public static void addItemOptions(ItemOptions io) {
        if (io == null) {
            return;
        }
        io.addGap();
        io.add(airplaneIconRes, LocaleController.getString("MorsegramAirplaneMode"), AirplaneMode.statusSubtext(), AirplaneMode::toggle);
        io.add(lockIconRes, LocaleController.getString("MorsegramHiddenChats"), HiddenChatsActivity::open);
        io.add(logoIconRes, LocaleController.getString("MorsegramSettings"), MorsegramApp::openSettings);
    }

    public static void openSettings() {
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity != null) {
            activity.presentFragment(new MorsegramSettingsActivity());
        }
    }

    /** LaunchActivity.onResume (extension point upstream ApplicationLoader.onResume). */
    public static void onResume() {
        AirplaneMode.onAppResume();
        LocalPins.onAppResume();
        StartupReporter.onFirstResume();
    }

    public static int airplaneIconRes() {
        return airplaneIconRes;
    }

    public static int logoIconRes() {
        return logoIconRes;
    }

    /** morsegram_hide (visibility_off); 0 se non passato: MorsegramMenu ripiega su msg_archive. */
    public static int hideIconRes() {
        return hideIconRes;
    }

    /** morsegram_unhide (visibility). */
    public static int unhideIconRes() {
        return unhideIconRes;
    }

    /** morsegram_lock (lock): voce 'Chat nascoste' e pagina impostazioni (S6-UI). */
    public static int lockIconRes() {
        return lockIconRes;
    }
}

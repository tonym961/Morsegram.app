/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import android.app.Application;

import org.telegram.messenger.LocaleController;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.LaunchActivity;

import it.iotatec.morsegram.ui.MorsegramSettingsActivity;

/**
 * Unico bootstrap di Morsegram, richiamato dai 4 override one-line di {@code ApplicationLoaderImpl} (modulo app):
 * onCreate / addItemOptions / consumePush / onResume. È l'UNICO punto in cui gli id risorsa del modulo app
 * ({@code org.telegram.messenger.regular.R}) entrano nella libreria: vietato {@code Resources.getIdentifier}.
 */
public final class MorsegramApp {

    private static volatile int airplaneIconRes;
    private static volatile int logoIconRes;

    private MorsegramApp() {
    }

    /**
     * ApplicationLoaderImpl.onCreate (dopo super.onCreate): registra ciò che serve e memorizza gli id risorsa.
     * {@code pinLocalIconRes} = morsegram_pin_local (glifo dei pin locali disegnato da DialogCell via LocalPins.glyph()).
     */
    public static void onCreate(Application app, int airplaneIconRes, int logoIconRes, int pinLocalIconRes) {
        MorsegramApp.airplaneIconRes = airplaneIconRes;
        MorsegramApp.logoIconRes = logoIconRes;
        AirplaneMode.setIconRes(airplaneIconRes);
        LocalPins.setGlyphRes(pinLocalIconRes);
        // S2/S3 (piano): StartupReporter.install(), app.registerActivityLifecycleCallbacks(FoldState.get()).
    }

    /** DialogsActivity, menu '⋯' della lista chat (extension point upstream ApplicationLoader.addItemOptions). */
    public static void addItemOptions(ItemOptions io) {
        if (io == null) {
            return;
        }
        io.addGap();
        io.add(airplaneIconRes, LocaleController.getString("MorsegramAirplaneMode"), AirplaneMode.statusSubtext(), AirplaneMode::toggle);
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
    }

    public static int airplaneIconRes() {
        return airplaneIconRes;
    }

    public static int logoIconRes() {
        return logoIconRes;
    }
}

/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;

/**
 * Tile Quick Settings di {@link AirplaneMode} (dichiarata nel manifest del modulo app, BIND_QUICK_SETTINGS_TILE).
 * Modalità PASSIVA (nessun META_DATA_ACTIVE_TILE): onStartListening arriva a ogni apertura del pannello, e
 * {@link AirplaneMode#requestTileUpdate()} lo richiede dopo ogni cambio in-app. Su API 23 il servizio non viene mai
 * bindato e la classe non viene caricata.
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class AirplaneTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        // processo freddo possibile (forza-arresto, poi tile): set() fa postInitApplication, qui lo anticipiamo per le stringhe.
        ApplicationLoader.postInitApplication();
        AirplaneMode.toggle();
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final boolean on = AirplaneMode.isOn();
        final String state = on ? str("MorsegramAirplaneModeOn", "On") : str("MorsegramAirplaneModeOff", "Off");
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(str("MorsegramAirplaneMode", "Airplane mode"));
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(state);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            tile.setStateDescription(state);
        }
        tile.updateTile();
    }

    /** Le stringhe vivono in localization_*.bin (LocaleController per chiave); fallback inglese se il processo è appena nato. */
    private static String str(String key, String fallback) {
        try {
            final String value = LocaleController.getString(key);
            return value == null || value.startsWith("LOC_ERR") ? fallback : value;
        } catch (Throwable ignore) {
            return fallback;
        }
    }
}

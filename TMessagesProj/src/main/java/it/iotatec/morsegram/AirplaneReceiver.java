/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import org.telegram.messenger.ApplicationLoader;

/**
 * Azione 'Disattiva' della notifica persistente di {@link AirplaneMode} (manifest app, exported=false).
 * Può girare a processo freddo: postInitApplication() prima di toccare ConnectionsManager (lo fa anche set()).
 * v2: scadenza del timer di auto-spegnimento.
 */
public class AirplaneReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !AirplaneMode.ACTION_OFF.equals(intent.getAction())) {
            return;
        }
        ApplicationLoader.postInitApplication();
        AirplaneMode.set(false);
    }
}

/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.LaunchActivity;

import it.iotatec.morsegram.hidden.HiddenChatsActivity;

/**
 * Trampolino invisibile del widget CHAT NASCOSTE: avvia l'app e poi apre la sezione protetta passando dal
 * lucchetto ({@code HiddenChatsActivity.open()} → {@code HiddenLock.authenticate}).
 * <p>
 * Serve un'activity e non un broadcast perché da Android 10 un BroadcastReceiver non può avviare activity,
 * mentre un PendingIntent di activity cliccato su un widget è un avvio in primo piano lecito.
 * <p>
 * {@code HiddenChatsActivity.open()} ha bisogno di {@code LaunchActivity.instance}: a processo freddo non
 * esiste ancora, quindi il tentativo viene ripetuto a intervalli per un tempo limitato (al massimo ~5 s). Non
 * è polling di sfondo: è una sequenza una-tantum dopo un tocco, e si ferma al primo successo.
 */
public class WidgetOpenActivity extends Activity {

    private static final String EXTRA_TARGET = "morsegram_widget_target";
    private static final String TARGET_HIDDEN = "hidden";
    private static final int MAX_ATTEMPTS = 25;
    private static final int RETRY_MS = 200;

    static Intent hiddenIntent(Context context, int appWidgetId) {
        return new Intent(context, WidgetOpenActivity.class)
                .setData(MorsegramWidgets.unique(MorsegramWidgets.TYPE_HIDDEN, appWidgetId, "open"))
                .putExtra(EXTRA_TARGET, TARGET_HIDDEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String target = null;
        try {
            ApplicationLoader.postInitApplication();
            target = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TARGET);
            final Intent open = new Intent(this, LaunchActivity.class)
                    .setAction("com.tmessages.openchat" + Integer.MAX_VALUE)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .addCategory(Intent.CATEGORY_LAUNCHER);
            startActivity(open);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (TARGET_HIDDEN.equals(target)) {
            openHidden(0);
        }
        finish();
    }

    private static void openHidden(int attempt) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final LaunchActivity activity = LaunchActivity.instance;
                if (activity != null && !activity.isFinishing()) {
                    HiddenChatsActivity.open();
                    return;
                }
            } catch (Throwable e) {
                FileLog.e(e);
                return;
            }
            if (attempt + 1 < MAX_ATTEMPTS) {
                openHidden(attempt + 1);
            }
        }, RETRY_MS);
    }
}

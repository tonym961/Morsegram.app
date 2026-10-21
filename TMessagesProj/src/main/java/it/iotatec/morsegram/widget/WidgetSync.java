/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;

/**
 * Aggiornamento A EVENTO dei widget con dati (NON LETTI, VOCALE RAPIDO, CHAT SINGOLA). Zero polling: nessun
 * {@code updatePeriodMillis}, nessun AlarmManager, nessun WorkManager.
 * <p>
 * Osservatori NotificationCenter (0 righe upstream: NotificationCenter è un extension point pubblico) sugli
 * stessi eventi che upstream usa per ridisegnare la lista chat: {@code dialogsNeedReload} (nuovo messaggio,
 * lettura, cancellazione), {@code updateInterfaces} (nomi, avatar), {@code dialogsUnreadCounterChanged} e
 * {@code appDidLogout}. Sono gli stessi fatti che fanno scattare {@code MessagesStorage.updateWidgets} per i
 * widget di upstream.
 * <p>
 * Due freni perché {@code updateInterfaces} arriva spesso: (1) se sulla schermata iniziale non c'è nessuno dei
 * nostri widget con dati si esce subito (una {@code getAppWidgetIds}, nessun calcolo); (2) debounce di 500 ms,
 * così una raffica di update diventa un solo ridisegno.
 */
final class WidgetSync {

    private static final int DEBOUNCE_MS = 500;
    private static volatile boolean installed;

    private static final Runnable APPLY = () -> {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || !MorsegramWidgets.anyDataWidget(context)) {
            return;
        }
        recomputeNow();
        MorsegramWidgets.refreshData();
        // anche i due widget di upstream: il filtro delle chat nascoste vale solo quando ri-interrogano
        MorsegramWidgets.refreshUpstream();
    };

    private static final NotificationCenter.NotificationCenterDelegate OBSERVER = (id, account, args) -> {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || !MorsegramWidgets.anyDataWidgetCached(context)) {
            return;
        }
        AndroidUtilities.cancelRunOnUIThread(APPLY);
        AndroidUtilities.runOnUIThread(APPLY, DEBOUNCE_MS);
    };

    private WidgetSync() {
    }

    static void installIfNeeded(Context context) {
        if (!installed && context != null && MorsegramWidgets.anyDataWidget(context)) {
            install();
        }
    }

    static void install() {
        if (installed) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (installed) {
                return;
            }
            try {
                for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                    final NotificationCenter center = NotificationCenter.getInstance(account);
                    center.addObserver(OBSERVER, NotificationCenter.dialogsNeedReload);
                    center.addObserver(OBSERVER, NotificationCenter.updateInterfaces);
                    center.addObserver(OBSERVER, NotificationCenter.dialogsUnreadCounterChanged);
                }
                NotificationCenter.getGlobalInstance().addObserver(OBSERVER, NotificationCenter.appDidLogout);
                installed = true;
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** Ricalcolo immediato della cache dei non letti per tutti gli slot attivi. Thread UI. */
    static void recomputeNow() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            try {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    WidgetUnread.refreshCache(account);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }
}

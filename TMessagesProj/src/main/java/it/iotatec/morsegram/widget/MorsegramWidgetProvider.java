/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Bundle;
import android.widget.RemoteViews;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

/**
 * Base dei cinque provider Morsegram. Un AppWidgetProvider È un BroadcastReceiver: niente
 * {@code RemoteViewsService} e niente permesso BIND_REMOTEVIEWS, perché nessuno dei nostri widget è una
 * collection scrollabile (lo studio B, "set minimo", punto 5).
 * <p>
 * Nessun lavoro pesante qui: {@code onUpdate} gira sul thread principale e si limita a leggere
 * SharedPreferences e, quando l'account è caricato, le strutture in memoria di MessagesController. Nessun
 * accesso al DB, nessuna attesa su latch (al contrario di FeedWidgetService.java:148-152).
 */
public abstract class MorsegramWidgetProvider extends AppWidgetProvider {

    protected abstract int type();

    /** Costruisce la vista; null = non toccare il widget (meglio l'ultima vista valida di una vuota). */
    protected abstract RemoteViews build(Context context, AppWidgetManager manager, int appWidgetId);

    final void push(Context context, AppWidgetManager manager, int appWidgetId) {
        try {
            // il processo puo' essere freddo (piazzamento, riavvio): come ChatsWidgetProvider.java:69, prima di
            // leggere qualunque stato dell'app. Idempotente e gia' fatto, piu' avanti, da canReadDialogs().
            ApplicationLoader.postInitApplication();
            if (WidgetConfig.type(appWidgetId) != type()) {
                WidgetConfig.setType(appWidgetId, type());
            }
            final RemoteViews views = build(context, manager, appWidgetId);
            if (views != null) {
                manager.updateAppWidget(appWidgetId, views);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);
        for (int appWidgetId : appWidgetIds) {
            push(context, appWidgetManager, appWidgetId);
        }
        MorsegramWidgets.invalidateWidgetCache();
        WidgetSync.installIfNeeded(context);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager, int appWidgetId, Bundle newOptions) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        push(context, appWidgetManager, appWidgetId);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        WidgetConfig.clear(appWidgetIds);
        MorsegramWidgets.invalidateWidgetCache();
    }

    /** Numero di celle della griglia del launcher per un lato, in dp (ChatsWidgetProvider.java:60-66). */
    static int cellsForSize(int size, int cell) {
        int n = 2;
        while (cell * n < size) {
            ++n;
        }
        return n - 1;
    }

    static int widthDp(AppWidgetManager manager, int appWidgetId) {
        try {
            final Bundle options = manager.getAppWidgetOptions(appWidgetId);
            final int width = options == null ? 0 : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
            return width > 0 ? width : 110;
        } catch (Throwable ignore) {
            return 110;
        }
    }

    static int heightDp(AppWidgetManager manager, int appWidgetId) {
        try {
            final Bundle options = manager.getAppWidgetOptions(appWidgetId);
            final int height = options == null ? 0 : options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
            return height > 0 ? height : 86;
        } catch (Throwable ignore) {
            return 86;
        }
    }
}

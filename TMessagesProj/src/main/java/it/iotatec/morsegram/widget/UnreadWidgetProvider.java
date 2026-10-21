/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.widget.RemoteViews;

import org.telegram.messenger.R;

/**
 * NON LETTI: contatore compatto che al tocco apre l'app. Esclude le chat nascoste per costruzione, perché
 * conta su {@code MessagesController.getDialogs(folderId)}, cioè su {@code dialogsByFolder}, da cui il nostro
 * hook in {@code sortDialogs} ha già tolto le chat nascoste ({@link WidgetUnread} ripete comunque il controllo
 * con {@code MorsegramDialogs.isHidden}: cintura e bretelle).
 * <p>
 * Il numero non viene ricalcolato dal widget: lo scrive {@link WidgetSync} quando i dialoghi cambiano e il
 * widget legge la cache, così un {@code onUpdate} a processo freddo (riavvio, piazzamento) non mostra zero
 * mentre i dialoghi non sono ancora caricati — mostra l'ultimo valore noto, o un trattino se non ce n'è uno.
 */
public class UnreadWidgetProvider extends MorsegramWidgetProvider {

    @Override
    protected int type() {
        return MorsegramWidgets.TYPE_UNREAD;
    }

    @Override
    protected RemoteViews build(Context context, AppWidgetManager manager, int appWidgetId) {
        final boolean dark = MorsegramWidgets.isDark(appWidgetId);
        final int account = WidgetConfig.account(appWidgetId);
        final RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.morsegram_widget_unread);
        MorsegramWidgets.applyChrome(views, appWidgetId, dark);

        WidgetUnread.refreshCache(account);
        final int total = WidgetUnread.cachedTotal(account);
        final int chats = WidgetUnread.cachedChats(account);

        if (!MorsegramWidgets.canReadDialogs(account) || total < 0) {
            // nessun account verificabile (processo freddo, slot non attivato): trattino e sottotitolo vuoto.
            // Mai "0", che sarebbe una informazione falsa, e mai la riga ripetuta sotto al titolo.
            views.setTextViewText(R.id.morsegram_widget_value, "\u2014");
            views.setTextColor(R.id.morsegram_widget_value, MorsegramWidgets.textColor(dark));
            views.setTextViewText(R.id.morsegram_widget_subtitle, "");
        } else {
            views.setTextViewText(R.id.morsegram_widget_value, total > 9999 ? "9999+" : String.valueOf(total));
            views.setTextColor(R.id.morsegram_widget_value, total > 0 ? MorsegramWidgets.accentColor(dark) : MorsegramWidgets.textColor(dark));
            views.setTextViewText(R.id.morsegram_widget_subtitle, total == 0
                    ? MorsegramWidgets.str("MorsegramWidgetUnreadNone", "All read")
                    : MorsegramWidgets.format("MorsegramWidgetUnreadChats", "in " + chats + " chats", chats));
        }
        views.setTextViewText(R.id.morsegram_widget_title, MorsegramWidgets.str("MorsegramWidgetUnread", "Unread"));
        views.setOnClickPendingIntent(R.id.morsegram_widget_root, MorsegramWidgets.openApp(context, type(), appWidgetId));
        return views;
    }
}

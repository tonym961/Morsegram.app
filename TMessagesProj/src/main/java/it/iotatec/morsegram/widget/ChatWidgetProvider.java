/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;
import android.widget.RemoteViews;

import org.telegram.messenger.R;

/**
 * CHAT SINGOLA: la chat scelta in configurazione, con il suo ultimo messaggio, l'ora, il contatore dei non
 * letti e una riga "Rispondi".
 * <p>
 * L'INVIO LO FA L'UTENTE: "Rispondi" non è un campo di testo (RemoteViews non ospita RemoteInput) ma apre la
 * chat nell'app con gli extra che {@code LaunchActivity.java:2880-2887} legge già, dove l'utente scrive e
 * manda. Nessun messaggio parte dal widget.
 * <p>
 * Se la chat è nascosta — o se il filtro non è verificabile perché l'account non è ancora caricato — il widget
 * mostra lo STESSO segnaposto di un widget mai configurato: nessun nome, nessuna anteprima, nessun conteggio, e
 * la cache viene cancellata ({@link WidgetDialog#read}). Un testo diverso per i due casi sarebbe una confessione:
 * direbbe che quella chat esiste ed è nascosta a chi legge la schermata iniziale senza sbloccare il telefono.
 */
public class ChatWidgetProvider extends MorsegramWidgetProvider {

    @Override
    protected int type() {
        return MorsegramWidgets.TYPE_CHAT;
    }

    @Override
    protected RemoteViews build(Context context, AppWidgetManager manager, int appWidgetId) {
        final boolean dark = MorsegramWidgets.isDark(appWidgetId);
        final int account = WidgetConfig.account(appWidgetId);
        final long dialogId = WidgetConfig.dialogId(appWidgetId);
        final RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.morsegram_widget_chat);
        MorsegramWidgets.applyChrome(views, appWidgetId, dark);
        views.setTextColor(R.id.morsegram_widget_action, MorsegramWidgets.accentColor(dark));
        views.setTextColor(R.id.morsegram_widget_value, MorsegramWidgets.textColor(dark));

        final WidgetDialog dialog = WidgetDialog.read(appWidgetId, account, dialogId);
        if (dialog == null) {
            views.setTextViewText(R.id.morsegram_widget_title, MorsegramWidgets.str("MorsegramWidgetChat", "Chat"));
            // UN SOLO testo per i due casi: un widget che punta a una chat NASCOSTA deve essere indistinguibile
            // da uno mai configurato. Due etichette diverse dicevano a chi guarda la schermata iniziale, senza
            // sbloccare il telefono, che quella chat esiste ed e' nascosta -- la stessa informazione che
            // HiddenWidgetProvider rifiuta deliberatamente di dare. Il tocco apre comunque l'app.
            views.setTextViewText(R.id.morsegram_widget_subtitle, MorsegramWidgets.str("MorsegramWidgetPickChat", "Choose a chat"));
            views.setTextViewText(R.id.morsegram_widget_value, "");
            views.setViewVisibility(R.id.morsegram_widget_badge, View.GONE);
            views.setViewVisibility(R.id.morsegram_widget_action, View.GONE);
            views.setOnClickPendingIntent(R.id.morsegram_widget_root, MorsegramWidgets.openApp(context, type(), appWidgetId));
            return views;
        }

        views.setTextViewText(R.id.morsegram_widget_title, dialog.name);
        views.setTextViewText(R.id.morsegram_widget_subtitle, TextUtils.isEmpty(dialog.preview)
                ? MorsegramWidgets.str("MorsegramWidgetNoMessage", "No messages yet") : dialog.preview);
        views.setTextViewText(R.id.morsegram_widget_value, dialog.time == null ? "" : dialog.time);
        if (dialog.unread > 0) {
            views.setViewVisibility(R.id.morsegram_widget_badge, View.VISIBLE);
            views.setTextViewText(R.id.morsegram_widget_badge, dialog.unread > 99 ? "99+" : String.valueOf(dialog.unread));
        } else {
            views.setViewVisibility(R.id.morsegram_widget_badge, View.GONE);
        }
        views.setViewVisibility(R.id.morsegram_widget_action, View.VISIBLE);
        views.setTextViewText(R.id.morsegram_widget_action, MorsegramWidgets.str("MorsegramWidgetReply", "Reply"));

        final android.app.PendingIntent open = MorsegramWidgets.openDialog(context, account, dialogId, type(), appWidgetId);
        views.setOnClickPendingIntent(R.id.morsegram_widget_root, open);
        views.setOnClickPendingIntent(R.id.morsegram_widget_action, open);
        return views;
    }
}

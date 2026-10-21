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
 * CHAT NASCOSTE: scorciatoia che apre il lucchetto. Non legge NULLA: nessun nome, nessuna anteprima, nessun
 * conteggio, nemmeno "quante" chat nascoste esistono — la schermata iniziale si legge senza sbloccare il
 * telefono, quindi anche un numero sarebbe un'informazione di troppo (è la stessa scelta della pagina
 * impostazioni, dove il conteggio non compare a sezione bloccata, MorsegramSettingsActivity "hiddenValue").
 * <p>
 * Questo widget è l'unico dei cinque che non ha nulla da ridisegnare quando cambiano i dati: il suo contenuto
 * è una costante. Nessun osservatore, nessun {@code updatePeriodMillis}.
 * <p>
 * Il tocco apre {@link WidgetOpenActivity}, che avvia l'app e poi passa da
 * {@code HiddenChatsActivity.open()} → {@code HiddenLock.authenticate} (PIN o biometria). Non si può usare un
 * broadcast: da Android 10 un BroadcastReceiver non può avviare activity, mentre un PendingIntent di activity
 * cliccato su un widget sì.
 */
public class HiddenWidgetProvider extends MorsegramWidgetProvider {

    @Override
    protected int type() {
        return MorsegramWidgets.TYPE_HIDDEN;
    }

    @Override
    protected RemoteViews build(Context context, AppWidgetManager manager, int appWidgetId) {
        final boolean dark = MorsegramWidgets.isDark(appWidgetId);
        final RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.morsegram_widget_hidden);
        MorsegramWidgets.applyChrome(views, appWidgetId, dark);
        // la pastiglia segue il tema: il predefinito del layout e' pill_off_light (avorio #F3EDE2), quindi con
        // tema scuro restava un disco avorio su sfondo #1A1A1A e il lucchetto, che applyChrome tinge con
        // iconColor(true) = AMBER, ci finiva sopra a circa 1,9:1, sotto la soglia 3:1 che la palette impone
        views.setImageViewResource(R.id.morsegram_widget_pill,
                dark ? R.drawable.morsegram_widget_pill_off_dark : R.drawable.morsegram_widget_pill_off_light);
        views.setTextViewText(R.id.morsegram_widget_title, MorsegramWidgets.str("MorsegramHiddenChats", "Hidden chats"));
        views.setTextViewText(R.id.morsegram_widget_subtitle, MorsegramWidgets.str("MorsegramWidgetHiddenAction", "Unlock"));
        views.setOnClickPendingIntent(R.id.morsegram_widget_root,
                MorsegramWidgets.activity(context, WidgetOpenActivity.hiddenIntent(context, appWidgetId)));
        return views;
    }
}

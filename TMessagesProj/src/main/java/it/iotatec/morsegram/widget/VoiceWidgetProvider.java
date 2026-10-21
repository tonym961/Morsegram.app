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
 * VOCALE RAPIDO: al tocco registra un vocale per la chat scelta in configurazione.
 * <p>
 * NON INVIA NIENTE DA SOLO. Il tocco apre {@link VoiceRecordActivity}, che registra e poi si FERMA in uno stato
 * di revisione (riascolto, "Rifai", "Annulla", "Invia a &lt;chat&gt;"): la conferma la chiede quella schermata,
 * NON l'app, perché la consegna a LaunchActivity come {@code Intent.ACTION_SEND} con {@code dialogId} +
 * {@code hash} finisce in {@code didSelectDialogs}, che per un audio spedisce senza chiedere nulla. Dal widget,
 * e dallo scadere del tempo massimo di registrazione, non parte alcun messaggio.
 * <p>
 * Il nome della chat è mostrato solo se la chat NON è nascosta e il filtro è verificabile
 * ({@link WidgetDialog#read}); altrimenti compare il segnaposto.
 */
public class VoiceWidgetProvider extends MorsegramWidgetProvider {

    @Override
    protected int type() {
        return MorsegramWidgets.TYPE_VOICE;
    }

    @Override
    protected RemoteViews build(Context context, AppWidgetManager manager, int appWidgetId) {
        final boolean dark = MorsegramWidgets.isDark(appWidgetId);
        final int account = WidgetConfig.account(appWidgetId);
        final long dialogId = WidgetConfig.dialogId(appWidgetId);
        final RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.morsegram_widget_voice);
        MorsegramWidgets.applyChrome(views, appWidgetId, dark);
        // la pastiglia va DICHIARATA, come fa AirplaneWidgetProvider: il predefinito del layout e' pill_on
        // (riempimento ambra pieno) e applyChrome tinge il glifo con iconColor(dark), cioe' ambra su ambra (1:1)
        // al buio e ambra media su ambra (circa 1,5:1) in chiaro -- in entrambi i casi il microfono scompariva.
        // Sopra un riempimento ambra i glifi vanno scuri (MorsegramPalette.ON_AMBER, 8,84:1).
        views.setImageViewResource(R.id.morsegram_widget_pill, R.drawable.morsegram_widget_pill_on);
        views.setInt(R.id.morsegram_widget_icon, "setColorFilter", it.iotatec.morsegram.theme.MorsegramPalette.ON_AMBER);
        views.setTextViewText(R.id.morsegram_widget_title, MorsegramWidgets.str("MorsegramWidgetVoice", "Quick voice"));

        final String name = WidgetDialog.nameOnly(appWidgetId, account, dialogId);
        if (name == null) {
            // un solo testo per i due casi, come in ChatWidgetProvider: "chat nascosta" non si distingue da
            // "widget non configurato", altrimenti la schermata iniziale rivela che quella chat esiste
            views.setTextViewText(R.id.morsegram_widget_subtitle, MorsegramWidgets.str("MorsegramWidgetPickChat", "Choose a chat"));
            views.setOnClickPendingIntent(R.id.morsegram_widget_root, MorsegramWidgets.openApp(context, type(), appWidgetId));
            return views;
        }
        views.setTextViewText(R.id.morsegram_widget_subtitle, name);
        views.setOnClickPendingIntent(R.id.morsegram_widget_root,
                MorsegramWidgets.activity(context, VoiceRecordActivity.intent(context, appWidgetId, account, dialogId)));
        return views;
    }
}

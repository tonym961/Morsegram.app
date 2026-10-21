/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;

import it.iotatec.morsegram.AirplaneMode;
import it.iotatec.morsegram.theme.MorsegramPalette;

/**
 * MODALITÀ AEREO: interruttore sulla schermata iniziale che accende e spegne senza aprire l'app, con lo stato
 * visibile (etichetta "Attiva"/"Disattivata" più la pastiglia ambra accesa o il contorno spento).
 * <p>
 * Il tocco NON apre l'app: manda un broadcast a questo stesso provider (un AppWidgetProvider è un
 * BroadcastReceiver), esattamente come l'azione "Disattiva" della notifica persistente
 * (AirplaneMode.java:228-230) e come AirplaneReceiver/AirplaneTileService. A processo freddo serve
 * {@code ApplicationLoader.postInitApplication()} prima di toccare ConnectionsManager: lo fa anche
 * {@link AirplaneMode#set}, qui lo anticipiamo per le stringhe.
 * <p>
 * Lo stato resta allineato con la tile Quick Settings e la notifica perché {@link AirplaneMode#set} chiama
 * {@code MorsegramWidgets.refresh(TYPE_AIRPLANE)} accanto a {@code requestTileUpdate()}.
 */
public class AirplaneWidgetProvider extends MorsegramWidgetProvider {

    @Override
    protected int type() {
        return MorsegramWidgets.TYPE_AIRPLANE;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && MorsegramWidgets.ACTION_TOGGLE_AIRPLANE.equals(intent.getAction())) {
            ApplicationLoader.postInitApplication();
            AirplaneMode.toggle();
            // set() chiama già refresh(TYPE_AIRPLANE); ridisegniamo comunque il widget toccato, anche se il
            // toggle è stato rifiutato (chiamata VoIP in corso): così l'interruttore non resta "a metà".
            MorsegramWidgets.refresh(MorsegramWidgets.TYPE_AIRPLANE);
            return;
        }
        super.onReceive(context, intent);
    }

    @Override
    protected RemoteViews build(Context context, AppWidgetManager manager, int appWidgetId) {
        final boolean dark = MorsegramWidgets.isDark(appWidgetId);
        final boolean on = AirplaneMode.isOn();
        final RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.morsegram_widget_airplane);
        MorsegramWidgets.applyChrome(views, appWidgetId, dark);
        views.setTextViewText(R.id.morsegram_widget_title, MorsegramWidgets.str("MorsegramAirplaneMode", "Airplane mode"));
        views.setTextViewText(R.id.morsegram_widget_subtitle, on
                ? MorsegramWidgets.str("MorsegramAirplaneModeOn", "On")
                : MorsegramWidgets.str("MorsegramAirplaneModeOff", "Off"));
        // acceso: pastiglia ambra piena con il glifo scuro sopra (ON_AMBER, 8,84:1; il bianco sarebbe 2,15:1).
        views.setImageViewResource(R.id.morsegram_widget_pill, on
                ? R.drawable.morsegram_widget_pill_on
                : (dark ? R.drawable.morsegram_widget_pill_off_dark : R.drawable.morsegram_widget_pill_off_light));
        views.setInt(R.id.morsegram_widget_icon, "setColorFilter", on ? MorsegramPalette.ON_AMBER : MorsegramWidgets.textColor(dark));
        views.setTextColor(R.id.morsegram_widget_subtitle, on ? MorsegramWidgets.accentColor(dark) : MorsegramWidgets.textColor(dark));
        views.setOnClickPendingIntent(R.id.morsegram_widget_root,
                MorsegramWidgets.broadcast(context, AirplaneWidgetProvider.class, MorsegramWidgets.ACTION_TOGGLE_AIRPLANE, type(), appWidgetId));
        return views;
    }
}

/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ExternalActionActivity;

/**
 * Schermata di configurazione dei cinque widget Morsegram ({@code android:configure} di tutti e cinque i
 * {@code appwidget-provider}; il tipo si deduce dal provider a cui l'appWidgetId appartiene, via
 * {@link MorsegramWidgets#typeOf}).
 * <p>
 * Stesso scheletro di {@code ChatsWidgetConfigActivity} (ExternalActionActivity + checkPasscode + un fragment
 * nello stack), così il passcode di Telegram vale anche qui. La differenza è che il RISULTATO viene messo
 * subito: i nostri widget sono {@code configuration_optional}, le scelte si applicano una per una mentre si
 * toccano, e uscire col tasto indietro NON deve fare sparire il widget appena piazzato.
 */
public class MorsegramWidgetConfigActivity extends ExternalActionActivity {

    private int creatingAppWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected boolean handleIntent(Intent intent, boolean isNew, boolean restore, boolean fromPassword, int intentAccount, int state) {
        if (!checkPasscode(intent, isNew, restore, fromPassword, intentAccount, state)) {
            return false;
        }
        final Bundle extras = intent.getExtras();
        if (extras != null) {
            creatingAppWidgetId = extras.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        }
        if (creatingAppWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return true;
        }
        final Intent result = new Intent();
        result.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, creatingAppWidgetId);
        setResult(Activity.RESULT_OK, result);

        final int type = MorsegramWidgets.typeOf(this, creatingAppWidgetId);
        final WidgetSettingsFragment fragment = new WidgetSettingsFragment(type, creatingAppWidgetId);
        fragment.setOnDone(this::finish);
        if (AndroidUtilities.isTablet()) {
            if (layersActionBarLayout.getFragmentStack().isEmpty()) {
                layersActionBarLayout.addFragmentToStack(fragment);
            }
        } else {
            if (actionBarLayout.getFragmentStack().isEmpty()) {
                actionBarLayout.addFragmentToStack(fragment);
            }
            backgroundTablet.setVisibility(View.GONE);
        }
        actionBarLayout.showLastFragment();
        if (AndroidUtilities.isTablet()) {
            layersActionBarLayout.showLastFragment();
        }
        intent.setAction(null);
        return true;
    }
}

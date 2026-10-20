/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.hidden;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

/**
 * Dialogo di impostazione/cambio del PIN Morsegram a due campi (nuovo PIN + ripeti, 4-8 cifre), stile AlertDialog
 * upstream. Installato come {@link HiddenLock.Presenter} di setup da {@link HiddenLockSheet#install()}: lo usano
 * {@link HiddenLock#setupPin} / {@link HiddenLock#ensurePin} (primo 'Nascondi chat') e la pagina impostazioni
 * (Imposta/Cambia PIN). Salva via {@link HiddenLock#setPin} (che apre anche la sessione) prima di onDone.
 */
public final class HiddenPinDialog {

    private HiddenPinDialog() {
    }

    /** Esattamente uno fra onDone (PIN salvato) e onCancel (chiuso senza salvare). Thread UI. */
    public static void show(Activity activity, Runnable onDone, Runnable onCancel) {
        if (activity == null) {
            activity = LaunchActivity.instance;
        }
        if (activity == null || activity.isFinishing()) {
            if (onCancel != null) {
                onCancel.run();
            }
            return;
        }
        final Context context = activity;
        final boolean change = HiddenLock.hasPin();
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(change ? "MorsegramChangePin" : "MorsegramSetPin"));
        builder.setMessage(LocaleController.getString("MorsegramSetPinInfo"));

        final EditTextBoldCursor first = HiddenLock.pinField(context);
        first.setHint(LocaleController.getString("MorsegramPinNew"));
        first.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        final EditTextBoldCursor second = HiddenLock.pinField(context);
        second.setHint(LocaleController.getString("MorsegramPinRepeat"));
        final TextView info = HiddenLock.infoView(context);

        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(first, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 9, 20, 8));
        container.addView(second, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 4));
        container.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 9));
        builder.makeCustomMaxHeight();
        builder.setView(container);
        builder.setWidth(Math.min(AndroidUtilities.dp(320), AndroidUtilities.displaySize.x * 85 / 100));

        final boolean[] done = new boolean[1];
        final AlertDialog[] dialog = new AlertDialog[1];
        final Runnable submit = () -> {
            if (done[0]) {
                return;
            }
            final String pin = first.getText().toString();
            final String repeat = second.getText().toString();
            if (pin.length() < HiddenLock.PIN_MIN_LENGTH) {
                info.setText(LocaleController.formatString("MorsegramPinTooShort", 0, HiddenLock.PIN_MIN_LENGTH));
                info.setVisibility(View.VISIBLE);
                AndroidUtilities.shakeView(first);
                first.requestFocus();
                return;
            }
            if (!pin.equals(repeat)) {
                info.setText(LocaleController.getString("MorsegramPinMismatch"));
                info.setVisibility(View.VISIBLE);
                second.setText("");
                AndroidUtilities.shakeView(second);
                second.requestFocus();
                return;
            }
            if (!HiddenLock.setPin(pin)) {
                info.setText(LocaleController.formatString("MorsegramPinTooShort", 0, HiddenLock.PIN_MIN_LENGTH));
                info.setVisibility(View.VISIBLE);
                AndroidUtilities.shakeView(first);
                return;
            }
            done[0] = true;
            if (dialog[0] != null) {
                dialog[0].dismiss();
            }
            if (onDone != null) {
                onDone.run();
            }
        };
        first.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_DONE) {
                second.requestFocus();
                return true;
            }
            return false;
        });
        second.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });
        builder.setPositiveButton(LocaleController.getString(R.string.Done), (d, which) -> submit.run());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, which) -> d.dismiss());
        try {
            dialog[0] = builder.create();
            dialog[0].setDismissDialogByButtons(false);
            dialog[0].setOnDismissListener(d -> {
                AndroidUtilities.hideKeyboard(first);
                AndroidUtilities.hideKeyboard(second);
                if (!done[0] && onCancel != null) {
                    onCancel.run();
                }
            });
            dialog[0].setOnShowListener(d -> {
                first.requestFocus();
                AndroidUtilities.showKeyboard(first);
            });
            dialog[0].show();
        } catch (Throwable e) {
            FileLog.e(e);
            if (!done[0] && onCancel != null) {
                onCancel.run();
            }
        }
    }
}

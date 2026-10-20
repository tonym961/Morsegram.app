/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.hidden;

import android.app.Activity;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import it.iotatec.morsegram.MorsegramApp;

/**
 * Foglio di sblocco della sezione 'Chat nascoste' (S6-UI): {@link HiddenLock.Presenter} installato da
 * {@link #install()} (chiamato da MorsegramApp.onCreate, 0 righe upstream) e quindi usato da OGNI chiamante di
 * {@link HiddenLock#authenticate} — HiddenGate (chat nascosta aperta da lista/ricerca/notifica/link), HiddenChatsActivity,
 * menu 'Mostra chat', pagina impostazioni. Stile upstream (BottomSheet con tastiera, ButtonWithCounterView): lucchetto,
 * campo PIN numerico, errore/backoff con conto alla rovescia, bottone 'Sblocca' e, se la biometria è abilitata e
 * disponibile, BiometricPrompt automatico all'apertura (pattern PasscodeView) con ripiego sul PIN.
 * <p>
 * Contratto Presenter: esattamente uno fra onSuccess (dopo {@link HiddenLock#unlock()}, già chiamato da
 * {@link HiddenLock#verify} / {@link HiddenLock#promptBiometric}) e onCancel (chiusura senza sblocco).
 */
public class HiddenLockSheet extends BottomSheet {

    /** MorsegramApp.onCreate: sostituisce i dialoghi di default di HiddenLock con il foglio e il dialogo a due campi. */
    public static void install() {
        HiddenLock.setPresenter(HiddenLockSheet::present);
        HiddenLock.setSetupPresenter(HiddenPinDialog::show);
    }

    /** HiddenLock.Presenter. Thread UI. */
    public static void present(Activity activity, Runnable onSuccess, Runnable onCancel) {
        if (activity == null || activity.isFinishing()) {
            if (onCancel != null) {
                onCancel.run();
            }
            return;
        }
        try {
            new HiddenLockSheet(activity, onSuccess, onCancel).show();
        } catch (Throwable e) {
            FileLog.e(e);
            if (onCancel != null) {
                onCancel.run();
            }
        }
    }

    private final Activity activity;
    private final Runnable onSuccess;
    private final Runnable onCancel;
    private final EditTextBoldCursor pinField;
    private final TextView info;
    private final ButtonWithCounterView unlockButton;
    private final TextView biometricButton;
    private final boolean biometric;
    private boolean done;
    private Runnable countdown;

    private HiddenLockSheet(Activity activity, Runnable onSuccess, Runnable onCancel) {
        super(activity, true);
        this.activity = activity;
        this.onSuccess = onSuccess;
        this.onCancel = onCancel;
        smoothKeyboardAnimationEnabled = true;
        fixNavigationBar(Theme.getColor(Theme.key_dialogBackground));

        final Context context = activity;
        biometric = HiddenLock.isBiometricEnabled() && HiddenLock.canUseBiometric() && activity instanceof FragmentActivity;

        final LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        layout.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));

        final ImageView icon = new ImageView(context);
        final GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        icon.setBackground(circle);
        icon.setScaleType(ImageView.ScaleType.CENTER);
        final int lockRes = MorsegramApp.lockIconRes();
        icon.setImageResource(lockRes != 0 ? lockRes : R.drawable.outline_header_lock_24);
        icon.setColorFilter(Theme.getColor(Theme.key_featuredStickers_buttonText), PorterDuff.Mode.SRC_IN);
        layout.addView(icon, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0, 12, 0, 0));

        final TextView title = new TextView(context);
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        title.setGravity(Gravity.CENTER);
        title.setText(LocaleController.getString("MorsegramHiddenChats"));
        layout.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 16, 0, 0));

        final TextView subtitle = new TextView(context);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitle.setTextColor(Theme.getColor(Theme.key_dialogTextGray3));
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setText(LocaleController.getString("MorsegramEnterPin"));
        layout.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 24, 6, 24, 0));

        pinField = HiddenLock.pinField(context);
        pinField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attempt();
                return true;
            }
            return false;
        });
        layout.addView(pinField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 20, 24, 4));

        info = HiddenLock.infoView(context);
        layout.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 0, 24, 4));

        unlockButton = new ButtonWithCounterView(context, null);
        unlockButton.setText(LocaleController.getString("MorsegramUnlock"), false);
        unlockButton.setOnClickListener(v -> attempt());
        layout.addView(unlockButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 8, 12, 8, 0));

        biometricButton = new TextView(context);
        biometricButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        biometricButton.setTextColor(Theme.getColor(Theme.key_dialogTextBlue));
        biometricButton.setGravity(Gravity.CENTER);
        biometricButton.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14), AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        biometricButton.setText(LocaleController.getString("MorsegramUseBiometric"));
        biometricButton.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), 6, 6));
        biometricButton.setOnClickListener(v -> promptBiometric());
        biometricButton.setVisibility(biometric ? View.VISIBLE : View.GONE);
        layout.addView(biometricButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 2, 0, 0));

        setCustomView(layout);
        setOnDismissListener(d -> {
            stopCountdown();
            AndroidUtilities.hideKeyboard(pinField);
            if (!done && onCancel != null) {
                onCancel.run();
            }
        });
    }

    @Override
    public void show() {
        super.show();
        if (biometric) {
            AndroidUtilities.runOnUIThread(this::promptBiometric, 200);
        } else {
            focusPin();
        }
        showBackoffIfAny();
    }

    private void focusPin() {
        AndroidUtilities.runOnUIThread(() -> {
            if (done) {
                return;
            }
            pinField.requestFocus();
            AndroidUtilities.showKeyboard(pinField);
        }, 50);
    }

    private void promptBiometric() {
        if (done || !(activity instanceof FragmentActivity) || activity.isFinishing()) {
            return;
        }
        try {
            HiddenLock.promptBiometric((FragmentActivity) activity, this::succeed, this::focusPin);
        } catch (Throwable e) {
            FileLog.e(e);
            focusPin();
        }
    }

    private void succeed() {
        if (done) {
            return;
        }
        done = true;
        dismiss();
        if (onSuccess != null) {
            onSuccess.run();
        }
    }

    private void attempt() {
        if (done) {
            return;
        }
        if (HiddenLock.retryInMs() > 0) {
            showBackoffIfAny();
            AndroidUtilities.shakeView(pinField);
            return;
        }
        if (HiddenLock.verify(pinField.getText().toString())) {
            succeed();
            return;
        }
        pinField.setText("");
        if (HiddenLock.retryInMs() > 0) {
            showBackoffIfAny();
        } else {
            info.setText(LocaleController.getString("MorsegramPinWrong"));
            info.setVisibility(View.VISIBLE);
        }
        AndroidUtilities.shakeView(pinField);
    }

    /** Backoff visibile: 'Riprova tra N s' aggiornato ogni secondo, bottone disabilitato finché non scade. */
    private void showBackoffIfAny() {
        stopCountdown();
        final long wait = HiddenLock.retryInMs();
        if (wait <= 0) {
            unlockButton.setEnabled(true);
            return;
        }
        unlockButton.setEnabled(false);
        info.setText(LocaleController.formatString("MorsegramPinRetryIn", 0, (int) Math.ceil(wait / 1000.0)));
        info.setVisibility(View.VISIBLE);
        countdown = () -> {
            countdown = null;
            if (done) {
                return;
            }
            final long left = HiddenLock.retryInMs();
            if (left <= 0) {
                info.setVisibility(View.GONE);
                unlockButton.setEnabled(true);
            } else {
                showBackoffIfAny();
            }
        };
        AndroidUtilities.runOnUIThread(countdown, Math.min(1000L, wait));
    }

    private void stopCountdown() {
        if (countdown != null) {
            AndroidUtilities.cancelRunOnUIThread(countdown);
            countdown = null;
        }
    }
}

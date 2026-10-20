/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.hidden;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FingerprintController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.TopicsFragment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import it.iotatec.morsegram.MorsegramPrefs;

/**
 * Lucchetto della sezione 'Chat nascoste': PIN Morsegram device-wide (separato dal passcode Telegram upstream),
 * biometria opzionale e sessione di sblocco con auto-lock quando l'app va in background.
 * <ul>
 * <li>PIN: PBKDF2-SHA512 nativo ({@link Utilities#computePBKDF2}, 100k iterazioni) + salt 16 B SecureRandom, in
 *     {@link MorsegramPrefs#global()}; backoff dei tentativi sbagliati come SharedConfig.increaseBadPasscodeTries.</li>
 * <li>Biometria: {@code androidx.biometric.BiometricPrompt} su {@code LaunchActivity.instance} con lo stesso pattern
 *     (executor main, BIOMETRIC_STRONG, bottone negativo 'Usa PIN') di upstream PasscodeView.checkFingerprint.</li>
 * <li>Sessione: solo in memoria. {@link #isLocked()} = PIN impostato e (mai sbloccato oppure timeout scaduto in
 *     background). ActivityLifecycleCallbacks registrati da MorsegramApp.onCreate ({@link #install}): alla pausa
 *     parte il timer ({@link #timeoutSeconds()}: 0 = subito, -1 = mai), alla ripresa si verifica la scadenza;
 *     {@link #lock()} chiude i fragment delle chat nascoste sui tre INavigationLayout e nel RightSlidingDialogContainer.</li>
 * </ul>
 * È un gate UI: il DB resta in chiaro come upstream. Nessun effetto sul wire (ToS 1.4): lo sblocco presenta lo
 * stesso fragment con gli stessi argomenti, il blocco equivale a un 'indietro' dell'utente.
 * La UI di sblocco/setup di default è a dialoghi (PIN numerico); {@link #setPresenter} permette a HiddenLockActivity
 * (S6-UI) di sostituirla senza toccare chi chiama ({@link HiddenGate}, menu, impostazioni).
 */
public final class HiddenLock {

    /** Sostituto della UI di sblocco (HiddenLockActivity): deve chiamare {@link #unlock()} prima di onSuccess. */
    public interface Presenter {
        void present(Activity activity, Runnable onSuccess, Runnable onCancel);
    }

    public interface Listener {
        void onHiddenLockChanged(boolean locked);
    }

    /** Marker per i fragment nostri (HiddenChatsActivity, HiddenLockActivity): {@link #lock()} li chiude. */
    public interface HiddenFragment {
    }

    public static final int TIMEOUT_IMMEDIATELY = 0;
    public static final int TIMEOUT_NEVER = -1;
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;
    /** valori offerti dalle impostazioni: subito, 1 min, 5 min, 30 min, mai. */
    public static final int[] TIMEOUTS = {TIMEOUT_IMMEDIATELY, 60, 300, 1800, TIMEOUT_NEVER};
    public static final int PIN_MIN_LENGTH = 4;
    public static final int PIN_MAX_LENGTH = 8;

    private static volatile boolean unlocked;
    /** SystemClock.elapsedRealtime() dell'ultima pausa, 0 = in primo piano. */
    private static volatile long pausedAt;
    private static Runnable lockRunnable;
    private static volatile Presenter presenter;
    /** Sostituto della UI di setup/cambio PIN (HiddenPinDialog): deve chiamare {@link #setPin} prima di onSuccess. */
    private static volatile Presenter setupPresenter;
    private static final ArrayList<Listener> listeners = new ArrayList<>();
    private static boolean installed;
    private static boolean authenticating;
    private static boolean secure;

    private HiddenLock() {
    }

    /* ======================================================================================== PIN */

    private static SharedPreferences prefs() {
        return MorsegramPrefs.global();
    }

    public static boolean hasPin() {
        return !TextUtils.isEmpty(prefs().getString(MorsegramPrefs.KEY_HIDDEN_PIN_HASH, null));
    }

    public static int pinLength() {
        return prefs().getInt(MorsegramPrefs.KEY_HIDDEN_PIN_LEN, 0);
    }

    /** Imposta/cambia il PIN e apre la sessione (chi imposta il PIN è autenticato). Thread UI. */
    public static boolean setPin(String pin) {
        if (pin == null || pin.length() < PIN_MIN_LENGTH || pin.length() > PIN_MAX_LENGTH) {
            return false;
        }
        try {
            final byte[] salt = new byte[16];
            Utilities.random.nextBytes(salt);
            final String hash = Utilities.bytesToHex(Utilities.computePBKDF2(pin.getBytes(StandardCharsets.UTF_8), salt));
            prefs().edit()
                    .putString(MorsegramPrefs.KEY_HIDDEN_PIN_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(MorsegramPrefs.KEY_HIDDEN_PIN_HASH, hash)
                    .putInt(MorsegramPrefs.KEY_HIDDEN_PIN_LEN, pin.length())
                    .putInt(MorsegramPrefs.KEY_HIDDEN_PIN_TRIES, 0)
                    .putLong(MorsegramPrefs.KEY_HIDDEN_PIN_RETRY_AT, 0)
                    .apply();
            unlock();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Rimuove il PIN: la sezione resta nascosta dalle liste ma non più protetta. Thread UI. */
    public static void clearPin() {
        prefs().edit()
                .remove(MorsegramPrefs.KEY_HIDDEN_PIN_SALT)
                .remove(MorsegramPrefs.KEY_HIDDEN_PIN_HASH)
                .remove(MorsegramPrefs.KEY_HIDDEN_PIN_LEN)
                .remove(MorsegramPrefs.KEY_HIDDEN_PIN_TRIES)
                .remove(MorsegramPrefs.KEY_HIDDEN_PIN_RETRY_AT)
                .apply();
        unlocked = false;
        notifyListeners(false);
    }

    /** ms da attendere prima del prossimo tentativo (backoff), 0 se si può provare. */
    public static long retryInMs() {
        final long retryAt = prefs().getLong(MorsegramPrefs.KEY_HIDDEN_PIN_RETRY_AT, 0);
        final long left = retryAt - System.currentTimeMillis();
        return left > 0 ? left : 0;
    }

    /**
     * Verifica il PIN (PBKDF2 sincrono come SharedConfig.checkPasscode, ~100 ms) e in caso di successo apre la
     * sessione. Backoff: dal 3° errore 5/10/15/20/25/30 s. Thread UI.
     */
    public static boolean verify(String pin) {
        if (pin == null || !hasPin() || retryInMs() > 0) {
            return false;
        }
        boolean ok = false;
        try {
            final byte[] salt = Base64.decode(prefs().getString(MorsegramPrefs.KEY_HIDDEN_PIN_SALT, ""), Base64.NO_WRAP);
            final String expected = prefs().getString(MorsegramPrefs.KEY_HIDDEN_PIN_HASH, "");
            final String actual = Utilities.bytesToHex(Utilities.computePBKDF2(pin.getBytes(StandardCharsets.UTF_8), salt));
            ok = MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (ok) {
            prefs().edit().putInt(MorsegramPrefs.KEY_HIDDEN_PIN_TRIES, 0).putLong(MorsegramPrefs.KEY_HIDDEN_PIN_RETRY_AT, 0).apply();
            unlock();
        } else {
            final int tries = prefs().getInt(MorsegramPrefs.KEY_HIDDEN_PIN_TRIES, 0) + 1;
            long retryMs = 0;
            if (tries >= 3) {
                retryMs = Math.min(30000L, 5000L * (tries - 2));
            }
            prefs().edit().putInt(MorsegramPrefs.KEY_HIDDEN_PIN_TRIES, tries)
                    .putLong(MorsegramPrefs.KEY_HIDDEN_PIN_RETRY_AT, retryMs > 0 ? System.currentTimeMillis() + retryMs : 0).apply();
        }
        return ok;
    }

    /* ================================================================================== biometria */

    public static boolean isBiometricEnabled() {
        return prefs().getBoolean(MorsegramPrefs.KEY_HIDDEN_BIOMETRIC, true);
    }

    public static void setBiometricEnabled(boolean enabled) {
        prefs().edit().putBoolean(MorsegramPrefs.KEY_HIDDEN_BIOMETRIC, enabled).apply();
        if (enabled && Build.VERSION.SDK_INT >= 23) {
            try {
                FingerprintController.checkKeyReady(false);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /**
     * Biometria forte disponibile (stesso check di PasscodeView 1253); la chiave upstream 'tmessages_passcode' è
     * usata solo per rilevare impronte cambiate: se non esiste (nessun passcode Telegram) il prompt basta da solo.
     */
    public static boolean canUseBiometric() {
        if (Build.VERSION.SDK_INT < 23) {
            return false;
        }
        try {
            final Context ctx = ApplicationLoader.applicationContext;
            if (BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
                return false;
            }
            return !FingerprintController.isKeyReady() || !FingerprintController.checkDeviceFingerprintsChanged();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /* =================================================================================== sessione */

    public static int timeoutSeconds() {
        return prefs().getInt(MorsegramPrefs.KEY_HIDDEN_LOCK_TIMEOUT, DEFAULT_TIMEOUT_SECONDS);
    }

    public static void setTimeoutSeconds(int seconds) {
        prefs().edit().putInt(MorsegramPrefs.KEY_HIDDEN_LOCK_TIMEOUT, seconds).apply();
    }

    /** Etichetta leggibile del timeout (impostazioni). */
    public static String timeoutLabel(int seconds) {
        if (seconds == TIMEOUT_IMMEDIATELY) {
            return LocaleController.getString("MorsegramAutoLockNow");
        } else if (seconds == TIMEOUT_NEVER) {
            return LocaleController.getString("MorsegramAutoLockNever");
        } else if (seconds < 3600) {
            return LocaleController.formatString("MorsegramAutoLockMinutes", 0, seconds / 60);
        }
        return LocaleController.formatString("MorsegramAutoLockHours", 0, seconds / 3600);
    }

    private static boolean expired() {
        final long p = pausedAt;
        if (p == 0) {
            return false;
        }
        final int t = timeoutSeconds();
        return t >= 0 && SystemClock.elapsedRealtime() - p >= t * 1000L;
    }

    /** Sezione bloccata: PIN impostato e nessuna sessione valida. Senza PIN la sezione non è protetta. */
    public static boolean isLocked() {
        return hasPin() && (!unlocked || expired());
    }

    /** Apre la sessione (dopo PIN/biometria corretti). Thread UI. */
    public static void unlock() {
        unlocked = true;
        pausedAt = 0;
        cancelScheduledLock();
        notifyListeners(false);
    }

    /** Chiude la sessione e i fragment delle chat nascoste aperti (come un 'indietro' dell'utente). Thread UI. */
    public static void lock() {
        unlocked = false;
        cancelScheduledLock();
        try {
            closeHiddenFragments();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        setSecure(false);
        notifyListeners(true);
    }

    public static void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void notifyListeners(boolean locked) {
        for (int i = listeners.size() - 1; i >= 0; i--) {
            try {
                listeners.get(i).onHiddenLockChanged(locked);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /** UI di sblocco alternativa (HiddenLockActivity); null = dialoghi di default. */
    public static void setPresenter(Presenter p) {
        presenter = p;
    }

    /** UI di setup/cambio PIN alternativa (HiddenPinDialog, S6-UI); null = dialoghi in due passi di default. */
    public static void setSetupPresenter(Presenter p) {
        setupPresenter = p;
    }

    /* ================================================================================== lifecycle */

    /** MorsegramApp.onCreate: auto-lock in background. */
    public static void install(Application app) {
        if (installed || app == null) {
            return;
        }
        installed = true;
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                cancelScheduledLock();
                if (unlocked && expired()) {
                    lock();
                }
                pausedAt = 0;
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                if (activity.isChangingConfigurations()) {
                    return;
                }
                pausedAt = SystemClock.elapsedRealtime();
                if (!unlocked) {
                    return;
                }
                final int t = timeoutSeconds();
                if (t == TIMEOUT_IMMEDIATELY) {
                    lock();
                } else if (t > 0) {
                    cancelScheduledLock();
                    lockRunnable = () -> {
                        lockRunnable = null;
                        if (unlocked && pausedAt != 0) {
                            lock();
                        }
                    };
                    AndroidUtilities.runOnUIThread(lockRunnable, t * 1000L);
                }
            }

            @Override public void onActivityCreated(@NonNull Activity activity, Bundle savedInstanceState) { }
            @Override public void onActivityStarted(@NonNull Activity activity) { }
            @Override public void onActivityStopped(@NonNull Activity activity) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) { }
            @Override public void onActivityDestroyed(@NonNull Activity activity) { }
        });
    }

    private static void cancelScheduledLock() {
        if (lockRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(lockRunnable);
            lockRunnable = null;
        }
    }

    /** FLAG_SECURE sulla finestra principale mentre una chat nascosta è aperta (tolto da lock()). */
    public static void setSecure(boolean on) {
        if (secure == on) {
            return;
        }
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity == null) {
            return;
        }
        try {
            final Window w = activity.getWindow();
            if (on) {
                w.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            }
            secure = on;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** true se il fragment mostra contenuto di una chat nascosta o è una pagina della sezione. */
    public static boolean isHiddenFragment(BaseFragment f) {
        if (f == null) {
            return false;
        }
        if (f instanceof HiddenFragment) {
            return true;
        }
        final int account = f.getCurrentAccount();
        if (f instanceof ChatActivity) {
            return HiddenChats.isHidden(account, ((ChatActivity) f).getDialogId());
        } else if (f instanceof TopicsFragment) {
            return HiddenChats.isHidden(account, ((TopicsFragment) f).getDialogId());
        } else if (f instanceof ProfileActivity) {
            return HiddenChats.isHidden(account, ((ProfileActivity) f).getDialogId());
        }
        return false;
    }

    /**
     * true se, oltre a {@code except}, resta aperto un fragment di chat nascosta o della sezione (FLAG_SECURE va
     * mantenuto). Sola lettura sui tre INavigationLayout e sul RightSlidingDialogContainer. Thread UI.
     */
    public static boolean anyHiddenFragmentOpen(BaseFragment except) {
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity == null) {
            return false;
        }
        try {
            final INavigationLayout[] layouts = {activity.getActionBarLayout(), activity.getRightActionBarLayout(), activity.getLayersActionBarLayout()};
            for (INavigationLayout layout : layouts) {
                if (layout == null) {
                    continue;
                }
                final List<BaseFragment> stack = layout.getFragmentStack();
                if (stack == null) {
                    continue;
                }
                for (BaseFragment f : new ArrayList<>(stack)) {
                    if (f == null || f == except) {
                        continue;
                    }
                    if (f instanceof DialogsActivity) {
                        final DialogsActivity da = (DialogsActivity) f;
                        if (da.rightSlidingDialogContainer != null && da.rightSlidingDialogContainer.hasFragment() && isHiddenFragment(da.rightSlidingDialogContainer.getFragment())) {
                            return true;
                        }
                    }
                    if (isHiddenFragment(f)) {
                        return true;
                    }
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return false;
    }

    /**
     * Rimuove dai tre INavigationLayout di LaunchActivity (e dal RightSlidingDialogContainer della lista chat, Fold
     * aperto) i fragment delle chat nascoste e quelli marcati {@link HiddenFragment}. Thread UI.
     */
    private static void closeHiddenFragments() {
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity == null) {
            return;
        }
        final INavigationLayout[] layouts = {activity.getActionBarLayout(), activity.getRightActionBarLayout(), activity.getLayersActionBarLayout()};
        for (INavigationLayout layout : layouts) {
            if (layout == null) {
                continue;
            }
            final List<BaseFragment> stack = layout.getFragmentStack();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            final ArrayList<BaseFragment> copy = new ArrayList<>(stack);
            for (BaseFragment f : copy) {
                if (f instanceof DialogsActivity) {
                    try {
                        final DialogsActivity da = (DialogsActivity) f;
                        if (da.rightSlidingDialogContainer != null && da.rightSlidingDialogContainer.hasFragment() && isHiddenFragment(da.rightSlidingDialogContainer.getFragment())) {
                            da.rightSlidingDialogContainer.finishPreview();
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                } else if (isHiddenFragment(f)) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("morsegram hidden lock: close " + f.getClass().getSimpleName());
                    }
                    layout.removeFragmentFromStack(f, true);
                }
            }
        }
    }

    /* =============================================================================== UI di sblocco */

    /**
     * Autenticazione: se la sezione non è bloccata esegue subito onSuccess; altrimenti biometria (se abilitata e
     * disponibile) con ripiego sul PIN. {@link #unlock()} viene chiamato prima di onSuccess. Thread UI.
     */
    public static void authenticate(Activity activity, Runnable onSuccess, Runnable onCancel) {
        if (!isLocked()) {
            if (onSuccess != null) {
                onSuccess.run();
            }
            return;
        }
        if (activity == null) {
            activity = LaunchActivity.instance;
        }
        if (activity == null || activity.isFinishing() || authenticating) {
            if (onCancel != null) {
                onCancel.run();
            }
            return;
        }
        authenticating = true;
        final Runnable success = () -> {
            authenticating = false;
            if (onSuccess != null) {
                onSuccess.run();
            }
        };
        final Runnable cancel = () -> {
            authenticating = false;
            if (onCancel != null) {
                onCancel.run();
            }
        };
        final Presenter p = presenter;
        try {
            if (p != null) {
                p.present(activity, success, cancel);
            } else if (isBiometricEnabled() && canUseBiometric() && activity instanceof FragmentActivity) {
                final Activity host = activity;
                promptBiometric((FragmentActivity) activity, success, () -> showPinDialog(host, success, cancel));
            } else {
                showPinDialog(activity, success, cancel);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            cancel.run();
        }
    }

    /** Pattern di PasscodeView.checkFingerprint: executor main, BIOMETRIC_STRONG, negativo = 'Usa PIN'. */
    public static void promptBiometric(FragmentActivity activity, Runnable onSuccess, Runnable onFallback) {
        final Executor executor = ContextCompat.getMainExecutor(activity);
        final BiometricPrompt prompt = new BiometricPrompt(activity, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errMsgId, @NonNull CharSequence errString) {
                FileLog.d("HiddenLock onAuthenticationError " + errMsgId + " \"" + errString + "\"");
                if (onFallback != null) {
                    onFallback.run();
                }
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                FileLog.d("HiddenLock onAuthenticationSucceeded");
                unlock();
                if (onSuccess != null) {
                    onSuccess.run();
                }
            }

            @Override
            public void onAuthenticationFailed() {
                FileLog.d("HiddenLock onAuthenticationFailed");
            }
        });
        final BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(LocaleController.getString("MorsegramUnlock"))
                .setSubtitle(LocaleController.getString("MorsegramHiddenChats"))
                .setNegativeButtonText(LocaleController.getString("MorsegramUsePin"))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
        prompt.authenticate(promptInfo);
    }

    /** Campo PIN numerico (tastiera numerica, password): condiviso con HiddenLockSheet / HiddenPinDialog. */
    static EditTextBoldCursor pinField(Context context) {
        final EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        editText.setHintTextColor(Theme.getColor(Theme.key_groupcreate_hintText));
        editText.setHint(LocaleController.getString("MorsegramPinTitle"));
        editText.setGravity(Gravity.CENTER);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(PIN_MAX_LENGTH)});
        editText.setSingleLine(true);
        editText.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(11), AndroidUtilities.dp(16), AndroidUtilities.dp(11));
        editText.setCursorWidth(1.5f);
        editText.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(22));
        bg.setColor(Theme.multAlpha(Theme.getColor(Theme.key_dialogTextBlack), 0.06f));
        editText.setBackground(bg);
        return editText;
    }

    static TextView infoView(Context context) {
        final TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tv.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        tv.setGravity(Gravity.CENTER);
        tv.setVisibility(View.GONE);
        return tv;
    }

    /**
     * Dialogo PIN di default (tastiera numerica, backoff visibile). Resta aperto finché il PIN non è corretto o
     * l'utente annulla. Thread UI.
     */
    public static void showPinDialog(Activity activity, Runnable onSuccess, Runnable onCancel) {
        if (activity == null) {
            activity = LaunchActivity.instance;
        }
        if (activity == null) {
            if (onCancel != null) {
                onCancel.run();
            }
            return;
        }
        final Context context = activity;
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString("MorsegramUnlock"));
        builder.setMessage(LocaleController.getString("MorsegramHiddenChats"));
        final EditTextBoldCursor editText = pinField(context);
        final TextView info = infoView(context);
        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 9, 20, 4));
        container.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 9));
        builder.makeCustomMaxHeight();
        builder.setView(container);
        builder.setWidth(Math.min(AndroidUtilities.dp(320), AndroidUtilities.displaySize.x * 85 / 100));
        final boolean[] done = new boolean[1];
        final AlertDialog[] dialog = new AlertDialog[1];
        final Runnable attempt = () -> {
            final long wait = retryInMs();
            if (wait > 0) {
                info.setText(LocaleController.formatString("MorsegramPinRetryIn", 0, (int) Math.ceil(wait / 1000.0)));
                info.setVisibility(View.VISIBLE);
                AndroidUtilities.shakeView(editText);
                return;
            }
            if (verify(editText.getText().toString())) {
                done[0] = true;
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } else {
                editText.setText("");
                final long after = retryInMs();
                info.setText(after > 0 ? LocaleController.formatString("MorsegramPinRetryIn", 0, (int) Math.ceil(after / 1000.0)) : LocaleController.getString("MorsegramPinWrong"));
                info.setVisibility(View.VISIBLE);
                AndroidUtilities.shakeView(editText);
            }
        };
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attempt.run();
                return true;
            }
            return false;
        });
        builder.setPositiveButton(LocaleController.getString("MorsegramUnlock"), (d, which) -> attempt.run());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, which) -> d.dismiss());
        dialog[0] = builder.create();
        dialog[0].setDismissDialogByButtons(false);
        dialog[0].setOnDismissListener(d -> {
            AndroidUtilities.hideKeyboard(editText);
            if (!done[0] && onCancel != null) {
                onCancel.run();
            }
        });
        dialog[0].setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
        dialog[0].show();
    }

    /**
     * Setup/cambio PIN di default in due passi (inserisci, conferma). Al termine il PIN è salvato e la sessione
     * aperta. Thread UI.
     */
    public static void setupPin(Activity activity, Runnable onDone, Runnable onCancel) {
        final Presenter p = setupPresenter;
        if (p != null) {
            if (activity == null) {
                activity = LaunchActivity.instance;
            }
            if (activity == null || activity.isFinishing()) {
                if (onCancel != null) {
                    onCancel.run();
                }
                return;
            }
            try {
                p.present(activity, onDone, onCancel);
                return;
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        setupStep(activity, null, onDone, onCancel);
    }

    private static void setupStep(Activity activity, String first, Runnable onDone, Runnable onCancel) {
        if (activity == null) {
            activity = LaunchActivity.instance;
        }
        if (activity == null) {
            if (onCancel != null) {
                onCancel.run();
            }
            return;
        }
        final Activity host = activity;
        final Context context = activity;
        final AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString(first == null ? "MorsegramSetPin" : "MorsegramPinConfirm"));
        builder.setMessage(LocaleController.getString(first == null ? "MorsegramSetPinInfo" : "MorsegramPinConfirmInfo"));
        final EditTextBoldCursor editText = pinField(context);
        final TextView info = infoView(context);
        final LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 9, 20, 4));
        container.addView(info, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 20, 0, 20, 9));
        builder.makeCustomMaxHeight();
        builder.setView(container);
        builder.setWidth(Math.min(AndroidUtilities.dp(320), AndroidUtilities.displaySize.x * 85 / 100));
        final boolean[] done = new boolean[1];
        final AlertDialog[] dialog = new AlertDialog[1];
        final Runnable next = () -> {
            final String pin = editText.getText().toString();
            if (pin.length() < PIN_MIN_LENGTH) {
                info.setText(LocaleController.formatString("MorsegramPinTooShort", 0, PIN_MIN_LENGTH));
                info.setVisibility(View.VISIBLE);
                AndroidUtilities.shakeView(editText);
                return;
            }
            if (first == null) {
                done[0] = true;
                dialog[0].dismiss();
                setupStep(host, pin, onDone, onCancel);
            } else if (first.equals(pin)) {
                done[0] = true;
                dialog[0].dismiss();
                if (setPin(pin)) {
                    if (onDone != null) {
                        onDone.run();
                    }
                } else if (onCancel != null) {
                    onCancel.run();
                }
            } else {
                done[0] = true;
                dialog[0].dismiss();
                // PIN diversi: si ricomincia dal primo passo con l'avviso
                setupStep(host, null, onDone, onCancel);
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        android.widget.Toast.makeText(host, LocaleController.getString("MorsegramPinMismatch"), android.widget.Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignore) {
                    }
                }, 150);
            }
        };
        editText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                next.run();
                return true;
            }
            return false;
        });
        builder.setPositiveButton(LocaleController.getString(first == null ? R.string.Next : R.string.Done), (d, which) -> next.run());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, which) -> d.dismiss());
        dialog[0] = builder.create();
        dialog[0].setDismissDialogByButtons(false);
        dialog[0].setOnDismissListener(d -> {
            AndroidUtilities.hideKeyboard(editText);
            if (!done[0] && onCancel != null) {
                onCancel.run();
            }
        });
        dialog[0].setOnShowListener(d -> {
            editText.requestFocus();
            AndroidUtilities.showKeyboard(editText);
        });
        dialog[0].show();
    }

    /**
     * Prerequisito per nascondere: PIN impostato (setup se manca). Nascondere non richiede lo sblocco (come
     * archiviare); mostrare sì ({@link #authenticate}). Thread UI.
     */
    public static void ensurePin(Activity activity, Runnable then, Runnable onCancel) {
        if (hasPin()) {
            if (then != null) {
                then.run();
            }
        } else {
            setupPin(activity, then, onCancel);
        }
    }
}

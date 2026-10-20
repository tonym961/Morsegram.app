/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.carlabs.car;

import android.Manifest;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.CarToast;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.Collections;

import it.iotatec.morsegram.carlabs.voice.CarVoiceLabels;
import it.iotatec.morsegram.carlabs.voice.CarVoiceRecorder;

/**
 * Registra e invia un vocale dall'head unit. Si apre già registrando: un tap apre la schermata, un tap invia.
 *
 * <p>La registrazione parte <b>una volta sola</b>, all'apertura (o dopo il permesso microfono concesso): se il
 * guidatore guarda le mappe e poi rientra, la schermata ritrova la clip già registrata in "Pronto · 0:12" e il
 * microfono <b>non</b> si riapre da sé. Solo {@code Annulla} e la chiusura della schermata buttano la clip.</p>
 *
 * <p>Nessun testo da leggere: lo stato è una riga sola ("Registrazione… 0:20" / "Pronto · 0:12") e le azioni sono
 * due, {@code Invia} (primaria) e {@code Annulla}. Il tetto dei 60 s ferma la registrazione da sé e tiene la
 * clip: <b>non invia da solo</b>, perché un invio automatico spedirebbe un messaggio che il guidatore non ha
 * finito di dettare, e un messaggio inviato non si ritira.</p>
 *
 * <p><b>Perché il cronometro non scorre al secondo</b>: l'host conta 5 template per task e considera "refresh"
 * (gratis) solo un MessageTemplate con titolo e messaggio identici. Un contatore al secondo sarebbe un template
 * nuovo ogni secondo e l'host ci staccherebbe dopo 5. Quindi il tempo si aggiorna ogni
 * {@link #ELAPSED_STEP_MS} (2 refresh in tutto) e l'avviso di fine imminente è un {@link CarToast}, che non
 * consuma quota.</p>
 */
public class RecordScreen extends Screen implements DefaultLifecycleObserver, CarVoiceRecorder.Listener {

    /** Passo di aggiornamento del tempo mostrato: 60 s / 20 s = 2 refresh, dentro la quota di 5 template. */
    private static final long ELAPSED_STEP_MS = 20_000L;
    /** Quanto prima del tetto avvisare (toast, non template). */
    private static final long WARN_BEFORE_END_MS = 10_000L;

    private static final int UI_RECORDING = 0;
    private static final int UI_READY = 1;
    private static final int UI_PERMISSION = 2;
    private static final int UI_ERROR = 3;

    private final int account;
    private final long dialogId;
    private final String title;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private int ui = UI_RECORDING;
    private int error;
    private long recordedMs;
    private boolean sending;
    private boolean permissionRequested;
    private boolean warnScheduled;
    private int elapsedTicks;
    /** La registrazione parte una volta sola, su apertura della schermata: mai di nuovo da sé a un ritorno. */
    private boolean autoStarted;

    public RecordScreen(@NonNull CarContext carContext, int account, long dialogId) {
        super(carContext);
        this.account = account;
        this.dialogId = dialogId;
        this.title = titleOf(account, dialogId);
        getLifecycle().addObserver(this);
    }

    // ===== lifecycle =====

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        // SOLO alla prima apertura: un tap ha chiesto di registrare. Un ritorno (il guidatore ha guardato le
        // mappe e poi è rientrato nell'app) non deve riaprire il microfono da sé, e deve ritrovare la clip di
        // prima pronta da inviare — la riattivazione del microfono senza un tap, in marcia, non si fa.
        if (!autoStarted) {
            startRecording();
        }
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        // schermata non più visibile: si chiude il microfono ma la clip resta (torna in "Pronto · 0:12");
        // il listener resta agganciato, così un invio già in volo arriva comunque a destinazione
        handler.removeCallbacksAndMessages(null);
        CarVoiceRecorder.getInstance().suspendCapture(this);
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        // schermata chiusa per davvero (indietro, task finito): qui sì, microfono chiuso e clip buttata
        handler.removeCallbacksAndMessages(null);
        CarVoiceRecorder.getInstance().detach(this);
    }

    private void startRecording() {
        autoStarted = true;
        ui = UI_RECORDING;
        error = 0;
        elapsedTicks = 0;
        warnScheduled = false;
        CarVoiceRecorder.getInstance().start(getCarContext(), account, dialogId, this);
    }

    // ===== template =====

    @NonNull
    @Override
    public Template onGetTemplate() {
        switch (ui) {
            case UI_PERMISSION:
                return new MessageTemplate.Builder(LocaleController.getString("MorsegramCarMicDenied"))
                        .setTitle(title)
                        .setHeaderAction(Action.BACK)
                        .addAction(new Action.Builder()
                                .setTitle(LocaleController.getString("MorsegramCarAllow"))
                                .setFlags(Action.FLAG_PRIMARY)
                                .setOnClickListener(this::requestMicPermission)
                                .build())
                        .build();
            case UI_ERROR:
                return new MessageTemplate.Builder(errorText(error))
                        .setTitle(title)
                        .setHeaderAction(Action.BACK)
                        .addAction(new Action.Builder()
                                .setTitle(LocaleController.getString("MorsegramCarClose"))
                                .setFlags(Action.FLAG_PRIMARY)
                                .setOnClickListener(this::finish)
                                .build())
                        .build();
            case UI_READY:
                return new MessageTemplate.Builder(
                        LocaleController.formatString("MorsegramCarVoiceReady", 0,
                                CarVoiceLabels.mmss((int) (recordedMs / 1000))))
                        .setTitle(title)
                        .setHeaderAction(Action.BACK)
                        .addAction(sendAction())
                        .addAction(cancelAction())
                        .build();
            case UI_RECORDING:
            default:
                return new MessageTemplate.Builder(recordingText())
                        .setTitle(title)
                        .setHeaderAction(Action.BACK)
                        .addAction(sendAction())
                        .addAction(cancelAction())
                        .build();
        }
    }

    private String recordingText() {
        long elapsed = CarVoiceRecorder.getInstance().getElapsedMs();
        if (sending) {
            return LocaleController.getString("MorsegramCarVoiceSending");
        }
        if (elapsed < 1000) {
            return LocaleController.getString("MorsegramCarRecording");
        }
        return LocaleController.formatString("MorsegramCarRecordingElapsed", 0,
                CarVoiceLabels.mmss((int) (elapsed / 1000)));
    }

    private Action sendAction() {
        return new Action.Builder()
                .setTitle(LocaleController.getString("MorsegramCarSend"))
                .setFlags(Action.FLAG_PRIMARY)
                .setEnabled(!sending)
                .setOnClickListener(this::onSendClicked)
                .build();
    }

    private Action cancelAction() {
        return new Action.Builder()
                .setTitle(LocaleController.getString("MorsegramCarCancel"))
                .setOnClickListener(this::onCancelClicked)
                .build();
    }

    // ===== azioni (un tap) =====

    private void onSendClicked() {
        if (sending) {
            return;
        }
        sending = true;
        handler.removeCallbacksAndMessages(null);
        // la schermata resta finché onVoiceSent/onFailed non arriva: finish() qui annullerebbe l'invio in volo
        CarVoiceRecorder.getInstance().send();
        if (sending) {
            invalidate();
        }
    }

    private void onCancelClicked() {
        handler.removeCallbacksAndMessages(null);
        CarVoiceRecorder.getInstance().cancel();
        finish();
    }

    private void requestMicPermission() {
        if (permissionRequested) {
            return;
        }
        permissionRequested = true;
        toast(LocaleController.getString("MorsegramCarCheckPhone"));
        try {
            getCarContext().requestPermissions(
                    Collections.singletonList(Manifest.permission.RECORD_AUDIO),
                    (granted, rejected) -> {
                        permissionRequested = false;
                        if (granted.contains(Manifest.permission.RECORD_AUDIO)) {
                            startRecording();
                        }
                    });
        } catch (Throwable t) {
            permissionRequested = false;
        }
    }

    // ===== callback del recorder =====

    @Override
    public void onRecordingStarted() {
        ui = UI_RECORDING;
        scheduleTick();
        invalidate();
    }

    @Override
    public void onRecordingStopped(long durationMs, boolean capReached) {
        handler.removeCallbacksAndMessages(null);
        recordedMs = durationMs;
        ui = UI_READY;
        if (capReached) {
            toast(LocaleController.getString("MorsegramCarVoiceMax"));
        }
        invalidate();
    }

    @Override
    public void onVoiceSent() {
        sending = false;
        toast(LocaleController.getString("MorsegramCarVoiceSent"));
        finish();
    }

    @Override
    public void onFailed(int code) {
        handler.removeCallbacksAndMessages(null);
        sending = false;
        error = code;
        ui = code == CarVoiceRecorder.ERR_NO_PERMISSION ? UI_PERMISSION : UI_ERROR;
        invalidate();
    }

    // ===== tempo mostrato =====

    private void scheduleTick() {
        long elapsed = CarVoiceRecorder.getInstance().getElapsedMs();
        long nextStep = (elapsedTicks + 1) * ELAPSED_STEP_MS;
        long warnAt = CarVoiceRecorder.MAX_DURATION_MS - WARN_BEFORE_END_MS;
        if (nextStep < CarVoiceRecorder.MAX_DURATION_MS) {
            handler.postDelayed(this::onElapsedTick, Math.max(1000L, nextStep - elapsed));
        }
        if (!warnScheduled && warnAt > elapsed) {
            warnScheduled = true;
            handler.postDelayed(() -> {
                if (CarVoiceRecorder.getInstance().isRecording() && !sending) {
                    toast(LocaleController.formatString("MorsegramCarVoiceLeft", 0, WARN_BEFORE_END_MS / 1000));
                }
            }, warnAt - elapsed);
        }
    }

    private void onElapsedTick() {
        if (!CarVoiceRecorder.getInstance().isRecording() || sending) {
            return;
        }
        elapsedTicks++;
        invalidate();
        scheduleTick();
    }

    // ===== helper =====

    private void toast(@Nullable String text) {
        if (text == null) {
            return;
        }
        try {
            CarToast.makeText(getCarContext(), text, CarToast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    private static String errorText(int code) {
        switch (code) {
            case CarVoiceRecorder.ERR_BUSY_PHONE:
                return LocaleController.getString("MorsegramCarVoiceBusy");
            case CarVoiceRecorder.ERR_TOO_SHORT:
                return LocaleController.getString("MorsegramCarVoiceShort");
            case CarVoiceRecorder.ERR_NO_MIC:
                return LocaleController.getString("MorsegramCarNoMic");
            case CarVoiceRecorder.ERR_HIDDEN:
                return LocaleController.getString("MorsegramCarChatOff");
            default:
                return LocaleController.getString("MorsegramCarVoiceError");
        }
    }

    @NonNull
    private static String titleOf(int account, long dialogId) {
        try {
            MessagesController controller = MessagesController.getInstance(account);
            if (DialogObject.isUserDialog(dialogId)) {
                TLRPC.User user = controller.getUser(dialogId);
                if (user != null) {
                    if (UserObject.isUserSelf(user)) {
                        return LocaleController.getString(R.string.SavedMessages);
                    }
                    String name = ContactsController.formatName(user.first_name, user.last_name);
                    if (name != null) {
                        return name;
                    }
                }
            } else {
                TLRPC.Chat chat = controller.getChat(-dialogId);
                if (chat != null && chat.title != null) {
                    return chat.title;
                }
            }
        } catch (Throwable ignored) {
        }
        return LocaleController.getString("MorsegramCarVoiceAction");
    }
}

/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.LaunchActivity;

import java.io.File;

import it.iotatec.morsegram.theme.MorsegramPalette;

/**
 * Registrazione del widget VOCALE RAPIDO. Attività propria (dichiarata nel manifest del modulo app), niente
 * servizio e niente registrazione in sottofondo: si registra solo mentre questa schermata è in primo piano, e
 * uscire annulla.
 * <p>
 * LA CONFERMA DELL'INVIO LA CHIEDE QUESTA SCHERMATA, NON L'APP. "Interrompi" ferma soltanto la registrazione e
 * porta allo stato di REVISIONE (riascolto, "Rifai", "Annulla", "Invia a &lt;chat&gt;"): il file parte solo al
 * tocco di "Invia a &lt;chat&gt;". Il motivo è che la consegna è un {@code Intent.ACTION_SEND} con
 * {@code dialogId} + {@code hash} e quel percorso in upstream NON chiede nulla: LaunchActivity.java:3109-3112
 * vede {@code dialogId != 0} e chiama {@code didSelectDialogs(...)}, che per i documenti e gli audio arriva
 * diritto a {@code SendMessagesHelper.prepareSendingDocuments} (LaunchActivity.java:6434-6438) e spedisce (solo
 * immagini e video passano da un editor con un pulsante di invio). Anche senza {@code dialogId} non cambierebbe:
 * la scelta della chat nel selettore rientra nello stesso {@code didSelectDialogs}, che invia alla selezione.
 * <p>
 * Per la stessa ragione il tetto di {@link #MAX_DURATION_MS} FERMA la registrazione e mostra la revisione: non
 * invia nulla da solo, altrimenti un vocale avviato per sbaglio dal widget partirebbe dopo 120 s con il telefono
 * in tasca. Perdere il primo piano (onPause) o toccare fuori dalla scheda annulla e cancella il file, anche
 * durante la revisione.
 * <p>
 * Formato: su API 29+ Ogg/Opus, che è esattamente ciò che
 * {@code SendMessagesHelper.prepareSendingDocumentInternal} riconosce come messaggio VOCALE
 * (SendMessagesHelper.java:9480 → attributeAudio.voice = true); sotto API 29 MediaRecorder non sa fare Opus e
 * si ripiega su m4a/AAC, che arriva come file audio.
 */
public class VoiceRecordActivity extends Activity {

    private static final String EXTRA_WIDGET_ID = "morsegram_widget_id";
    private static final String EXTRA_ACCOUNT = "morsegram_account";
    private static final String EXTRA_DIALOG = "morsegram_dialog";
    private static final int REQUEST_RECORD_AUDIO = 7781;
    private static final long MAX_DURATION_MS = 120_000L;
    private static final long MIN_DURATION_MS = 700L;
    private static final String DIR = "morsegram_voice";
    private static final String AUTHORITY_SUFFIX = ".provider";

    private int account;
    private long dialogId;
    private String chatName;
    private boolean dark;
    private File file;
    private MediaRecorder recorder;
    private MediaPlayer player;
    private long startedAt;
    private long recordedMs;
    /** true dopo "Interrompi": registrazione finita, in attesa che l'utente decida. Niente è stato inviato. */
    private boolean reviewing;
    private boolean handedOff;
    private TextView timerView;
    private TextView hintView;
    /** La riga dei pulsanti: ricostruita al passaggio fra registrazione e revisione. */
    private LinearLayout controls;
    /** Riga del solo "Invia a <chat>": sta su una riga sua per avere spazio per il nome. */
    private LinearLayout sendRow;
    private TextView playView;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (recorder == null) {
                return;
            }
            final long elapsed = SystemClock.elapsedRealtime() - startedAt;
            if (elapsed >= MAX_DURATION_MS) {
                // il tetto ferma la registrazione e chiede, non invia: un vocale avviato per sbaglio non parte da solo
                stopForReview();
                return;
            }
            updateTimer(elapsed);
            AndroidUtilities.runOnUIThread(this, 200);
        }
    };

    private final Runnable playTick = new Runnable() {
        @Override
        public void run() {
            final MediaPlayer current = player;
            if (current == null) {
                return;
            }
            try {
                updateTimer(current.getCurrentPosition());
            } catch (Throwable ignore) {
            }
            AndroidUtilities.runOnUIThread(this, 200);
        }
    };

    static Intent intent(Context context, int appWidgetId, int account, long dialogId) {
        return new Intent(context, VoiceRecordActivity.class)
                .setData(MorsegramWidgets.unique(MorsegramWidgets.TYPE_VOICE, appWidgetId, "rec"))
                .putExtra(EXTRA_WIDGET_ID, appWidgetId)
                .putExtra(EXTRA_ACCOUNT, account)
                .putExtra(EXTRA_DIALOG, dialogId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ApplicationLoader.postInitApplication();
        final Intent intent = getIntent();
        final int widgetId = intent == null ? 0 : intent.getIntExtra(EXTRA_WIDGET_ID, 0);
        account = intent == null ? 0 : intent.getIntExtra(EXTRA_ACCOUNT, 0);
        dialogId = intent == null ? 0 : intent.getLongExtra(EXTRA_DIALOG, 0);
        // la chat può essere stata nascosta dopo la configurazione: qui non si registra nulla per una nascosta
        chatName = dialogId == 0 ? null : WidgetDialog.nameOnly(widgetId, account, dialogId);
        if (dialogId == 0 || chatName == null) {
            finish();
            return;
        }
        setContentView(buildView(chatName));
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        } else {
            start();
        }
    }

    private View buildView(String name) {
        dark = MorsegramWidgets.isDark(0);
        final FrameLayout dim = new FrameLayout(this);
        dim.setBackgroundColor(0x99000000);
        dim.setOnClickListener(v -> cancel());

        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClickable(true);
        final GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(AndroidUtilities.dp(20));
        background.setColor(dark ? MorsegramPalette.DARK_1 : MorsegramPalette.WHITE);
        card.setBackground(background);
        card.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(22), AndroidUtilities.dp(24), AndroidUtilities.dp(14));

        final TextView title = text(MorsegramWidgets.str("MorsegramWidgetVoice", "Quick voice"), 18, MorsegramWidgets.titleColor(dark));
        final TextView target = text(name, 15, MorsegramWidgets.textColor(dark));
        timerView = text("0:00", 34, MorsegramWidgets.accentColor(dark));
        hintView = text("", 13, MorsegramWidgets.textColor(dark));

        card.addView(title, row());
        card.addView(target, row());
        final LinearLayout.LayoutParams timerParams = row();
        timerParams.topMargin = AndroidUtilities.dp(14);
        card.addView(timerView, timerParams);
        card.addView(hintView, row());

        controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.END);
        final LinearLayout.LayoutParams controlsParams = row();
        controlsParams.topMargin = AndroidUtilities.dp(6);
        card.addView(controls, controlsParams);

        sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL);
        sendRow.setGravity(Gravity.END);
        sendRow.setVisibility(View.GONE);
        card.addView(sendRow, row());

        showRecording();

        final FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(Math.min(AndroidUtilities.dp(320), AndroidUtilities.displaySize.x - AndroidUtilities.dp(40)), FrameLayout.LayoutParams.WRAP_CONTENT);
        cardParams.gravity = Gravity.CENTER;
        dim.addView(card, cardParams);
        return dim;
    }

    private static LinearLayout.LayoutParams row() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private TextView text(String value, int sizeSp, int color) {
        final TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        return view;
    }

    private TextView button(String label, boolean accent, boolean strong, View.OnClickListener action) {
        final TextView view = text(label, 15, accent ? MorsegramWidgets.accentColor(dark) : MorsegramWidgets.textColor(dark));
        view.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        view.setMaxLines(1);
        view.setEllipsize(TextUtils.TruncateAt.END);
        if (strong) {
            view.setTypeface(view.getTypeface(), Typeface.BOLD);
        }
        view.setOnClickListener(action);
        return view;
    }

    /* ===================================================================================== due stati */

    /** Sta registrando: si può solo annullare o interrompere. */
    private void showRecording() {
        reviewing = false;
        playView = null;
        hintView.setText(MorsegramWidgets.str("MorsegramWidgetVoiceHint", "Stop to listen to it and decide whether to send it"));
        controls.removeAllViews();
        controls.addView(button(MorsegramWidgets.str("MorsegramWidgetVoiceCancel", "Cancel"), false, false, v -> cancel()), wrap());
        controls.addView(button(MorsegramWidgets.str("MorsegramWidgetVoiceStop", "Stop"), true, false, v -> stopForReview()), wrap());
        sendRow.removeAllViews();
        sendRow.setVisibility(View.GONE);
    }

    /** Registrazione finita e NON inviata: riascolto, rifai, annulla, invia. Solo "Invia" consegna il file. */
    private void showReview() {
        reviewing = true;
        updateTimer(recordedMs);
        hintView.setText(MorsegramWidgets.str("MorsegramWidgetVoiceReview", "Listen to it, then send it or record it again"));
        controls.removeAllViews();
        playView = button(playLabel(), true, false, v -> togglePlayback());
        controls.addView(playView, wrap());
        controls.addView(button(MorsegramWidgets.str("MorsegramWidgetVoiceCancel", "Cancel"), false, false, v -> cancel()), wrap());
        controls.addView(button(MorsegramWidgets.str("MorsegramWidgetVoiceRedo", "Redo"), false, false, v -> redo()), wrap());
        sendRow.removeAllViews();
        sendRow.addView(button(sendLabel(), true, true, v -> handOff()), wrap());
        sendRow.setVisibility(View.VISIBLE);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private String playLabel() {
        return player != null
                ? MorsegramWidgets.str("MorsegramWidgetVoicePause", "Pause")
                : MorsegramWidgets.str("MorsegramWidgetVoicePlay", "Play");
    }

    private String sendLabel() {
        final String format = MorsegramWidgets.str("MorsegramWidgetVoiceSendTo", "Send to %1$s");
        try {
            return String.format(java.util.Locale.getDefault(), format, chatName);
        } catch (Throwable ignore) {
            return MorsegramWidgets.str("MorsegramWidgetVoiceSend", "Send");
        }
    }

    private void updateTimer(long elapsedMs) {
        if (timerView == null) {
            return;
        }
        final long seconds = Math.max(0, elapsedMs) / 1000;
        timerView.setText(String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            start();
        } else {
            toast(MorsegramWidgets.str("MorsegramWidgetVoiceNoPermission", "Microphone access is needed to record a voice message"));
            finish();
        }
    }

    /* ================================================================================= registrazione */

    private void start() {
        try {
            // files/cache/<DIR>: e' l'unico albero gia' coperto dal FileProvider di upstream
            // (res/xml/provider_paths.xml, <files-path name="cache" path="/cache/"/>), quindi non serve
            // dichiarare un secondo <provider> nel manifest — e non si potrebbe, perche' il merger abbina i
            // provider per android:name e due androidx.core.content.FileProvider entrerebbero in conflitto.
            final File dir = new File(new File(getFilesDir(), "cache"), DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                finish();
                return;
            }
            cleanOld(dir);
            final boolean opus = Build.VERSION.SDK_INT >= 29;
            file = new File(dir, "voice_" + System.currentTimeMillis() + (opus ? ".ogg" : ".m4a"));
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            if (opus) {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.OGG);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS);
                recorder.setAudioSamplingRate(48000);
                recorder.setAudioEncodingBitRate(32000);
            } else {
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                recorder.setAudioSamplingRate(44100);
                recorder.setAudioEncodingBitRate(64000);
            }
            recorder.setAudioChannels(1);
            recorder.setOutputFile(file.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            startedAt = SystemClock.elapsedRealtime();
            AndroidUtilities.runOnUIThread(tick, 200);
        } catch (Throwable e) {
            FileLog.e(e);
            release();
            toast(MorsegramWidgets.str("MorsegramWidgetVoiceFailed", "Could not record the voice message"));
            finish();
        }
    }

    /**
     * "Interrompi" e tetto dei {@link #MAX_DURATION_MS}: ferma la registrazione e passa alla revisione. NON
     * invia: il file resta sul dispositivo finché l'utente non tocca "Invia a &lt;chat&gt;".
     */
    private void stopForReview() {
        if (handedOff || reviewing) {
            return;
        }
        final long elapsed = startedAt == 0 ? 0 : SystemClock.elapsedRealtime() - startedAt;
        if (!release() || file == null || !file.exists() || file.length() == 0 || elapsed < MIN_DURATION_MS) {
            cancel();
            return;
        }
        recordedMs = elapsed;
        showReview();
    }

    /** Il solo punto che consegna il file all'app, raggiungibile solo dal tocco su "Invia a &lt;chat&gt;". */
    private void handOff() {
        if (handedOff || !reviewing || file == null || !file.exists() || file.length() == 0) {
            return;
        }
        stopPlayback();
        handedOff = true;
        try {
            final Uri uri = FileProvider.getUriForFile(this, getPackageName() + AUTHORITY_SUFFIX, file);
            final Intent send = new Intent(this, LaunchActivity.class)
                    .setAction(Intent.ACTION_SEND)
                    .setType(file.getName().endsWith(".ogg") ? "audio/ogg" : "audio/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra("dialogId", dialogId)
                    .putExtra("hash", SharedConfig.directShareHash)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(send);
        } catch (Throwable e) {
            FileLog.e(e);
            toast(MorsegramWidgets.str("MorsegramWidgetVoiceFailed", "Could not record the voice message"));
        }
        finish();
    }

    /** "Rifai": butta la registrazione e riparte da zero, senza uscire dalla schermata. */
    private void redo() {
        if (handedOff) {
            return;
        }
        stopPlayback();
        release();
        deleteFile();
        recordedMs = 0;
        updateTimer(0);
        showRecording();
        start();
    }

    private void cancel() {
        stopPlayback();
        release();
        deleteFile();
        finish();
    }

    /** @return true se lo stop è andato a buon fine (un MediaRecorder fermato troppo presto lancia). */
    private boolean release() {
        AndroidUtilities.cancelRunOnUIThread(tick);
        final MediaRecorder current = recorder;
        recorder = null;
        if (current == null) {
            return false;
        }
        boolean ok = true;
        try {
            current.stop();
        } catch (Throwable e) {
            ok = false;
        }
        try {
            current.release();
        } catch (Throwable ignore) {
        }
        return ok;
    }

    /* ======================================================================================= riascolto */

    private void togglePlayback() {
        if (player != null) {
            stopPlayback();
            return;
        }
        if (file == null || !file.exists() || file.length() == 0) {
            return;
        }
        try {
            final MediaPlayer created = new MediaPlayer();
            created.setDataSource(file.getAbsolutePath());
            created.setOnCompletionListener(mp -> stopPlayback());
            created.setOnErrorListener((mp, what, extra) -> {
                stopPlayback();
                return true;
            });
            created.prepare();
            created.start();
            player = created;
            if (playView != null) {
                playView.setText(playLabel());
            }
            AndroidUtilities.runOnUIThread(playTick, 200);
        } catch (Throwable e) {
            FileLog.e(e);
            stopPlayback();
            toast(MorsegramWidgets.str("MorsegramWidgetVoicePlayFailed", "Could not play the recording"));
        }
    }

    private void stopPlayback() {
        AndroidUtilities.cancelRunOnUIThread(playTick);
        final MediaPlayer current = player;
        player = null;
        if (current != null) {
            try {
                current.stop();
            } catch (Throwable ignore) {
            }
            try {
                current.release();
            } catch (Throwable ignore) {
            }
        }
        if (playView != null) {
            playView.setText(playLabel());
        }
        if (reviewing) {
            updateTimer(recordedMs);
        }
    }

    /* ========================================================================================== file */

    private void deleteFile() {
        if (file != null) {
            try {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (Throwable ignore) {
            }
            file = null;
        }
    }

    /** Il file consegnato all'app resta finché l'utente non conferma: si pulisce al giro dopo. */
    private static void cleanOld(File dir) {
        try {
            final File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            final long limit = System.currentTimeMillis() - 24L * 60 * 60 * 1000;
            for (File candidate : files) {
                if (candidate.isFile() && candidate.lastModified() < limit) {
                    //noinspection ResultOfMethodCallIgnored
                    candidate.delete();
                }
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // niente registrazione in sottofondo e niente revisione che sopravvive in sottofondo: se la schermata
        // perde il primo piano senza che l'utente abbia toccato "Invia a <chat>", si annulla e il file si cancella
        if (!handedOff && (recorder != null || reviewing)) {
            cancel();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
        release();
    }

    private void toast(String message) {
        try {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignore) {
        }
    }
}

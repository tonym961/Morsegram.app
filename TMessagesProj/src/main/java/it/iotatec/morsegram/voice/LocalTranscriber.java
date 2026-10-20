/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.voice;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.ModelDownloadListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.TranscribeButton;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Trascrizione on-device dei vocali e dei videomessaggi rotondi per tutti gli utenti (mission 5): Telegram Premium la fa
 * sul server (messages.transcribeAudio), qui la fa il riconoscitore on-device di Android 13+ ({@link SpeechRecognizer}
 * con EXTRA_AUDIO_SOURCE) sull'audio già ricevuto. API statica usata dai 3 hook di {@code TranscribeButton}:
 * <ul>
 * <li>{@link #canTranscribe}: O(1), flag statici (motore e probe calcolati una volta su globalQueue), chiamato dal bind
 *     di ogni bolla vocale via isFreeTranscribeInChat → bottone visibile, nessun lucchetto/bulletin Premium.</li>
 * <li>{@link #start}: prende in carico il tap al posto della richiesta di rete; false = prosegue upstream.</li>
 * <li>{@link #isTranscribing}: spinner upstream e bolla aperta durante il lavoro.</li>
 * </ul>
 * Pipeline (una alla volta, SpeechRecognizer è single-session): download se serve (FileLoader, senza riproduzione: il
 * vocale NON viene marcato come ascoltato) → {@link PcmDecoder} su thread proprio → probe di sicurezza al primo uso →
 * verifica modello lingua (checkRecognitionSupport) → sessioni {@link SpeechSession} per segmenti ≤ 50 s tagliati sui
 * silenzi, con ripartenza dall'offset consumato se il riconoscitore chiude prima → persistenza identica al percorso
 * server (MessagesStorage.updateMessageVoiceTranscription / TranscribeButton.finishTranscription, voiceTranscriptionId
 * resta 0: nessun 'Valuta trascrizione') e NotificationCenter.voiceTranscriptionUpdate che ChatActivity/ChatMessageCell
 * già consumano. Il PCM temporaneo in cache è cancellato a fine job; niente lascia il dispositivo a meno che l'utente non
 * abiliti il riconoscitore di sistema ({@link VoiceSettings#allowSystem()}).
 * <p>
 * {@link #SENTINEL_ID} viaggia come args[1] di voiceTranscriptionUpdate ma non è mai salvato: ChatActivity con
 * args[1] != null ricerca il messaggio per (dialogId, id) quando l'istanza non è nella lista (chat riaperta a metà
 * lavoro); con 0 avrebbe agganciato il primo messaggio con voiceTranscriptionId == 0.
 */
@SuppressLint("NewApi") // API 33 (SpeechRecognizer EXTRA_AUDIO_SOURCE): gating runtime in canTranscribe/warmUp/statusText, mai raggiungibile sotto Android 13
public final class LocalTranscriber {

    /** Id fittizio, mai persistito (voiceTranscriptionId resta 0). */
    public static final long SENTINEL_ID = -0x4D6F727365L;
    /** Durata massima trascrivibile (20 min ≈ 38 MB di PCM in cache). */
    public static final int MAX_SECONDS = 1200;

    static final int SEGMENT_MAX_SEC = 50;
    static final int SEGMENT_SEARCH_SEC = 15;
    private static final long DOWNLOAD_TIMEOUT_MS = 60_000;
    private static final long SUPPORT_TIMEOUT_MS = 10_000;
    private static final long SUPPORT_CACHE_MS = 60 * 60_000L;
    private static final long UI_THROTTLE_MS = 300;
    private static final long PROBE_BYTES = PcmDecoder.BYTES_PER_SECOND * 3; // 3 s di silenzio (96 KB > pipe di fallback 64 KB: writerDone implica letture del servizio)
    private static final String TMP_PREFIX = "morsegram_stt_";

    private static final int ENGINE_UNKNOWN = 0;
    private static final int ENGINE_CHECKING = 1;
    private static final int ENGINE_ON_DEVICE = 2;
    private static final int ENGINE_SYSTEM = 3;
    private static final int ENGINE_NONE = 4;

    private static final int FAIL_GENERIC = 0;
    private static final int FAIL_UNAVAILABLE = 1;
    private static final int FAIL_MODEL_MISSING = 2;
    private static final int FAIL_MODEL_PENDING = 3;
    private static final int FAIL_LANG_UNSUPPORTED = 4;

    private static volatile int engine = ENGINE_UNKNOWN;
    private static volatile boolean systemAvailable;
    private static volatile boolean onDeviceAvailable;
    private static volatile String probeKey = "";
    private static volatile boolean probeOk;

    private static final ConcurrentHashMap<Integer, Job> jobs = new ConcurrentHashMap<>();
    private static final ArrayDeque<Job> queue = new ArrayDeque<>();
    private static Job active;
    private static final List<Integer> observedAccounts = new ArrayList<>();
    private static String supportTag;
    private static long supportAt;
    private static SpeechRecognizer downloadRecognizer;

    private LocalTranscriber() {
    }

    /* ======================================================================================== API hook */

    /** O(1): vocale o videomessaggio rotondo inviato, non 'visualizza una volta', ≤ 20 min, Android 13+, motore pronto, modalità. */
    public static boolean canTranscribe(MessageObject m) {
        if (m == null || m.messageOwner == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return false;
        }
        final int mode = VoiceSettings.mode();
        if (mode == VoiceSettings.MODE_TELEGRAM) {
            return false;
        }
        if (!(m.isVoice() || m.isRoundVideo()) || !m.isSent() || m.isVoiceOnce() || m.isRoundOnce()) {
            return false;
        }
        if (m.getDuration() > MAX_SECONDS) {
            return false;
        }
        if (mode == VoiceSettings.MODE_AUTO && UserConfig.getInstance(m.currentAccount).isPremium()) {
            return false;
        }
        final int e = engine;
        if (e == ENGINE_UNKNOWN) {
            ensureEngineChecked();
            return false;
        }
        return e == ENGINE_ON_DEVICE || e == ENGINE_SYSTEM;
    }

    /** true = preso in carico (hook nel ramo di rete di transcribePressed); false = upstream prosegue col server. */
    public static boolean start(MessageObject m, ChatMessageCell.ChatMessageCellDelegate delegate) {
        if (!canTranscribe(m)) {
            return false;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(() -> start(m, delegate));
            return true;
        }
        final int key = key(m);
        if (jobs.containsKey(key)) {
            return true;
        }
        final Job job = new Job(m);
        jobs.put(key, job);
        ensureObserver(job.account);
        TranscribeButton.openVideoTranscription(m);
        m.messageOwner.voiceTranscriptionOpen = true;
        m.messageOwner.voiceTranscriptionFinal = false;
        m.messageOwner.voiceTranscription = LocaleController.getString("MorsegramVoiceTranscribing");
        MessagesStorage.getInstance(job.account).updateMessageVoiceTranscription(job.dialogId, job.msgId, "", m.messageOwner);
        NotificationCenter.getInstance(job.account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, m, (Long) SENTINEL_ID, m.messageOwner.voiceTranscription, (Boolean) true, (Boolean) false);
        queue.add(job);
        log("queued " + job);
        pump();
        return true;
    }

    public static boolean isTranscribing(MessageObject m) {
        return m != null && m.messageOwner != null && !jobs.isEmpty() && jobs.containsKey(key(m));
    }

    /** Annulla il job del messaggio e richiude la bolla (stesso percorso della chiusura upstream). */
    public static void cancel(MessageObject m) {
        if (m == null) {
            return;
        }
        final Job job = jobs.get(key(m));
        if (job != null) {
            AndroidUtilities.runOnUIThread(() -> abort(job, true));
        }
    }

    /** Per un eventuale hook di chiusura chat (ChatActivity.onFragmentDestroy): annulla tutti i job del dialogo. */
    public static void cancelDialog(long dialogId) {
        for (Job job : jobs.values()) {
            if (job.dialogId == dialogId) {
                AndroidUtilities.runOnUIThread(() -> abort(job, true));
            }
        }
    }

    public static void cancelAll() {
        for (Job job : jobs.values()) {
            AndroidUtilities.runOnUIThread(() -> abort(job, true));
        }
    }

    /** Bootstrap opzionale (MorsegramApp.onCreate): calcola la disponibilità del motore e pulisce i PCM orfani in cache. */
    public static void warmUp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        ensureEngineChecked();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final File[] files = cacheDir().listFiles((dir, name) -> name.startsWith(TMP_PREFIX));
                if (files != null) {
                    for (File f : files) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            } catch (Throwable ignore) {
            }
        });
    }

    /** VoiceSettings: modalità/lingua/riconoscitore di sistema cambiati → ricalcolo motore e cache lingua. */
    static void onSettingsChanged() {
        supportTag = null;
        engine = ENGINE_UNKNOWN;
        ensureEngineChecked();
    }

    /** Dimentica l'esito della probe (ok o ko) e ricontrolla il motore: la prossima trascrizione ripete la probe (riga di stato delle impostazioni, tap). */
    public static void resetProbe() {
        VoiceSettings.setProbe("");
        probeOk = false;
        onSettingsChanged();
    }

    /** Testo di stato per la pagina impostazioni. */
    static String statusText() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return LocaleController.getString("MorsegramVoiceStatusAndroid13");
        }
        switch (engine) {
            case ENGINE_ON_DEVICE:
                return LocaleController.formatString("MorsegramVoiceStatusOnDevice", 0, VoiceSettings.effectiveLanguageTag());
            case ENGINE_SYSTEM:
                return LocaleController.getString("MorsegramVoiceStatusSystem");
            case ENGINE_NONE:
                return LocaleController.getString("MorsegramVoiceUnavailable");
            default:
                ensureEngineChecked();
                return LocaleController.getString("MorsegramVoiceStatusChecking");
        }
    }

    /* ===================================================================================== motore */

    private static void ensureEngineChecked() {
        if (engine != ENGINE_UNKNOWN) {
            return;
        }
        engine = ENGINE_CHECKING;
        Utilities.globalQueue.postRunnable(() -> {
            int result = ENGINE_NONE;
            try {
                final Context ctx = ApplicationLoader.applicationContext;
                if (ctx != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    onDeviceAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx);
                    systemAvailable = SpeechRecognizer.isRecognitionAvailable(ctx);
                    int candidate = ENGINE_NONE;
                    if (onDeviceAvailable) {
                        candidate = ENGINE_ON_DEVICE;
                    } else if (systemAvailable && VoiceSettings.allowSystem()) {
                        candidate = ENGINE_SYSTEM;
                    }
                    // la probe vale per il motore che verrà usato: un ko del riconoscitore di sistema non blocca quello on-device e viceversa
                    probeKey = computeProbeKey(ctx) + (candidate == ENGINE_SYSTEM ? "|sys" : "|od");
                    final String probe = VoiceSettings.probe();
                    probeOk = probe.equals(probeKey + "=ok");
                    final boolean probeKo = probe.equals(probeKey + "=ko");
                    if (!probeKo) {
                        result = candidate;
                    }
                    log("engine check: onDevice=" + onDeviceAvailable + " system=" + systemAvailable + " probe=" + probe + " key=" + probeKey + " -> " + result);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            engine = result;
            AndroidUtilities.runOnUIThread(LocalTranscriber::refreshLocks);
        });
    }

    /** Dopo il check del motore: le celle già visibili riallineano il lucchetto (showTranscribeLock → isFreeTranscribeInChat → canTranscribe). */
    private static void refreshLocks() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.updateTranscriptionLock);
                }
            } catch (Throwable ignore) {
            }
        }
    }

    /** Fingerprint + versione dei pacchetti che possono ospitare il riconoscitore: la probe si ripete quando cambiano. */
    private static String computeProbeKey(Context ctx) {
        final StringBuilder sb = new StringBuilder();
        sb.append(Integer.toHexString(Build.FINGERPRINT.hashCode()));
        String servicePkg = null;
        try {
            final String setting = Settings.Secure.getString(ctx.getContentResolver(), "voice_recognition_service");
            final ComponentName cn = TextUtils.isEmpty(setting) ? null : ComponentName.unflattenFromString(setting);
            servicePkg = cn == null ? null : cn.getPackageName();
        } catch (Throwable ignore) {
        }
        for (String pkg : new String[]{servicePkg, "com.google.android.tts", "com.google.android.as"}) {
            if (TextUtils.isEmpty(pkg)) {
                continue;
            }
            sb.append('|').append(pkg).append(':');
            try {
                final PackageInfo pi = ctx.getPackageManager().getPackageInfo(pkg, 0);
                sb.append(pi.getLongVersionCode());
            } catch (Throwable e) {
                sb.append('-');
            }
        }
        return sb.toString();
    }

    private static boolean onDevice() {
        return engine == ENGINE_ON_DEVICE;
    }

    /* ======================================================================================== job */

    private static final class Job {
        final MessageObject message;
        final int account;
        final long dialogId;
        final int msgId;
        final int key;
        final AtomicBoolean cancelled = new AtomicBoolean();
        String fileName;
        Runnable timeout;
        File pcm;
        long bytes;
        long[] segments; // coppie start,end in byte
        int segIndex;
        long cursor; // offset dentro il segmento corrente
        long sessionCursor;
        int retries;
        String lang;
        SpeechSession session;
        final StringBuilder finalText = new StringBuilder();
        String partial;
        long lastUi;
        Runnable pendingUi;

        Job(MessageObject m) {
            message = m;
            account = m.currentAccount;
            dialogId = m.getDialogId();
            msgId = m.getId();
            key = key(m);
        }

        @Override
        public String toString() {
            return "job[" + account + "/" + dialogId + "/" + msgId + "]";
        }
    }

    private static int key(MessageObject m) {
        return Objects.hash(m.currentAccount, m.getDialogId(), m.getId());
    }

    private static void pump() {
        if (active != null) {
            return;
        }
        final Job job = queue.poll();
        if (job == null) {
            return;
        }
        active = job;
        resolveFile(job);
    }

    private static void resolveFile(Job job) {
        if (job.cancelled.get()) {
            abort(job, false);
            return;
        }
        final TLRPC.Document document = job.message.getDocument();
        final File f = FileLoader.getInstance(job.account).getPathToMessage(job.message.messageOwner);
        if (f != null && f.exists() && f.length() > 0) {
            decode(job, f);
            return;
        }
        if (document == null) {
            fail(job, FAIL_GENERIC);
            return;
        }
        job.fileName = FileLoader.getAttachFileName(document);
        job.timeout = () -> {
            job.timeout = null;
            log("download timeout " + job);
            fail(job, FAIL_GENERIC);
        };
        AndroidUtilities.runOnUIThread(job.timeout, DOWNLOAD_TIMEOUT_MS);
        log("download " + job.fileName);
        FileLoader.getInstance(job.account).loadFile(document, job.message, FileLoader.PRIORITY_HIGH, job.message.shouldEncryptPhotoOrVideo() ? 2 : 0);
    }

    private static void decode(Job job, File src) {
        job.pcm = new File(cacheDir(), TMP_PREFIX + Integer.toHexString(job.key) + "_" + SystemClock.elapsedRealtime() + ".pcm");
        final Thread t = new Thread(() -> {
            PcmDecoder.Result res = null;
            Throwable error = null;
            try {
                res = PcmDecoder.decode(src, job.pcm, job.cancelled);
            } catch (Throwable e) {
                error = e;
            }
            final PcmDecoder.Result result = res;
            final Throwable err = error;
            AndroidUtilities.runOnUIThread(() -> {
                if (job.cancelled.get()) {
                    abort(job, false);
                    return;
                }
                if (err != null || result == null || result.bytes <= 0) {
                    if (err != null) {
                        FileLog.e(err);
                    }
                    fail(job, FAIL_GENERIC);
                    return;
                }
                job.bytes = result.bytes;
                job.segments = segments(result);
                log("decoded " + job.bytes + " bytes, " + job.segments.length / 2 + " segments");
                probe(job);
            });
        }, "morsegram-stt-decode");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        t.start();
    }

    /** Segmenti ≤ 50 s tagliati sul frame più silenzioso degli ultimi 15 s. */
    static long[] segments(PcmDecoder.Result res) {
        final float[] rms = res.frameRms;
        final int n = rms.length;
        final ArrayList<Long> out = new ArrayList<>();
        int startFrame = 0;
        final int maxFrames = SEGMENT_MAX_SEC * 1000 / PcmDecoder.FRAME_MS;
        final int searchFrames = SEGMENT_SEARCH_SEC * 1000 / PcmDecoder.FRAME_MS;
        while (startFrame < n) {
            final int maxEnd = Math.min(n, startFrame + maxFrames);
            if (maxEnd >= n) {
                out.add((long) startFrame * PcmDecoder.FRAME_BYTES);
                out.add(res.bytes);
                break;
            }
            int best = maxEnd;
            float bestRms = Float.MAX_VALUE;
            for (int i = Math.max(startFrame + 1, maxEnd - searchFrames); i < maxEnd; i++) {
                if (rms[i] < bestRms) {
                    bestRms = rms[i];
                    best = i;
                }
            }
            out.add((long) startFrame * PcmDecoder.FRAME_BYTES);
            out.add((long) best * PcmDecoder.FRAME_BYTES);
            startFrame = best;
        }
        if (out.isEmpty()) {
            out.add(0L);
            out.add(res.bytes);
        }
        final long[] arr = new long[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /* ------------------------------------------------------------------------------------- probe */

    /**
     * Probe di sicurezza (una volta per {@link #probeKey}): 3 s di silenzio nella pipe. "Letto" = byte accettati oltre la
     * capacità della pipe (il buffer della pipe assorbe i primi {@code pipeCapacity} byte anche senza lettore) o EOF
     * raggiunto. Se il riconoscitore non legge il descrittore (ERR_NO_PROGRESS, oppure sessione conclusa senza letture)
     * potrebbe aver aperto il microfono: funzione disattivata (voice.probe = key=ko) finché {@link #resetProbe()} o
     * cambio di fingerprint/versione del servizio. Il file di silenzio è scritto fuori dal main thread.
     */
    private static void probe(Job job) {
        if (job.cancelled.get()) {
            abort(job, false);
            return;
        }
        if (probeOk) {
            checkLanguage(job);
            return;
        }
        final File silence = new File(cacheDir(), TMP_PREFIX + "probe.pcm");
        Utilities.globalQueue.postRunnable(() -> {
            boolean written = false;
            try (FileOutputStream fos = new FileOutputStream(silence)) {
                final byte[] zeros = new byte[PcmDecoder.FRAME_BYTES];
                long left = PROBE_BYTES;
                while (left > 0) {
                    final int n = (int) Math.min(zeros.length, left);
                    fos.write(zeros, 0, n);
                    left -= n;
                }
                written = true;
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final boolean ok = written;
            AndroidUtilities.runOnUIThread(() -> {
                if (job.cancelled.get()) {
                    abort(job, false);
                } else if (!ok) {
                    fail(job, FAIL_GENERIC);
                } else {
                    startProbe(job, silence);
                }
            });
        });
    }

    private static void startProbe(Job job, File silence) {
        log("probe start key=" + probeKey);
        final SpeechSession probe = new SpeechSession(ApplicationLoader.applicationContext, onDevice(), VoiceSettings.effectiveLanguageTag(), silence, 0, PROBE_BYTES, new SpeechSession.Listener() {
            @Override
            public void onPartial(String text) {
            }

            @Override
            public void onSegment(String text) {
            }

            @Override
            public void onFinished(boolean writerDone, long accepted) {
                //noinspection ResultOfMethodCallIgnored
                silence.delete();
                if (writerDone || accepted > pipeCapacityOf(job)) {
                    probePassed(job, true);
                } else {
                    // sessione conclusa (NO_MATCH/SPEECH_TIMEOUT/risultati) senza aver letto il descrittore: ha ascoltato altro
                    log("probe finished without reading the source, accepted=" + accepted);
                    probeKo(job);
                }
            }

            @Override
            public void onFailed(int error, boolean writerDone, long accepted) {
                //noinspection ResultOfMethodCallIgnored
                silence.delete();
                log("probe failed error=" + error + " writerDone=" + writerDone + " accepted=" + accepted);
                final boolean read = writerDone || accepted > pipeCapacityOf(job);
                if (error == SpeechSession.ERR_NO_PROGRESS) {
                    probeKo(job);
                } else if (read) {
                    // il descrittore viene letto: la probe è passata, l'errore (lingua, server...) si gestisce nella sessione vera
                    probePassed(job, true);
                } else if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                    // rifiuto prima di leggere: inconclusiva, non si persiste; il check lingua darà il messaggio giusto
                    probePassed(job, false);
                } else if (error == SpeechSession.ERR_TIMEOUT) {
                    // mai pronto e mai letto entro la scadenza: inconclusiva, non si persiste, il prossimo job ripete la probe
                    fail(job, FAIL_GENERIC);
                } else {
                    fail(job, error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechSession.ERR_CREATE ? FAIL_UNAVAILABLE : FAIL_GENERIC);
                }
            }
        });
        job.session = probe;
        probe.start();
    }

    /** Probe fallita: funzione disattivata per questo {@link #probeKey}, job in coda chiusi senza bulletin, lucchetti riallineati. */
    private static void probeKo(Job job) {
        VoiceSettings.setProbe(probeKey + "=ko");
        probeOk = false;
        engine = ENGINE_NONE;
        for (Job other : new ArrayList<>(queue)) {
            fail(other, -1);
        }
        fail(job, FAIL_UNAVAILABLE);
        refreshLocks();
    }

    private static void probePassed(Job job, boolean persist) {
        job.session = null;
        if (persist) {
            probeOk = true;
            VoiceSettings.setProbe(probeKey + "=ok");
        }
        log("probe ok persist=" + persist);
        checkLanguage(job);
    }

    /* ------------------------------------------------------------------------------- lingua/modello */

    private static void checkLanguage(Job job) {
        if (job.cancelled.get()) {
            abort(job, false);
            return;
        }
        final String tag = VoiceSettings.effectiveLanguageTag();
        if (tag.equals(supportTag) && SystemClock.elapsedRealtime() - supportAt < SUPPORT_CACHE_MS) {
            job.lang = tag;
            nextSegment(job);
            return;
        }
        final Context ctx = ApplicationLoader.applicationContext;
        final SpeechRecognizer recognizer = SpeechSession.create(ctx, onDevice());
        if (recognizer == null) {
            fail(job, FAIL_UNAVAILABLE);
            return;
        }
        final boolean[] answered = new boolean[1];
        final Runnable timeout = () -> {
            if (answered[0]) {
                return;
            }
            answered[0] = true;
            destroy(recognizer);
            log("checkRecognitionSupport timeout: proceeding with " + tag);
            job.lang = tag;
            nextSegment(job);
        };
        AndroidUtilities.runOnUIThread(timeout, SUPPORT_TIMEOUT_MS);
        try {
            recognizer.checkRecognitionSupport(SpeechSession.intent(ctx, tag, null), ContextCompat.getMainExecutor(ctx), new RecognitionSupportCallback() {
                @Override
                public void onSupportResult(RecognitionSupport support) {
                    if (answered[0]) {
                        return;
                    }
                    answered[0] = true;
                    AndroidUtilities.cancelRunOnUIThread(timeout);
                    destroy(recognizer);
                    onSupport(job, tag, support);
                }

                @Override
                public void onError(int error) {
                    if (answered[0]) {
                        return;
                    }
                    answered[0] = true;
                    AndroidUtilities.cancelRunOnUIThread(timeout);
                    destroy(recognizer);
                    log("checkRecognitionSupport error " + error + ": proceeding with " + tag);
                    job.lang = tag;
                    nextSegment(job);
                }
            });
        } catch (Throwable e) {
            FileLog.e(e);
            if (!answered[0]) {
                answered[0] = true;
                AndroidUtilities.cancelRunOnUIThread(timeout);
                destroy(recognizer);
                job.lang = tag;
                nextSegment(job);
            }
        }
    }

    private static void onSupport(Job job, String tag, RecognitionSupport support) {
        final List<String> installed = support.getInstalledOnDeviceLanguages();
        final List<String> supported = support.getSupportedOnDeviceLanguages();
        final List<String> pending = support.getPendingOnDeviceLanguages();
        final List<String> online = support.getOnlineLanguages();
        log("support for " + tag + ": installed=" + installed + " pending=" + pending + " supported=" + supported + " online=" + online);
        String match = match(installed, tag);
        if (match == null && !onDevice() && VoiceSettings.allowSystem()) {
            match = match(online, tag);
        }
        if (match == null && installed != null && installed.isEmpty() && supported != null && supported.isEmpty()) {
            // il servizio non dichiara le lingue: si prova comunque
            match = tag;
        }
        if (match != null) {
            job.lang = match;
            supportTag = tag;
            supportAt = SystemClock.elapsedRealtime();
            nextSegment(job);
            return;
        }
        if (match(pending, tag) != null) {
            fail(job, FAIL_MODEL_PENDING);
        } else if (match(supported, tag) != null) {
            job.lang = tag;
            fail(job, FAIL_MODEL_MISSING);
        } else {
            final String fallback = VoiceSettings.normalize(Locale.getDefault().toLanguageTag());
            final String fm = fallback.equalsIgnoreCase(tag) ? null : match(installed, fallback);
            if (fm != null) {
                log("language " + tag + " unsupported, falling back to " + fm);
                job.lang = fm;
                nextSegment(job);
            } else {
                fail(job, FAIL_LANG_UNSUPPORTED);
            }
        }
    }

    /** Tag esatto, altrimenti stessa lingua (prefisso prima del '-'). */
    private static String match(List<String> list, String tag) {
        if (list == null || tag == null) {
            return null;
        }
        for (String s : list) {
            if (tag.equalsIgnoreCase(s)) {
                return s;
            }
        }
        final String lang = tag.indexOf('-') > 0 ? tag.substring(0, tag.indexOf('-')) : tag;
        for (String s : list) {
            final String l = s.indexOf('-') > 0 ? s.substring(0, s.indexOf('-')) : s;
            if (lang.equalsIgnoreCase(l)) {
                return s;
            }
        }
        return null;
    }

    private static void destroy(SpeechRecognizer r) {
        try {
            r.destroy();
        } catch (Throwable ignore) {
        }
    }

    /* ---------------------------------------------------------------------------------- segmenti */

    private static void nextSegment(Job job) {
        if (job.cancelled.get()) {
            abort(job, false);
            return;
        }
        if (job.segments == null || job.segIndex * 2 >= job.segments.length) {
            finish(job);
            return;
        }
        final long segStart = job.segments[job.segIndex * 2];
        final long segEnd = job.segments[job.segIndex * 2 + 1];
        final long from = segStart + job.cursor;
        if (segEnd - from < PcmDecoder.BYTES_PER_SECOND / 2) {
            job.segIndex++;
            job.cursor = 0;
            nextSegment(job);
            return;
        }
        job.sessionCursor = job.cursor;
        final SpeechSession session = new SpeechSession(ApplicationLoader.applicationContext, onDevice(), job.lang, job.pcm, from, segEnd, new SpeechSession.Listener() {
            @Override
            public void onPartial(String text) {
                job.partial = text;
                updateUi(job, false);
            }

            @Override
            public void onSegment(String text) {
                append(job, text);
                job.partial = null;
                updateUi(job, true);
            }

            @Override
            public void onFinished(boolean writerDone, long accepted) {
                final int pipeCapacity = pipeCapacityOf(job);
                job.session = null;
                job.retries = 0;
                if (job.cancelled.get()) {
                    abort(job, false);
                    return;
                }
                if (!TextUtils.isEmpty(job.partial)) {
                    // sessione chiusa senza risultato finale (NO_MATCH/TIMEOUT): si tiene l'ultimo parziale
                    append(job, job.partial);
                    job.partial = null;
                }
                if (writerDone) {
                    job.segIndex++;
                    job.cursor = 0;
                } else {
                    // il riconoscitore ha chiuso prima della fine: si riparte dal punto consumato (≤ pipe non letta), avanzando di almeno 2 s
                    final long consumed = Math.max(0, accepted - pipeCapacity);
                    job.cursor = job.sessionCursor + Math.max(consumed, 2L * PcmDecoder.BYTES_PER_SECOND);
                    job.cursor -= job.cursor % 2;
                }
                nextSegment(job);
            }

            @Override
            public void onFailed(int error, boolean writerDone, long accepted) {
                job.session = null;
                if (job.cancelled.get()) {
                    abort(job, false);
                    return;
                }
                log("segment failed error=" + error + " writerDone=" + writerDone + " accepted=" + accepted + " " + job);
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY && job.retries++ < 3) {
                    AndroidUtilities.runOnUIThread(() -> nextSegment(job), 1500);
                    return;
                }
                if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                    fail(job, FAIL_MODEL_MISSING);
                } else if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                    fail(job, FAIL_LANG_UNSUPPORTED);
                } else if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechSession.ERR_CREATE || error == SpeechSession.ERR_NO_PROGRESS) {
                    fail(job, FAIL_UNAVAILABLE);
                } else {
                    fail(job, FAIL_GENERIC);
                }
            }
        });
        job.session = session;
        session.start();
    }

    private static int pipeCapacityOf(Job job) {
        return job.session != null ? job.session.pipeCapacity() : 65536;
    }

    private static void append(Job job, String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        final String t = text.trim();
        if (t.isEmpty()) {
            return;
        }
        if (job.finalText.length() > 0) {
            job.finalText.append(' ');
        }
        job.finalText.append(t);
    }

    private static String composed(Job job) {
        final String fin = job.finalText.toString();
        if (TextUtils.isEmpty(job.partial)) {
            return fin;
        }
        return fin.isEmpty() ? job.partial.trim() : fin + " " + job.partial.trim();
    }

    /** Aggiorna la bolla (throttle 300 ms per i parziali); i segmenti finali vengono anche persistiti. */
    private static void updateUi(Job job, boolean persist) {
        if (job.cancelled.get()) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (!persist && now - job.lastUi < UI_THROTTLE_MS) {
            if (job.pendingUi == null) {
                job.pendingUi = () -> {
                    job.pendingUi = null;
                    updateUi(job, false);
                };
                AndroidUtilities.runOnUIThread(job.pendingUi, UI_THROTTLE_MS - (now - job.lastUi));
            }
            return;
        }
        job.lastUi = now;
        String text = composed(job);
        if (text.isEmpty()) {
            text = LocaleController.getString("MorsegramVoiceTranscribing");
        }
        final MessageObject m = job.message;
        m.messageOwner.voiceTranscription = text;
        m.messageOwner.voiceTranscriptionOpen = true;
        m.messageOwner.voiceTranscriptionFinal = false;
        if (persist) {
            MessagesStorage.getInstance(job.account).updateMessageVoiceTranscription(job.dialogId, job.msgId, job.finalText.toString(), m.messageOwner);
        }
        NotificationCenter.getInstance(job.account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, m, (Long) SENTINEL_ID, text, (Boolean) true, (Boolean) false);
    }

    /* ---------------------------------------------------------------------------------- chiusura */

    private static void finish(Job job) {
        if (!TextUtils.isEmpty(job.partial)) {
            append(job, job.partial);
            job.partial = null;
        }
        final String text = job.finalText.toString().trim();
        log("finished " + job + " chars=" + text.length());
        release(job);
        jobs.remove(job.key);
        job.message.messageOwner.voiceTranscription = text;
        TranscribeButton.finishTranscription(job.message, SENTINEL_ID, text);
        if (text.isEmpty()) {
            bulletin(LocaleController.getString("MorsegramVoiceNoSpeech"), null, null);
        }
        done(job);
    }

    /** Errore: bolla richiusa come nel percorso upstream (open=false, lock aggiornato) + bulletin; {@code reason} -1 = silenzioso. */
    private static void fail(Job job, int reason) {
        job.cancelled.set(true);
        release(job);
        jobs.remove(job.key);
        queue.remove(job);
        closeBubble(job);
        if (reason >= 0) {
            final String lang = job.lang != null ? job.lang : VoiceSettings.effectiveLanguageTag();
            switch (reason) {
                case FAIL_UNAVAILABLE:
                    bulletin(LocaleController.getString("MorsegramVoiceUnavailable"), null, null);
                    break;
                case FAIL_MODEL_MISSING:
                    bulletin(LocaleController.formatString("MorsegramVoiceModelMissing", 0, VoiceSettings.displayName(lang)), LocaleController.getString("MorsegramVoiceDownload"), () -> triggerModelDownload(lang));
                    break;
                case FAIL_MODEL_PENDING:
                    bulletin(LocaleController.formatString("MorsegramVoiceModelPending", 0, VoiceSettings.displayName(lang)), null, null);
                    break;
                case FAIL_LANG_UNSUPPORTED:
                    bulletin(LocaleController.formatString("MorsegramVoiceLanguageUnsupported", 0, VoiceSettings.displayName(lang)), null, null);
                    break;
                default:
                    bulletin(LocaleController.getString("MorsegramVoiceFailed"), null, null);
                    break;
            }
        }
        done(job);
    }

    /** Annullamento (chiusura della bolla da parte dell'utente o cancel*): niente bulletin. */
    private static void abort(Job job, boolean closeBubble) {
        if (!jobs.containsKey(job.key) && active != job && !queue.contains(job)) {
            return;
        }
        log("abort " + job);
        job.cancelled.set(true);
        release(job);
        jobs.remove(job.key);
        queue.remove(job);
        if (closeBubble) {
            closeBubble(job);
        }
        done(job);
    }

    private static void closeBubble(Job job) {
        final MessageObject m = job.message;
        m.messageOwner.voiceTranscriptionOpen = false;
        m.messageOwner.voiceTranscriptionFinal = false;
        MessagesStorage.getInstance(job.account).updateMessageVoiceTranscriptionOpen(job.dialogId, job.msgId, m.messageOwner);
        NotificationCenter.getInstance(job.account).postNotificationName(NotificationCenter.voiceTranscriptionUpdate, m, null, null, (Boolean) false, null);
        NotificationCenter.getInstance(job.account).postNotificationName(NotificationCenter.updateTranscriptionLock);
    }

    private static void release(Job job) {
        if (job.timeout != null) {
            AndroidUtilities.cancelRunOnUIThread(job.timeout);
            job.timeout = null;
        }
        if (job.pendingUi != null) {
            AndroidUtilities.cancelRunOnUIThread(job.pendingUi);
            job.pendingUi = null;
        }
        if (job.session != null) {
            job.session.cancel();
            job.session = null;
        }
        if (job.pcm != null) {
            final File f = job.pcm;
            job.pcm = null;
            Utilities.globalQueue.postRunnable(() -> {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            });
        }
    }

    private static void done(Job job) {
        if (active == job) {
            active = null;
        }
        pump();
    }

    /* ---------------------------------------------------------------------------- notifiche */

    private static void ensureObserver(int account) {
        if (observedAccounts.contains(account)) {
            return;
        }
        observedAccounts.add(account);
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        nc.addObserver(observer, NotificationCenter.fileLoaded);
        nc.addObserver(observer, NotificationCenter.fileLoadFailed);
        nc.addObserver(observer, NotificationCenter.voiceTranscriptionUpdate);
    }

    private static final NotificationCenter.NotificationCenterDelegate observer = (id, account, args) -> {
        if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
            final Job job = active;
            if (job == null || job.fileName == null || args == null || args.length == 0 || !job.fileName.equals(args[0])) {
                return;
            }
            if (job.timeout != null) {
                AndroidUtilities.cancelRunOnUIThread(job.timeout);
                job.timeout = null;
            }
            job.fileName = null;
            if (id == NotificationCenter.fileLoaded) {
                final File f = FileLoader.getInstance(job.account).getPathToMessage(job.message.messageOwner);
                if (f != null && f.exists() && f.length() > 0) {
                    decode(job, f);
                } else {
                    fail(job, FAIL_GENERIC);
                }
            } else {
                fail(job, FAIL_GENERIC);
            }
        } else if (id == NotificationCenter.voiceTranscriptionUpdate) {
            // chiusura della bolla da parte dell'utente (TranscribeButton.transcribePressed open=false): i nostri post hanno sempre open=true finché il job esiste
            if (args == null || args.length < 4 || !Boolean.FALSE.equals(args[3]) || !(args[0] instanceof MessageObject)) {
                return;
            }
            final Job job = jobs.get(key((MessageObject) args[0]));
            if (job != null) {
                abort(job, false);
            }
        }
    };

    /* --------------------------------------------------------------------------------- bulletin */

    private static void bulletin(String text, String button, Runnable action) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final BulletinFactory f = BulletinFactory.global();
                if (button != null && action != null) {
                    f.createSimpleBulletin(R.raw.transcribe, text, button, action).show();
                } else {
                    f.createSimpleBulletin(R.raw.transcribe, text).show();
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** 'Scarica' nel bulletin modello mancante: prompt di sistema (API 33) o download con esito (API 34+). */
    private static void triggerModelDownload(String lang) {
        final Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (downloadRecognizer != null) {
            destroy(downloadRecognizer);
            downloadRecognizer = null;
        }
        final SpeechRecognizer r = SpeechSession.create(ctx, onDevice());
        if (r == null) {
            return;
        }
        downloadRecognizer = r;
        final Intent intent = SpeechSession.intent(ctx, lang, null);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                r.triggerModelDownload(intent, ContextCompat.getMainExecutor(ctx), new ModelDownloadListener() {
                    @Override
                    public void onProgress(int completedPercent) {
                    }

                    @Override
                    public void onSuccess() {
                        supportTag = null;
                        releaseDownloadRecognizer(r);
                        bulletin(LocaleController.formatString("MorsegramVoiceModelReady", 0, VoiceSettings.displayName(lang)), null, null);
                    }

                    @Override
                    public void onScheduled() {
                        supportTag = null;
                        releaseDownloadRecognizer(r);
                        bulletin(LocaleController.formatString("MorsegramVoiceModelPending", 0, VoiceSettings.displayName(lang)), null, null);
                    }

                    @Override
                    public void onError(int error) {
                        releaseDownloadRecognizer(r);
                        bulletin(LocaleController.getString("MorsegramVoiceFailed"), null, null);
                    }
                });
            } else {
                r.triggerModelDownload(intent);
                supportTag = null;
                AndroidUtilities.runOnUIThread(() -> releaseDownloadRecognizer(r), 60_000);
            }
        } catch (Throwable e) {
            FileLog.e(e);
            releaseDownloadRecognizer(r);
        }
    }

    private static void releaseDownloadRecognizer(SpeechRecognizer r) {
        if (downloadRecognizer == r) {
            downloadRecognizer = null;
        }
        destroy(r);
    }

    /* ---------------------------------------------------------------------------------- utilità */

    private static File cacheDir() {
        File dir = null;
        try {
            dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
        } catch (Throwable ignore) {
        }
        if (dir == null && ApplicationLoader.applicationContext != null) {
            dir = ApplicationLoader.applicationContext.getCacheDir();
        }
        if (dir != null) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    private static void log(String s) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("[morsegram-voice] " + s);
        }
    }
}

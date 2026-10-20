package it.iotatec.morsegram.carlabs.voice;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.car.app.CarContext;
import androidx.car.app.media.CarAudioRecord;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AutoDeleteMediaTask;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import it.iotatec.morsegram.auto.MorsegramAutoCommon;

/**
 * Records a real Telegram voice note from the car microphone (Android Auto "labs" path only) and sends it as a
 * normal voice message.
 *
 * <p>Pipeline: {@link CarAudioRecord} (16 kHz mono PCM 16-bit, the only microphone a templated Car App can reach)
 * -> upstream's native Opus encoder -> .ogg in the app media dir -> {@code TL_document} +
 * {@code TL_documentAttributeAudio(voice=true, duration, waveform)} -> {@code SendMessagesHelper.sendMessage}.
 * The objects are built exactly like {@code MediaController.stopRecordingInternal}, so the message is
 * indistinguishable from one recorded on the phone (real voice note, real waveform, real duration).
 *
 * <p>The encoder is reached by reflection on our own APK class ({@code org.telegram.messenger.MediaController}):
 * {@code startRecord/writeFrame/stopRecord} are private instance natives, so zero upstream lines are needed and
 * {@code setAccessible} is legal (same APK, no hidden-API restriction).
 *
 * <p>The native encoder in {@code jni/audio.c} is a set of process-wide C globals ({@code _encoder},
 * {@code _fileOs}, {@code os}): {@code initRecorder} calls {@code cleanupRecorder} first, so starting while the
 * phone recorder runs would destroy its stream. We therefore refuse to start while
 * {@code MediaController.isRecordingAudio()} is true and keep a single instance of this class.
 *
 * <p>Everything public is main-thread only; capture and encoding run on one worker thread, which keeps the
 * native calls serialized exactly like upstream's {@code fileEncodingQueue}. The car's speakers are silenced for
 * the capture by an exclusive audio focus request ({@link #requestAudioFocus()}), and nothing on the main thread
 * ever calls into {@link CarAudioRecord}, whose {@code stopRecording()} is a synchronous binder round trip to the
 * host ({@link #closeMicrophoneAsync()}).
 */
public final class CarVoiceRecorder {

    /** 16000 Hz: the car microphone's fixed rate, also what upstream uses for voice notes. */
    public static final int SAMPLE_RATE = CarAudioRecord.AUDIO_CONTENT_SAMPLING_RATE;
    /** jni/audio.c encodes exactly frame_size (960) samples per writeFrame call -> 1920 bytes mono 16-bit. */
    private static final int FRAME_BYTES = 1920;
    /** 16000 Hz * 2 bytes / 1000 ms. */
    private static final int BYTES_PER_MS = SAMPLE_RATE * 2 / 1000;
    /** Driver distraction: a car voice note is capped, the recorder auto-stops (it never auto-sends). */
    public static final long MAX_DURATION_MS = 60_000L;
    /** Upstream drops anything under 700 ms; we round up so a stray tap cannot send an empty note. */
    public static final long MIN_DURATION_MS = 1_000L;

    public static final int ERR_BUSY_PHONE = 1;
    public static final int ERR_BUSY_CAR = 2;
    public static final int ERR_NO_PERMISSION = 3;
    public static final int ERR_NO_MIC = 4;
    public static final int ERR_ENCODER = 5;
    public static final int ERR_TOO_SHORT = 6;
    public static final int ERR_HIDDEN = 7;

    /** Callbacks, always on the main thread. */
    public interface Listener {
        void onRecordingStarted();

        /** Audio captured and encoded, nothing sent yet: {@link #send()} or {@link #cancel()} must follow. */
        void onRecordingStopped(long durationMs, boolean capReached);

        void onVoiceSent();

        void onFailed(int code);
    }

    private static final int STATE_IDLE = 0;
    private static final int STATE_STARTING = 1;
    private static final int STATE_RECORDING = 2;
    private static final int STATE_RECORDED = 3;

    @Nullable
    private static CarVoiceRecorder instance;

    @MainThread
    public static CarVoiceRecorder getInstance() {
        if (instance == null) {
            instance = new CarVoiceRecorder();
        }
        return instance;
    }

    private CarVoiceRecorder() {
    }

    private int state = STATE_IDLE;
    @Nullable
    private Listener listener;
    private int account;
    private long dialogId;

    // scritti dal worker, letti sul thread UI (e viceversa alla cancellazione): volatile, non solo publish via Handler
    @Nullable
    private volatile File file;
    @Nullable
    private volatile TLRPC.TL_document document;
    @Nullable
    private volatile byte[] waveform;
    private long recordedDurationMs;

    @Nullable
    private Thread worker;
    @Nullable
    private volatile CarAudioRecord audioRecord;
    // focus audio tenuto per la durata della cattura: dalla 26 un AudioFocusRequest (tipato Object per non
    // nominare la classe fuori dai rami protetti), prima solo il listener piu' lo stream
    @Nullable
    private volatile AudioManager focusManager;
    @Nullable
    private volatile Object focusRequest;
    @Nullable
    private volatile AudioManager.OnAudioFocusChangeListener focusListener;
    private volatile boolean stopRequested;
    private volatile boolean cancelRequested;
    private boolean sendWhenStopped;

    private final ByteBuffer frameBuffer = allocateFrameBuffer();
    private volatile long totalPcmBytes;

    private static ByteBuffer allocateFrameBuffer() {
        ByteBuffer b = ByteBuffer.allocateDirect(FRAME_BYTES);
        b.order(ByteOrder.nativeOrder());
        return b;
    }

    // ===== reflection on our own MediaController =====

    /** Resolved once; the debug line prints the Method objects so a rebase that renames them is obvious in logcat. */
    private static final class Natives {
        @Nullable static final Method START_RECORD;
        @Nullable static final Method WRITE_FRAME;
        @Nullable static final Method STOP_RECORD;
        @Nullable static final Method IS_RECORDING_AUDIO;
        static final boolean AVAILABLE;

        static {
            Method start = null, write = null, stop = null, isRecording = null;
            try {
                start = MediaController.class.getDeclaredMethod("startRecord", String.class, int.class);
                write = MediaController.class.getDeclaredMethod("writeFrame", ByteBuffer.class, int.class);
                stop = MediaController.class.getDeclaredMethod("stopRecord");
                start.setAccessible(true);
                write.setAccessible(true);
                stop.setAccessible(true);
            } catch (Throwable t) {
                FileLog.e("morsegram/car: opus encoder natives not found", t);
                start = null;
                write = null;
                stop = null;
            }
            try {
                isRecording = MediaController.class.getDeclaredMethod("isRecordingAudio");
                isRecording.setAccessible(true);
            } catch (Throwable t) {
                FileLog.e("morsegram/car: MediaController.isRecordingAudio() not found", t);
            }
            START_RECORD = start;
            WRITE_FRAME = write;
            STOP_RECORD = stop;
            IS_RECORDING_AUDIO = isRecording;
            AVAILABLE = start != null && write != null && stop != null;
            FileLog.d("morsegram/car: opus natives resolved"
                    + " startRecord=" + START_RECORD
                    + " writeFrame=" + WRITE_FRAME
                    + " stopRecord=" + STOP_RECORD
                    + " isRecordingAudio=" + IS_RECORDING_AUDIO
                    + " available=" + AVAILABLE);
        }
    }

    /** True when the three natives are reachable: the UI can hide the action instead of failing on tap. */
    public static boolean isSupported() {
        return Natives.AVAILABLE;
    }

    private static int nativeStartRecord(String path, int sampleRate) throws Exception {
        Object r = Natives.START_RECORD.invoke(MediaController.getInstance(), path, sampleRate);
        return r instanceof Integer ? (Integer) r : 0;
    }

    private static void nativeWriteFrame(ByteBuffer frame, int len) throws Exception {
        Object r = Natives.WRITE_FRAME.invoke(MediaController.getInstance(), frame, len);
        if (r instanceof Integer && (Integer) r == 0) {
            FileLog.e("morsegram/car: writeFrame failed, len=" + len);
        }
    }

    private static void nativeStopRecord() {
        try {
            Natives.STOP_RECORD.invoke(MediaController.getInstance());
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** True when upstream's own recorder owns the native encoder (phone chat recording, raise-to-record...). */
    private static boolean phoneIsRecording() {
        if (Natives.IS_RECORDING_AUDIO == null) {
            return false;
        }
        try {
            Object r = Natives.IS_RECORDING_AUDIO.invoke(MediaController.getInstance());
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    // ===== public API (main thread) =====

    @MainThread
    public boolean isRecording() {
        return state == STATE_STARTING || state == STATE_RECORDING;
    }

    @MainThread
    public boolean isBusy() {
        return state != STATE_IDLE;
    }

    @MainThread
    public long getRecordedDurationMs() {
        return recordedDurationMs;
    }

    /** Elapsed capture time, for the screen's countdown. */
    @MainThread
    public long getElapsedMs() {
        return state == STATE_RECORDED ? recordedDurationMs : totalPcmBytes / BYTES_PER_MS;
    }

    public static boolean hasMicPermission(@NonNull CarContext carContext) {
        return ContextCompat.checkSelfPermission(carContext, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Opens the car microphone and starts encoding. Fails fast (via {@link Listener#onFailed(int)}) when the
     * phone recorder holds the native encoder, the chat is hidden, the permission is missing or the host
     * refuses the microphone.
     */
    @MainThread
    public void start(@NonNull CarContext carContext, int account, long dialogId, @NonNull Listener listener) {
        if (state != STATE_IDLE) {
            listener.onFailed(ERR_BUSY_CAR);
            return;
        }
        if (!Natives.AVAILABLE) {
            listener.onFailed(ERR_ENCODER);
            return;
        }
        // hidden chats must never be reachable from the head unit, not even as a send target
        if (!MorsegramAutoCommon.allowed(account, dialogId)) {
            listener.onFailed(ERR_HIDDEN);
            return;
        }
        if (!hasMicPermission(carContext)) {
            listener.onFailed(ERR_NO_PERMISSION);
            return;
        }
        if (phoneIsRecording()) {
            listener.onFailed(ERR_BUSY_PHONE);
            return;
        }
        this.listener = listener;
        this.account = account;
        this.dialogId = dialogId;
        this.waveform = null;
        this.recordedDurationMs = 0;
        this.totalPcmBytes = 0;
        this.stopRequested = false;
        this.cancelRequested = false;
        this.sendWhenStopped = false;
        this.frameBuffer.clear();
        this.state = STATE_STARTING;

        // il singleton va creato qui, sul thread UI: il worker poi lo usa solo per invocare i native
        MediaController.getInstance();

        final CarContext context = carContext;
        worker = new Thread(() -> recordLoop(context), "MorsegramCarVoice");
        worker.start();
    }

    /** Stops capture and keeps the clip; {@link Listener#onRecordingStopped} follows. */
    @MainThread
    public void stop() {
        if (state == STATE_STARTING || state == STATE_RECORDING) {
            stopRequested = true;
            closeMicrophoneAsync();
        }
    }

    /** One tap = stop (if needed) and send. */
    @MainThread
    public void send() {
        if (state == STATE_STARTING || state == STATE_RECORDING) {
            sendWhenStopped = true;
            stopRequested = true;
            closeMicrophoneAsync();
        } else if (state == STATE_RECORDED) {
            sendRecorded();
        }
    }

    /** One tap = stop and throw the clip away. Safe in every state, including while the worker is running. */
    @MainThread
    public void cancel() {
        if (state == STATE_IDLE) {
            return;
        }
        if (state == STATE_RECORDED) {
            discard();
            state = STATE_IDLE;
            return;
        }
        cancelRequested = true;
        stopRequested = true;
        closeMicrophoneAsync();
    }

    /**
     * Sblocca subito la {@code read} del worker chiudendo il microfono, ma <b>mai dal thread UI</b>.
     *
     * <p>{@code CarAudioRecord.stopRecording()} è una chiamata binder <i>sincrona</i> verso l'host
     * ({@code ICarAudioCallback.onStopRecording}, transact senza FLAG_ONEWAY + readException): farla dentro un
     * click listener o in {@code Screen.onStop} significa bloccare il thread UI aspettando l'host, mentre l'host
     * sta aspettando che il nostro callback ritorni — ogni tap su Invia/Annulla diventerebbe un ANR se l'host è
     * lento. {@link #stopRequested} è già il segnale vero (il microfono consegna un blocco ogni ~16 ms a 16 kHz,
     * quindi il worker lo vede alla read successiva); qui si manda solo lo sblocco esplicito su una coda di
     * lavoro, e il {@code finally} del worker rilascia comunque il record.</p>
     */
    private void closeMicrophoneAsync() {
        final CarAudioRecord record = audioRecord;
        if (record == null) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            try {
                record.stopRecording();
            } catch (Throwable ignored) {
            }
        });
    }

    /** Called when the screen goes away for good: never leave the microphone, the encoder or a clip behind. */
    @MainThread
    public void detach(@Nullable Listener owner) {
        if (listener != owner) {
            return;
        }
        listener = null;
        cancel();
    }

    /**
     * La schermata è solo passata in secondo piano (il guidatore ha dato un'occhiata alle mappe): si chiude il
     * microfono, ma la clip <b>resta</b> e lo stato arriva a {@link #STATE_RECORDED}.
     *
     * <p>Il listener non si stacca: la schermata è ancora sullo stack e quando torna visibile deve trovare la sua
     * clip pronta da inviare, non una registrazione nuova. Buttare via l'audio appena registrato perché si è
     * guardata la mappa per tre secondi è perdita di dati in marcia.</p>
     */
    @MainThread
    public void suspendCapture(@Nullable Listener owner) {
        if (listener != owner) {
            return;
        }
        if (state == STATE_STARTING || state == STATE_RECORDING) {
            stopRequested = true;
            closeMicrophoneAsync();
        }
    }

    // ===== worker =====

    private void recordLoop(@NonNull CarContext carContext) {
        int failure = 0;
        boolean capReached = false;
        boolean encoderOpen = false;
        CarAudioRecord record = null;
        try {
            prepareOutputFile();
            if (nativeStartRecord(file.getPath(), SAMPLE_RATE) == 0) {
                FileLog.e("morsegram/car: cant init opus encoder");
                failure = ERR_ENCODER;
                nativeStopRecord();
            } else if (!requestAudioFocus()) {
                // niente focus = la musica o la navigazione continuerebbero a suonare DENTRO il vocale
                encoderOpen = true;
                FileLog.e("morsegram/car: audio focus refused, not recording");
                failure = ERR_NO_MIC;
            } else {
                encoderOpen = true;
                record = CarAudioRecord.create(carContext);
                record.startRecording();
                audioRecord = record;
                AndroidUtilities.runOnUIThread(this::onStarted);

                final byte[] buffer = new byte[CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE];
                while (!stopRequested) {
                    int len = record.read(buffer, 0, buffer.length);
                    if (len < 0) {
                        // the host closed the microphone (another app took it, or the session ended)
                        FileLog.d("morsegram/car: microphone closed by host");
                        break;
                    }
                    if (len == 0) {
                        try {
                            Thread.sleep(2);
                        } catch (InterruptedException ignore) {
                        }
                        continue;
                    }
                    feed(buffer, len);
                    if (totalPcmBytes / BYTES_PER_MS >= MAX_DURATION_MS) {
                        capReached = true;
                        break;
                    }
                }
                if (totalPcmBytes == 0 && failure == 0 && !stopRequested) {
                    failure = ERR_NO_MIC;
                }
            }
        } catch (Throwable t) {
            if (stopRequested) {
                // il microfono è stato chiuso da noi per sbloccare la read: è uno stop, non un errore
                FileLog.d("morsegram/car: recording interrupted by stop request");
            } else {
                FileLog.e("morsegram/car: voice recording failed", t);
                if (failure == 0) {
                    failure = encoderOpen ? ERR_NO_MIC : ERR_ENCODER;
                }
            }
        } finally {
            if (record != null) {
                try {
                    record.stopRecording();
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
            audioRecord = null;
            abandonAudioFocus();
            if (encoderOpen) {
                try {
                    // a short last frame (len/2 < frame_size) is what sets e_o_s in audio.c and closes the ogg stream
                    nativeWriteFrame(frameBuffer, frameBuffer.position());
                } catch (Throwable t) {
                    FileLog.e(t);
                }
                nativeStopRecord();
            }
        }

        final long durationMs = totalPcmBytes / BYTES_PER_MS;
        if (failure == 0 && !cancelRequested && durationMs >= MIN_DURATION_MS && file != null && file.exists()) {
            try {
                waveform = MediaController.getWaveform(file.getAbsolutePath());
            } catch (Throwable t) {
                FileLog.e(t);
                waveform = null;
            }
        }
        final int finalFailure = failure;
        final boolean finalCap = capReached;
        AndroidUtilities.runOnUIThread(() -> onWorkerDone(finalFailure, durationMs, finalCap));
    }

    // ===== focus audio (sul worker) =====

    /**
     * Prende il focus audio in <b>esclusiva</b> prima di aprire il microfono, e lo lascia alla fine della cattura.
     *
     * <p>Gli altoparlanti dell'auto non si zittiscono da soli: senza questo, il brano aperto dalla scheda Musica o
     * la voce del navigatore finiscono <i>dentro</i> il vocale (e continuano a coprire la voce del guidatore). È
     * quello che fa anche l'esempio "microphone" della Car App Library: {@code AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE}
     * con {@code CONTENT_TYPE_SPEECH}/{@code USAGE_ASSISTANT} prima di {@code startRecording()} e rilascio dopo
     * {@code stopRecording()}.</p>
     *
     * <p>Alla perdita del focus (prompt di navigazione, chiamata in arrivo) la cattura si ferma da sé: basta
     * {@link #stopRequested}, il worker lo vede alla read successiva (~16 ms) e la clip registrata fin lì resta
     * pronta da inviare. Il listener arriva sul thread UI, quindi qui non si tocca il {@link CarAudioRecord}.</p>
     */
    private boolean requestAudioFocus() {
        try {
            final AudioManager manager = (AudioManager) ApplicationLoader.applicationContext
                    .getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) {
                return false;
            }
            final AudioManager.OnAudioFocusChangeListener listener = focusChange -> {
                if (focusChange != AudioManager.AUDIOFOCUS_GAIN) {
                    FileLog.d("morsegram/car: audio focus lost (" + focusChange + "), stopping capture");
                    stopRequested = true;
                }
            };
            final int result = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? requestFocusApi26(manager, listener)
                    : manager.requestAudioFocus(listener, AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE);
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                return false;
            }
            focusListener = listener;
            focusManager = manager;
            return true;
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private int requestFocusApi26(@NonNull AudioManager manager,
                                  @NonNull AudioManager.OnAudioFocusChangeListener listener) {
        final AudioFocusRequest request =
                new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .build())
                        .setOnAudioFocusChangeListener(listener)
                        .build();
        final int result = manager.requestAudioFocus(request);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request;
        }
        return result;
    }

    /** Sempre dal {@code finally} del worker: il focus non va mai lasciato appeso dopo la cattura. */
    private void abandonAudioFocus() {
        final AudioManager manager = focusManager;
        final Object request = focusRequest;
        final AudioManager.OnAudioFocusChangeListener listener = focusListener;
        focusManager = null;
        focusRequest = null;
        focusListener = null;
        if (manager == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && request instanceof AudioFocusRequest) {
                manager.abandonAudioFocusRequest((AudioFocusRequest) request);
            } else if (listener != null) {
                manager.abandonAudioFocus(listener);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void prepareOutputFile() {
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.file_reference = new byte[0];
        doc.dc_id = Integer.MIN_VALUE;
        doc.id = SharedConfig.getLastLocalId();
        doc.user_id = UserConfig.getInstance(account).getClientUserId();
        doc.mime_type = "audio/ogg";
        SharedConfig.saveConfig();
        document = doc;

        FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE).mkdirs();
        file = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO),
                System.currentTimeMillis() + "_" + FileLoader.getAttachFileName(doc));
        AutoDeleteMediaTask.lockFile(file);
    }

    /** Splits the microphone chunks into the 1920-byte frames the native encoder wants. */
    private void feed(byte[] src, int len) throws Exception {
        int offset = 0;
        while (offset < len) {
            int n = Math.min(len - offset, frameBuffer.remaining());
            frameBuffer.put(src, offset, n);
            offset += n;
            if (!frameBuffer.hasRemaining()) {
                // GetDirectBufferAddress ignores the position: the frame always starts at index 0
                nativeWriteFrame(frameBuffer, FRAME_BYTES);
                frameBuffer.clear();
            }
        }
        totalPcmBytes += len;
    }

    // ===== completion (main thread) =====

    private void onStarted() {
        if (state == STATE_STARTING) {
            state = STATE_RECORDING;
        }
        if (listener != null) {
            listener.onRecordingStarted();
        }
    }

    private void onWorkerDone(int failure, long durationMs, boolean capReached) {
        worker = null;
        boolean cancelled = cancelRequested;
        stopRequested = false;
        cancelRequested = false;
        if (cancelled) {
            discard();
            state = STATE_IDLE;
            return;
        }
        if (failure != 0) {
            discard();
            state = STATE_IDLE;
            notifyFailed(failure);
            return;
        }
        if (durationMs < MIN_DURATION_MS || file == null || !file.exists()) {
            discard();
            state = STATE_IDLE;
            notifyFailed(ERR_TOO_SHORT);
            return;
        }
        recordedDurationMs = durationMs;
        state = STATE_RECORDED;
        if (sendWhenStopped) {
            sendWhenStopped = false;
            sendRecorded();
        } else if (listener != null) {
            listener.onRecordingStopped(durationMs, capReached);
        }
    }

    /** Builds the voice message exactly like MediaController.stopRecordingInternal and hands it to the sender. */
    private void sendRecorded() {
        final File f = file;
        final TLRPC.TL_document doc = document;
        if (f == null || doc == null || !f.exists()) {
            discard();
            state = STATE_IDLE;
            notifyFailed(ERR_TOO_SHORT);
            return;
        }
        try {
            doc.date = ConnectionsManager.getInstance(account).getCurrentTime();
            doc.size = f.length();

            TLRPC.TL_documentAttributeAudio attributeAudio = new TLRPC.TL_documentAttributeAudio();
            attributeAudio.voice = true;
            attributeAudio.waveform = waveform;
            if (attributeAudio.waveform != null) {
                attributeAudio.flags |= 4;
            }
            attributeAudio.duration = recordedDurationMs / 1000.0;
            doc.attributes.clear();
            doc.attributes.add(attributeAudio);

            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                    doc, null, f.getAbsolutePath(), dialogId,
                    null, null, null, null, null, null,
                    true, 0, 0, 0, null, null, false);
            SendMessagesHelper.getInstance(account).sendMessage(params);
            MorsegramAutoCommon.voicesChanged();   // l'albero media di produzione mostri subito la chat
            FileLog.d("morsegram/car: voice note sent, " + recordedDurationMs + "ms " + f.length() + "b");
        } catch (Throwable t) {
            FileLog.e("morsegram/car: cannot send car voice note", t);
            discard();
            state = STATE_IDLE;
            notifyFailed(ERR_ENCODER);
            return;
        }
        // the file now belongs to the outgoing message: do not delete it, just forget it
        file = null;
        document = null;
        waveform = null;
        state = STATE_IDLE;
        if (listener != null) {
            listener.onVoiceSent();
        }
    }

    private void notifyFailed(int code) {
        if (listener != null) {
            listener.onFailed(code);
        }
    }

    private void discard() {
        if (file != null) {
            AutoDeleteMediaTask.unlockFile(file);
            if (file.exists()) {
                file.delete();
            }
        }
        file = null;
        document = null;
        waveform = null;
        recordedDurationMs = 0;
        totalPcmBytes = 0;
        frameBuffer.clear();
    }
}

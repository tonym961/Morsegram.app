/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.voice;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.util.ArrayList;

/**
 * Una sessione di {@link SpeechRecognizer} su un intervallo [start, end) di un file PCM 16 kHz mono 16 bit,
 * alimentata con {@link ParcelFileDescriptor#createPipe()} tramite {@code RecognizerIntent.EXTRA_AUDIO_SOURCE} (API 33).
 * Solo main thread per le chiamate al riconoscitore (vincolo di SpeechRecognizer); il writer è un thread proprio.
 * <ul>
 * <li>Pipe ridotta a una pagina (F_SETPIPE_SZ, best effort) e lato scrittura non bloccante (O_NONBLOCK): i byte
 *     accettati dalla pipe approssimano a ≤ {@link #pipeCapacity()} quelli consumati dal riconoscitore, così il
 *     chiamante può ripartire dal punto giusto se la sessione termina prima della fine dell'intervallo (riconoscitore
 *     non segmentato: chiude all'endpoint della prima frase).</li>
 * <li>Watchdog: nessun byte accettato per {@link #NO_PROGRESS_MS} ⇒ il riconoscitore NON legge il descrittore
 *     (potrebbe aver aperto il microfono, l'app ha RECORD_AUDIO): cancel() e {@link #ERR_NO_PROGRESS}, che alla prima
 *     probe disattiva la funzione. Scadenza dura proporzionale alla durata del segmento.</li>
 * <li>Segmented session (EXTRA_SEGMENTED_SESSION) richiesta: se onorata arrivano onSegmentResults per frase e
 *     onEndOfSegmentedSession a fine file; altrimenti onResults chiude la sessione e il chiamante riparte.</li>
 * </ul>
 * Niente lascia il dispositivo: con {@code onDevice=true} il riconoscitore è quello di
 * {@link SpeechRecognizer#createOnDeviceSpeechRecognizer}; con {@code false} (solo opt-in utente) è quello di sistema
 * con EXTRA_PREFER_OFFLINE, che non garantisce l'offline.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
final class SpeechSession {

    interface Listener {
        void onPartial(String text);

        void onSegment(String text);

        /** Sessione conclusa senza errore. {@code writerDone}: tutto l'intervallo è stato accettato dalla pipe. */
        void onFinished(boolean writerDone, long accepted);

        /** Errore di SpeechRecognizer (codici ERROR_*) o uno degli ERR_* locali. */
        void onFailed(int error, boolean writerDone, long accepted);
    }

    static final int ERR_NO_PROGRESS = -100;
    static final int ERR_TIMEOUT = -101;
    static final int ERR_CREATE = -102;

    /** Dopo onReadyForSpeech: nessun byte accettato per questo intervallo ⇒ il servizio non legge il descrittore. */
    static final long NO_PROGRESS_MS = 6_000;
    /** Senza onReadyForSpeech (bind + caricamento modello a freddo): attesa massima senza letture prima dello stesso verdetto. */
    static final long NOT_READY_MS = 20_000;
    private static final long TAIL_MS = 20_000;
    private static final int F_SETPIPE_SZ = 1031; // F_LINUX_SPECIFIC_BASE + 7, non esposto da OsConstants
    private static final int BLOCK = PcmDecoder.FRAME_BYTES; // 100 ms

    private final Context context;
    private final boolean onDevice;
    private final String language;
    private final File pcm;
    private final long start;
    private final long end;
    private final Listener listener;

    private SpeechRecognizer recognizer;
    private ParcelFileDescriptor[] pipe;
    private Writer writer;
    private int pipeCapacity = 65536;
    private boolean done;
    private boolean cancelled;
    private long startedAt;
    private long hardDeadline;
    private long readyAt;
    private final Runnable watchdog = this::watchdog;

    SpeechSession(Context context, boolean onDevice, String language, File pcm, long start, long end, Listener listener) {
        this.context = context;
        this.onDevice = onDevice;
        this.language = language;
        this.pcm = pcm;
        this.start = start;
        this.end = end;
        this.listener = listener;
    }

    static Intent intent(Context context, String language, ParcelFileDescriptor source) {
        final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.getPackageName());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false);
        intent.putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY);
        intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        if (source != null) {
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, PcmDecoder.SAMPLE_RATE);
            intent.putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE);
        }
        return intent;
    }

    /** Crea il riconoscitore richiesto (main thread); null se non disponibile. */
    static SpeechRecognizer create(Context context, boolean onDevice) {
        try {
            if (onDevice) {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
            }
            return SpeechRecognizer.createSpeechRecognizer(context);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Main thread. */
    void start() {
        try {
            recognizer = create(context, onDevice);
            if (recognizer == null) {
                fail(ERR_CREATE);
                return;
            }
            pipe = ParcelFileDescriptor.createPipe();
            final FileDescriptor wfd = pipe[1].getFileDescriptor();
            try {
                pipeCapacity = Math.max(4096, Os.fcntlInt(wfd, F_SETPIPE_SZ, 4096));
            } catch (Throwable ignore) {
                pipeCapacity = 65536;
            }
            final int flags = Os.fcntlInt(wfd, OsConstants.F_GETFL, 0);
            Os.fcntlInt(wfd, OsConstants.F_SETFL, flags | OsConstants.O_NONBLOCK);
            final Intent intent = intent(context, language, pipe[0]);
            startedAt = SystemClock.elapsedRealtime();
            final long segmentMs = (end - start) * 1000L / PcmDecoder.BYTES_PER_SECOND;
            hardDeadline = startedAt + 30_000 + segmentMs * 3;
            recognizer.setRecognitionListener(recognitionListener);
            recognizer.startListening(intent);
            writer = new Writer();
            writer.lastProgress = startedAt;
            writer.start();
            AndroidUtilities.runOnUIThread(watchdog, 1000);
            log("session start " + start + ".." + end + " lang=" + language + " onDevice=" + onDevice + " pipe=" + pipeCapacity);
        } catch (Throwable e) {
            FileLog.e(e);
            fail(ERR_CREATE);
        }
    }

    void cancel() {
        if (done) {
            return;
        }
        cancelled = true;
        teardown();
    }

    int pipeCapacity() {
        return pipeCapacity;
    }

    boolean writerDone() {
        return writer != null && writer.eof;
    }

    long accepted() {
        return writer == null ? 0 : writer.accepted;
    }

    private void watchdog() {
        if (done) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (writer != null && !writer.eof && !writer.stopped) {
            // prima di onReadyForSpeech il servizio può ancora star caricando il modello: finestra lunga; dopo, 6 s senza letture = microfono o descrittore ignorato
            final long idle = now - Math.max(writer.lastProgress, readyAt);
            if (readyAt > 0 ? idle > NO_PROGRESS_MS : idle > NOT_READY_MS) {
                log("watchdog: no progress, ready=" + (readyAt > 0) + " accepted=" + writer.accepted);
                fail(ERR_NO_PROGRESS);
                return;
            }
        }
        if (now > hardDeadline || (writer != null && writer.eof && now - writer.eofAt > TAIL_MS) || (writer != null && writer.stopped && !writer.eof && now - writer.lastProgress > 15_000)) {
            log("watchdog: timeout, accepted=" + accepted() + " eof=" + writerDone());
            fail(ERR_TIMEOUT);
            return;
        }
        AndroidUtilities.runOnUIThread(watchdog, 1000);
    }

    private void finish() {
        if (done) {
            return;
        }
        final boolean eof = writerDone();
        final long acc = accepted();
        teardown();
        listener.onFinished(eof, acc);
    }

    private void fail(int error) {
        if (done) {
            return;
        }
        final boolean eof = writerDone();
        final long acc = accepted();
        teardown();
        listener.onFailed(error, eof, acc);
    }

    private void teardown() {
        done = true;
        AndroidUtilities.cancelRunOnUIThread(watchdog);
        if (writer != null) {
            writer.stopped = true;
            writer.interrupt();
        }
        if (recognizer != null) {
            try {
                recognizer.cancel();
            } catch (Throwable ignore) {
            }
            try {
                recognizer.destroy();
            } catch (Throwable ignore) {
            }
            recognizer = null;
        }
        if (pipe != null) {
            // lato lettura: copia locale del descrittore già passato al servizio. Lato scrittura: lo chiude il writer nel suo finally
            // (chiuderlo da qui mentre Os.write è in corso su un altro thread potrebbe colpire un fd riassegnato nel frattempo).
            try {
                pipe[0].close();
            } catch (Throwable ignore) {
            }
            if (writer == null || writer.getState() == Thread.State.NEW) {
                try {
                    pipe[1].close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static String first(Bundle b) {
        if (b == null) {
            return null;
        }
        final ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private final RecognitionListener recognitionListener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) {
            readyAt = SystemClock.elapsedRealtime();
            log("onReadyForSpeech accepted=" + accepted());
        }

        @Override
        public void onBeginningOfSpeech() {
        }

        @Override
        public void onRmsChanged(float rmsdB) {
        }

        @Override
        public void onBufferReceived(byte[] buffer) {
        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onError(int error) {
            if (done) {
                return;
            }
            log("onError " + error + " accepted=" + accepted() + " eof=" + writerDone());
            if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                finish();
            } else {
                fail(error);
            }
        }

        @Override
        public void onResults(Bundle results) {
            if (done) {
                return;
            }
            final String text = first(results);
            log("onResults accepted=" + accepted() + " eof=" + writerDone());
            if (text != null && !text.isEmpty()) {
                listener.onSegment(text);
            }
            finish();
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (done) {
                return;
            }
            final String text = first(partialResults);
            if (text != null) {
                listener.onPartial(text);
            }
        }

        @Override
        public void onSegmentResults(Bundle segmentResults) {
            if (done) {
                return;
            }
            final String text = first(segmentResults);
            log("onSegmentResults accepted=" + accepted());
            if (text != null && !text.isEmpty()) {
                listener.onSegment(text);
            }
        }

        @Override
        public void onEndOfSegmentedSession() {
            if (done) {
                return;
            }
            log("onEndOfSegmentedSession accepted=" + accepted() + " eof=" + writerDone());
            finish();
        }

        @Override
        public void onEvent(int eventType, Bundle params) {
        }
    };

    /** Legge il PCM da {@code start} e lo spinge nella pipe a blocchi da 100 ms; a fine intervallo chiude (= EOF). */
    private final class Writer extends Thread {
        volatile boolean stopped;
        volatile boolean eof;
        volatile long accepted;
        volatile long lastProgress;
        volatile long eofAt;

        Writer() {
            super("morsegram-stt-writer");
            setDaemon(true);
        }

        @Override
        public void run() {
            final byte[] buf = new byte[BLOCK];
            try (FileInputStream in = new FileInputStream(pcm)) {
                long skipped = 0;
                while (skipped < start) {
                    final long s = in.skip(start - skipped);
                    if (s <= 0) {
                        break;
                    }
                    skipped += s;
                }
                long remaining = end - start;
                final FileDescriptor fd = pipe[1].getFileDescriptor();
                while (remaining > 0 && !stopped) {
                    final int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                    if (n <= 0) {
                        break;
                    }
                    int off = 0;
                    while (off < n && !stopped) {
                        try {
                            final int w = Os.write(fd, buf, off, n - off);
                            if (w > 0) {
                                off += w;
                                accepted += w;
                                lastProgress = SystemClock.elapsedRealtime();
                            }
                        } catch (ErrnoException e) {
                            if (e.errno == OsConstants.EAGAIN) {
                                Thread.sleep(5);
                            } else {
                                // EPIPE: il lettore ha chiuso; la sessione sta per concludersi via callback
                                stopped = true;
                                break;
                            }
                        }
                    }
                    remaining -= n;
                }
                if (remaining <= 0 && !stopped) {
                    eof = true;
                    eofAt = SystemClock.elapsedRealtime();
                }
            } catch (Throwable e) {
                if (!stopped) {
                    FileLog.e(e);
                }
            } finally {
                try {
                    pipe[1].close();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static void log(String s) {
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("[morsegram-voice] " + s);
        }
    }
}

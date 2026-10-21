/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.prompt.Generation;
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.Utilities;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import it.iotatec.morsegram.MorsegramGates;
import it.iotatec.morsegram.MorsegramPrefs;

/**
 * Il modello sul dispositivo: stato, scaricamento, limiti veri, traduzione degli errori. L'inferenza sta in
 * {@link OnDeviceProvider}; qui c'è tutto quello che serve a sapere SE si può fare e a dirlo all'utente.
 *
 * <p><b>Niente si deduce dal nome del telefono.</b> Google pubblica un elenco di dispositivi, ma l'elenco è una
 * fotografia e i nomi commerciali non si mappano con sicurezza sui modelli ({@code SM-F971B} non si risolve da
 * una fonte Samsung ufficiale). Qui si chiede al dispositivo: {@link #status()} viene da {@code checkStatus()},
 * la finestra da {@code getTokenLimit()}, il prompt di sistema da {@code isSystemPromptAvailable()} e il nome del
 * modello da {@code getBaseModelName()}. La documentazione è esplicita sul fatto che {@code checkStatus()} va
 * chiamato prima di mostrare qualunque interfaccia, e per questo l'interruttore generale e il fornitore
 * predefinito lo leggono da qui.</p>
 *
 * <p><b>Pigramente, mai all'avvio.</b> {@code checkStatus()} è una chiamata binder verso AICore e il cold start è
 * una promessa dell'app: la sonda gira su {@code Utilities.globalQueue} alla prima lettura e lascia il risultato
 * in un campo volatile, come fa {@code LocalTranscriber} con i suoi flag. Finché non è tornata lo stato è
 * {@link #STATUS_UNKNOWN} e nessuno mente all'utente.</p>
 *
 * <p><b>Trappola di questo fork:</b> la patch 25 rimuove {@code MlKitInitProvider} dal manifest, quindi
 * {@code MlKit.initialize} arriva da {@code StartupReporter} 1,5 s dopo il primo resume. {@code genai-prompt}
 * passa da {@code MlKitContext}: se la nostra prima chiamata arriva prima, {@link #client()} inizializza ML Kit a
 * mano e riprova una volta. Nuovo call site da tenere nell'elenco del punto 10 della checklist di MORSEGRAM.md,
 * accanto a {@code SubjectSegmentation.getClient} e {@code LanguageIdentification.getClient}.</p>
 *
 * <p>Tutto questo file è raggiungibile solo dietro {@link MorsegramGates#LOCAL_AI}: chi chiama scrive
 * {@code MorsegramGates.LOCAL_AI && OnDeviceModel.x()}, così nell'edizione senza modello locale R8 elimina la
 * classe e con lei la libreria.</p>
 */
public final class OnDeviceModel {

    /** Sonda non ancora tornata: non si sa, e non si finge di sapere. */
    public static final int STATUS_UNKNOWN = -1;
    // gli altri valori sono quelli di FeatureStatus: 0 UNAVAILABLE, 1 DOWNLOADABLE, 2 DOWNLOADING, 3 AVAILABLE

    /** Tetto all'attesa di una chiamata di servizio (stato, limiti, conteggio token). */
    static final long SERVICE_TIMEOUT_MS = 20_000;
    /** Tetto all'attesa di una singola inferenza. Un blocco pieno costa qualche secondo, non minuti. */
    static final long INFERENCE_TIMEOUT_MS = 180_000;

    private static final Object lock = new Object();

    private static volatile GenerativeModelFutures client;
    private static volatile int status = STATUS_UNKNOWN;
    private static volatile boolean probing;
    private static volatile boolean downloading;
    /** Finestra vera del dispositivo in token; 0 = non ancora noto. */
    private static volatile int tokenLimit;
    /** -1 non noto, 0 niente prompt di sistema (Nano v2), 1 sì (Nano v3+). */
    private static volatile int systemPrompt = -1;
    private static volatile String baseModel = "";

    private OnDeviceModel() {
    }

    /* ======================================================================================== stato */

    /**
     * Stato noto adesso, senza bloccare. Alla prima chiamata avvia la sonda di fondo: quando torna, gli
     * ascoltatori di {@link AiGate} vengono avvisati e la UI si ridisegna da sé.
     */
    public static int status() {
        final int s = status;
        if (s == STATUS_UNKNOWN) {
            probe();
        }
        return s;
    }

    /** Il dispositivo sa fare inferenza locale (modello pronto, da scaricare o in scaricamento). */
    public static boolean supported() {
        final int s = status();
        return s == FeatureStatus.AVAILABLE || s == FeatureStatus.DOWNLOADABLE || s == FeatureStatus.DOWNLOADING;
    }

    /** Il modello è sul telefono e utilizzabile adesso. */
    public static boolean ready() {
        return status() == FeatureStatus.AVAILABLE;
    }

    /** Il modello manca e si può scaricare: va spiegato e mostrato, non fatto di nascosto. */
    public static boolean needsDownload() {
        final int s = status();
        return s == FeatureStatus.DOWNLOADABLE || s == FeatureStatus.DOWNLOADING;
    }

    /** Nome del modello di base, p.es. {@code nano-v2}. Vuoto se non ancora letto. */
    public static String baseModelName() {
        return baseModel;
    }

    /** Finestra del dispositivo in token, 0 se non nota. */
    public static int knownTokenLimit() {
        return tokenLimit;
    }

    /** Chiave di stringa per la riga delle impostazioni. */
    public static String statusKey() {
        switch (status()) {
            case FeatureStatus.AVAILABLE:
                return "MorsegramAiLocalReady";
            case FeatureStatus.DOWNLOADABLE:
                return "MorsegramAiLocalToDownload";
            case FeatureStatus.DOWNLOADING:
                return "MorsegramAiLocalDownloading";
            case FeatureStatus.UNAVAILABLE:
                return "MorsegramAiLocalUnavailable";
            default:
                return "MorsegramAiLocalChecking";
        }
    }

    /** Valore leggibile della riga delle impostazioni: stato e, quando lo sappiamo, il modello. */
    public static String statusText() {
        final String base = LocaleController.getString(statusKey());
        final String model = baseModel;
        if (status() == FeatureStatus.AVAILABLE && model.length() > 0) {
            return base + " · " + model;
        }
        return base;
    }

    /**
     * Sonda di fondo: stato, finestra, prompt di sistema, nome del modello. Una volta per avvio, su
     * {@code globalQueue}, mai sul thread UI e mai durante l'avvio a freddo.
     */
    public static void probe() {
        if (probing) {
            return;
        }
        synchronized (lock) {
            if (probing) {
                return;
            }
            probing = true;
        }
        Utilities.globalQueue.postRunnable(() -> {
            int next = FeatureStatus.UNAVAILABLE;
            try {
                final GenerativeModelFutures futures = client();
                final Integer s = futures.checkStatus().get(SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                next = s != null ? s : FeatureStatus.UNAVAILABLE;
                if (next == FeatureStatus.AVAILABLE) {
                    readLimits(futures);
                }
            } catch (Throwable e) {
                // AICore assente (è il caso dell'emulatore: il pacchetto com.google.android.aicore non esiste),
                // binder caduto, o configurazioni non ancora scaricate: tutto questo è "non disponibile", non un
                // guasto da mostrare. L'utente lo vede come "non disponibile su questo telefono".
                FileLog.e(e, false);
                next = FeatureStatus.UNAVAILABLE;
            } finally {
                status = next;
                probing = false;
                AiGate.notifyChanged();
            }
        });
    }

    /** Rifà la sonda: dopo uno scaricamento o quando l'utente apre la riga delle impostazioni. */
    public static void refresh() {
        status = STATUS_UNKNOWN;
        probe();
    }

    private static void readLimits(GenerativeModelFutures futures) {
        try {
            final Integer limit = futures.getTokenLimit().get(SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (limit != null && limit > 0) {
                tokenLimit = limit;
            }
        } catch (Throwable ignore) {
        }
        try {
            final Boolean sys = futures.isSystemPromptAvailable().get(SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (sys != null) {
                systemPrompt = sys ? 1 : 0;
            }
        } catch (Throwable ignore) {
        }
        try {
            final String name = futures.getBaseModelName().get(SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (name != null && name.trim().length() > 0) {
                baseModel = name.trim();
            }
        } catch (Throwable ignore) {
        }
    }

    /* ====================================================================================== cliente */

    /**
     * Il cliente ML Kit, creato una volta. Vedi la trappola {@code MlKitContext} nel javadoc di classe: se ML Kit
     * non è ancora inizializzato lo inizializziamo noi e si riprova una volta sola.
     */
    static GenerativeModelFutures client() throws Exception {
        GenerativeModelFutures c = client;
        if (c != null) {
            return c;
        }
        synchronized (lock) {
            if (client == null) {
                client = GenerativeModelFutures.from(create());
            }
            return client;
        }
    }

    private static com.google.mlkit.genai.prompt.GenerativeModel create() throws Exception {
        try {
            return Generation.INSTANCE.getClient();
        } catch (Throwable first) {
            // "MlKitContext has not been initialized": il manifest del fork rimuove MlKitInitProvider e
            // StartupReporter chiama MlKit.initialize 1,5 s dopo il primo resume. Se siamo arrivati prima,
            // inizializziamo qui. MlKit.initialize non è idempotente: una seconda chiamata lancia e si ignora.
            try {
                com.google.mlkit.common.MlKit.initialize(ApplicationLoader.applicationContext);
            } catch (Throwable ignore) {
            }
            return Generation.INSTANCE.getClient();
        }
    }

    /* ================================================================================ scaricamento */

    /** Avanzamento dello scaricamento del modello, sul thread UI. */
    public interface DownloadListener {
        /** @param totalBytes quanto pesa davvero: il numero lo dice il dispositivo, non è pubblicato da Google */
        void onStarted(long totalBytes);

        void onProgress(long bytes, long totalBytes);

        void onCompleted();

        void onFailed(String detail);
    }

    /**
     * Scarica il modello. Non esiste nessuna finestra di consenso di sistema: {@code download()} parte e basta,
     * quindi l'unico rifiuto possibile è quello alla NOSTRA richiesta e lo ricordiamo noi
     * ({@link #setDeclined(boolean)}). Il peso si sa solo da {@code onDownloadStarted}.
     *
     * <p><b>Perché si guarda anche il future, e non solo la callback.</b> {@code download(callback)}
     * restituisce un {@code ListenableFuture<Void>} e TUTTI i metodi di {@code DownloadCallback} sono
     * {@code default}: il canale di errore garantito dalla libreria è il future, la callback è un extra.
     * {@link #downloading} è un fermo che solo la chiusura azzera, quindi un future fallito senza
     * {@code onDownloadFailed} lo lascerebbe acceso per tutta la vita del processo: la finestra di attesa di
     * {@code AiSheet} non si risolverebbe mai e da lì in poi ogni richiesta risponderebbe «già in
     * scaricamento», con l'unico rimedio di uccidere l'app. Si ascoltano entrambi i canali e chiude il ciclo
     * chi arriva primo ({@code settled}), così la chiusura è idempotente.
     *
     * <p>Il future si ascolta con {@code Futures.addCallback} e non con {@code get()}: scaricare il modello
     * dura minuti e {@code Utilities.globalQueue} è la coda di tutta l'app, non nostra.
     */
    public static void download(DownloadListener listener) {
        if (downloading) {
            return;
        }
        downloading = true;
        setDeclined(false);
        activeListener = listener;
        final DownloadListener sink = SINK;
        Utilities.globalQueue.postRunnable(() -> {
            final long[] total = new long[1];
            final AtomicBoolean settled = new AtomicBoolean();
            try {
                final GenerativeModelFutures futures = client();
                status = FeatureStatus.DOWNLOADING;
                AiGate.notifyChanged();
                final ListenableFuture<Void> future = futures.download(new DownloadCallback() {
                    @Override
                    public void onDownloadStarted(long bytesToDownload) {
                        total[0] = bytesToDownload;
                        AndroidUtilities.runOnUIThread(() -> sink.onStarted(bytesToDownload));
                    }

                    @Override
                    public void onDownloadProgress(long totalBytesDownloaded) {
                        AndroidUtilities.runOnUIThread(() -> sink.onProgress(totalBytesDownloaded, total[0]));
                    }

                    @Override
                    public void onDownloadCompleted() {
                        if (settled.compareAndSet(false, true)) {
                            onDownloadDone(sink);
                        }
                    }

                    @Override
                    public void onDownloadFailed(GenAiException e) {
                        if (settled.compareAndSet(false, true)) {
                            onDownloadError(sink, e, String.valueOf(e.getErrorCode()));
                        }
                    }
                });
                if (future != null) {
                    Futures.addCallback(future, new FutureCallback<Void>() {
                        @Override
                        public void onSuccess(Void result) {
                            if (settled.compareAndSet(false, true)) {
                                // future a buon fine ma nessuna onDownloadCompleted: non si dichiara pronto un
                                // modello che nessuno ha confermato, lo stato si richiede al dispositivo
                                confirmAfterDownload(sink);
                            }
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            if (settled.compareAndSet(false, true)) {
                                onDownloadError(sink, t, String.valueOf(t.getMessage()));
                            }
                        }
                    }, MoreExecutors.directExecutor());
                }
            } catch (Throwable e) {
                if (settled.compareAndSet(false, true)) {
                    downloading = false;
                    FileLog.e(e, false);
                    status = FeatureStatus.UNAVAILABLE;
                    AiGate.notifyChanged();
                    AndroidUtilities.runOnUIThread(() -> sink.onFailed(String.valueOf(e.getMessage())));
                }
            }
        });
    }

    /** Scaricamento finito: il fermo cade, il modello è pronto, i limiti si rileggono. */
    private static void onDownloadDone(DownloadListener listener) {
        downloading = false;
        status = FeatureStatus.AVAILABLE;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                readLimits(client());
            } catch (Throwable ignore) {
            }
            AiGate.notifyChanged();
        });
        if (listener != null) {
            AndroidUtilities.runOnUIThread(listener::onCompleted);
        }
    }

    /** Scaricamento fallito, da qualunque dei due canali: il fermo cade sempre e lo stato si rilegge. */
    private static void onDownloadError(DownloadListener listener, Throwable e, String detail) {
        downloading = false;
        FileLog.e(e, false);
        refresh();
        if (listener != null) {
            AndroidUtilities.runOnUIThread(() -> listener.onFailed(detail));
        }
    }

    /**
     * Chiusura dal solo future: si chiede lo stato al dispositivo e si risponde con quello, invece di dare per
     * buono un modello disponibile. Il fermo cade qui, non prima, perché fino alla risposta lo scaricamento è
     * ancora in corso per quel che ne sappiamo.
     */
    private static void confirmAfterDownload(DownloadListener listener) {
        Utilities.globalQueue.postRunnable(() -> {
            int next = FeatureStatus.UNAVAILABLE;
            try {
                final GenerativeModelFutures futures = client();
                final Integer s = futures.checkStatus().get(SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                next = s != null ? s : FeatureStatus.UNAVAILABLE;
                if (next == FeatureStatus.AVAILABLE) {
                    readLimits(futures);
                }
            } catch (Throwable e) {
                FileLog.e(e, false);
            }
            final int now = next;
            status = now;
            downloading = false;
            AiGate.notifyChanged();
            if (listener != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (now == FeatureStatus.AVAILABLE) {
                        listener.onCompleted();
                    } else {
                        listener.onFailed("status " + now);
                    }
                });
            }
        });
    }

    public static boolean isDownloading() {
        return downloading;
    }

    /**
     * L'ascoltatore dello scaricamento IN CORSO. Si tiene qui, e non nelle callback della libreria, perché quelle
     * restano vive per tutta la durata dello scaricamento (minuti, decine di minuti): un ascoltatore anonimo di
     * {@code AiSheet} o della pagina delle impostazioni porta con sé il frammento che lo ha creato, cioè una
     * ChatActivity con la sua lista di messaggi e le sue bitmap. Attraverso questo campo lo si può DIMENTICARE
     * senza fermare lo scaricamento, che lo fa Android e non noi.
     */
    private static volatile DownloadListener activeListener;

    /** Inoltro: le callback della libreria tengono solo questo oggetto statico. */
    private static final DownloadListener SINK = new DownloadListener() {
        @Override
        public void onStarted(long totalBytes) {
            final DownloadListener l = activeListener;
            if (l != null) {
                l.onStarted(totalBytes);
            }
        }

        @Override
        public void onProgress(long bytes, long totalBytes) {
            final DownloadListener l = activeListener;
            if (l != null) {
                l.onProgress(bytes, totalBytes);
            }
        }

        @Override
        public void onCompleted() {
            final DownloadListener l = activeListener;
            activeListener = null;
            if (l != null) {
                l.onCompleted();
            }
        }

        @Override
        public void onFailed(String detail) {
            final DownloadListener l = activeListener;
            activeListener = null;
            if (l != null) {
                l.onFailed(detail);
            }
        }
    };

    /**
     * Dimentica l'ascoltatore passato a {@link #download}: lo scaricamento continua, ma chi lo ha chiesto non
     * resta trattenuto. Si chiama quando il chiamante muore (onFragmentDestroy) o quando non c'è più nessuno a
     * cui mostrare l'avanzamento. Passare null, o un ascoltatore che non è quello corrente, non fa niente.
     */
    public static void forgetDownloadListener(DownloadListener listener) {
        if (listener != null && activeListener == listener) {
            activeListener = null;
        }
    }

    /** L'utente ha detto no allo scaricamento: lo si ricorda per non richiederglielo a ogni funzione. */
    public static boolean declined() {
        return MorsegramPrefs.global().getBoolean(MorsegramPrefs.KEY_AI_LOCAL_DECLINED, false);
    }

    public static void setDeclined(boolean value) {
        MorsegramPrefs.global().edit().putBoolean(MorsegramPrefs.KEY_AI_LOCAL_DECLINED, value).apply();
    }

    /**
     * Svuota le cache implicite del modello. La prefix caching scrive file nello storage privato dell'app: è
     * giusto che l'utente li possa buttare via da dove gestisce il resto.
     */
    public static void clearCaches(Runnable done) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                client().clearImplicitCaches().get(SERVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (Throwable e) {
                FileLog.e(e, false);
            }
            if (done != null) {
                AndroidUtilities.runOnUIThread(done);
            }
        });
    }

    /* ================================================================================== inferenza */

    /** Finestra da usare: quella misurata, o il valore documentato della Prompt API se il dispositivo non la dà. */
    static int tokenLimitOrDefault() {
        final int limit = tokenLimit;
        return limit > 0 ? limit : 4000;
    }

    /** Vero se il prompt di sistema esiste su questo dispositivo (Nano v3+); su Nano v2 va dentro il testo. */
    static boolean hasSystemPrompt() {
        return systemPrompt == 1;
    }

    /** Legge limiti e capacità ora, se la sonda non c'è ancora arrivata. */
    static void ensureLimits(GenerativeModelFutures futures) {
        if (tokenLimit <= 0 || systemPrompt < 0 || baseModel.length() == 0) {
            readLimits(futures);
        }
    }

    /**
     * Aspetta un {@link ListenableFuture} traducendo ogni esito in un {@link AiException} classificato, e lo
     * registra su {@code abort} così l'annullamento lo ferma invece di aspettarne la fine.
     */
    static <T> T await(ListenableFuture<T> future, long timeoutMs, AiProvider.Abort abort, String label) throws AiException {
        if (future == null) {
            throw new AiException(AiException.KIND_BAD_REQUEST, label, "no future");
        }
        if (abort != null) {
            abort.attach(future);
            if (abort.isAborted()) {
                future.cancel(true);
                throw new AiException(AiException.KIND_CANCELLED, label, "cancelled");
            }
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.CancellationException e) {
            throw new AiException(AiException.KIND_CANCELLED, label, "cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiException.KIND_CANCELLED, label, "interrupted");
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AiException(aborted(abort) ? AiException.KIND_CANCELLED : AiException.KIND_TIMEOUT, label, "timeout");
        } catch (ExecutionException e) {
            throw toAiException(e.getCause() != null ? e.getCause() : e, label, abort);
        } catch (Throwable e) {
            throw toAiException(e, label, abort);
        } finally {
            if (abort != null) {
                abort.attach((Future<?>) null);
            }
        }
    }

    private static boolean aborted(AiProvider.Abort abort) {
        return abort != null && abort.isAborted();
    }

    /**
     * Mappa degli errori di AICore sui nostri. Da notare che NON esiste un codice di rifiuto per contenuto:
     * {@link AiException#KIND_CONTENT} resta un fatto dei soli fornitori remoti.
     */
    static AiException toAiException(Throwable t, String label, AiProvider.Abort abort) {
        if (aborted(abort)) {
            return new AiException(AiException.KIND_CANCELLED, label, "cancelled");
        }
        if (t instanceof AiException) {
            return (AiException) t;
        }
        if (t instanceof GenAiException) {
            final GenAiException e = (GenAiException) t;
            final int code = e.getErrorCode();
            switch (code) {
                case GenAiException.ErrorCode.CANCELLED:
                    return new AiException(AiException.KIND_CANCELLED, label, "cancelled");
                case GenAiException.ErrorCode.NOT_AVAILABLE:
                case GenAiException.ErrorCode.NOT_SUPPORTED:
                case GenAiException.ErrorCode.AICORE_INCOMPATIBLE:
                case GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE:
                    // il dispositivo ha cambiato idea rispetto alla sonda: si rilegge lo stato
                    refresh();
                    return new AiException(AiException.KIND_LOCAL_MISSING, label, "code " + code);
                case GenAiException.ErrorCode.BUSY:
                case GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED:
                    return new AiException(AiException.KIND_LOCAL_BUSY, label, "code " + code, retryDelayMs(t));
                case GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED:
                    return new AiException(AiException.KIND_LOCAL_QUOTA, label, "code " + code);
                case GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE:
                    return new AiException(AiException.KIND_LOCAL_DISK, label, "code " + code);
                case GenAiException.ErrorCode.REQUEST_TOO_SMALL:
                    return new AiException(AiException.KIND_EMPTY, label, "code " + code);
                case GenAiException.ErrorCode.REQUEST_TOO_LARGE:
                    // errore di budget NOSTRO: OnDeviceProvider lo riconosce e spezza di più, non si mostra
                    return new AiException(AiException.KIND_BAD_REQUEST, label, TOO_LARGE);
                case GenAiException.ErrorCode.CACHE_PROCESSING_ERROR:
                    // la prefix caching non è disponibile o ha fatto cilecca: si rifà la richiesta senza prefisso
                    return new AiException(AiException.KIND_BAD_REQUEST, label, CACHE_ERROR);
                default:
                    return new AiException(AiException.KIND_BAD_REQUEST, label, "code " + code);
            }
        }
        FileLog.e(t, false);
        return new AiException(AiException.KIND_BAD_REQUEST, label, String.valueOf(t.getMessage()));
    }

    /** Dettaglio che {@link OnDeviceProvider} usa per riconoscere un blocco troppo grande e rifarlo più piccolo. */
    static final String TOO_LARGE = "REQUEST_TOO_LARGE";
    /** Dettaglio della prefix caching fallita: si rifà la richiesta con le istruzioni dentro il testo. */
    static final String CACHE_ERROR = "CACHE_PROCESSING_ERROR";

    static boolean isTooLarge(AiException e) {
        return e != null && TOO_LARGE.equals(e.getMessage());
    }

    static boolean isCacheError(AiException e) {
        return e != null && CACHE_ERROR.equals(e.getMessage());
    }

    /** Ritardo consigliato da AICore per un nuovo tentativo, in millisecondi, con un tetto nostro. */
    static long retryDelayMs(Throwable t) {
        try {
            if (t instanceof GenAiException) {
                final java.time.Duration d = ((GenAiException) t).getRetryDelay();
                if (d != null) {
                    final long ms = d.toMillis();
                    if (ms > 0) {
                        return Math.min(ms, 5000);
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        return 1200;
    }
}

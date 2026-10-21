/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

import java.net.HttpURLConnection;
import java.util.concurrent.Future;

import it.iotatec.morsegram.MorsegramGates;

/**
 * Esegue una richiesta all'assistente: scelta del fornitore, ripiego, thread.
 *
 * <p>Threading: la richiesta gira su una coda di fondo nostra ({@code morsegramAi}, una sola, quindi due richieste
 * non si sovrappongono), e il risultato torna sul thread principale via
 * {@link AndroidUtilities#runOnUIThread(Runnable)}. Nessuna chiamata di rete sul thread UI, nessun aggiornamento di
 * vista fuori dal thread UI.</p>
 *
 * <p>Ripiego, due condizioni e non una. La prima: il principale ha fallito per
 * {@link AiException#allowsFallback()} — 429, 5xx, timeout, rete. Su rifiuto di contenuto, chiave non valida o
 * richiesta malformata l'errore arriva all'utente così com'è: mandare lo stesso testo a un secondo servizio non
 * cambierebbe la risposta e raddoppierebbe soltanto i destinatari del testo.</p>
 *
 * <p>La seconda, nuova con il modello sul dispositivo: <b>il ripiego automatico non scavalca mai il confine del
 * dispositivo</b>. Con il modello locale come principale, un {@code BUSY} o una quota di sistema spedirebbero il
 * testo a Groq senza che nessuno l'abbia chiesto — esattamente ciò che l'anteprima deve impedire. Da locale a
 * remoto si passa solo con un atto esplicito dell'utente ({@link #run(AiPayload, String, int, Callback)},
 * chiamato dal pulsante della finestra di errore, che ricostruisce il payload CON oscuramento e passa
 * dall'anteprima). Da remoto a locale il ripiego automatico resta lecito: l'insieme dei destinatari si
 * restringe.</p>
 *
 * <p>Annullabile: {@link Call#cancel()} chiude la connessione viva o cancella l'inferenza in corso, quindi
 * l'annullamento è immediato e non resta un thread appeso fino al timeout.</p>
 */
public final class AiClient {

    public interface Callback {
        /**
         * @param origin chi ha generato il testo: il fornitore che ha RISPOSTO (col ripiego può non essere il
         *               principale) e il modello usato. Serve alla segnalazione del contenuto generato
         *               ({@link AiReports}), che deve dire di chi è il risultato e non di chi avrebbe dovuto
         *               esserlo.
         */
        void onAiResult(String text, AiReports.Origin origin);

        /** Mai chiamato per {@link AiException#KIND_CANCELLED}. */
        void onAiError(AiException error);

        /**
         * Avanzamento di un lavoro a blocchi, sul thread UI. Serve al modello sul dispositivo, dove un arretrato
         * lungo diventa più inferenze in fila: uno spinner muto per mezzo minuto è un difetto. I fornitori di
         * rete fanno un blocco solo e questo metodo non dice niente di utile.
         */
        default void onAiProgress(int step, int total, String partial) {
        }
    }

    /** Una richiesta in corso. Il lavoro vivo è una connessione (rete) o un future (dispositivo). */
    public static final class Call implements HttpAiProvider.HttpAbort {

        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;
        private volatile Future<?> work;

        @Override
        public boolean isAborted() {
            return cancelled;
        }

        @Override
        public void attach(HttpURLConnection conn) {
            connection = conn;
        }

        @Override
        public void attach(Future<?> future) {
            work = future;
        }

        /** Da thread UI: la risposta non arriverà, e il lavoro vivo viene fermato subito. */
        public void cancel() {
            cancelled = true;
            final HttpURLConnection conn = connection;
            connection = null;
            final Future<?> future = work;
            work = null;
            if (future != null) {
                try {
                    future.cancel(true);
                } catch (Throwable ignore) {
                }
            }
            if (conn != null) {
                // disconnect() da un altro thread sblocca la lettura in corso: è il modo previsto da HttpURLConnection
                new Thread(() -> {
                    try {
                        conn.disconnect();
                    } catch (Throwable ignore) {
                    }
                }, "morsegramAiCancel").start();
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    private static volatile DispatchQueue queue;

    private AiClient() {
    }

    private static DispatchQueue queue() {
        DispatchQueue q = queue;
        if (q == null) {
            synchronized (AiClient.class) {
                q = queue;
                if (q == null) {
                    q = new DispatchQueue("morsegramAi");
                    queue = q;
                }
            }
        }
        return q;
    }

    /**
     * Manda {@code payload} al fornitore principale (e, alle condizioni dette sopra, a quello di ripiego).
     *
     * @param taskKey chiave di stringa della funzione che ha chiesto il testo: viaggia fino alla risposta perché
     *                una segnalazione di contenuto generato deve poter dire da quale funzione veniva
     * @return la richiesta, per annullarla. Mai null.
     */
    public static Call run(AiPayload payload, String taskKey, Callback callback) {
        return run(payload, taskKey, AiProvider.NONE, callback);
    }

    /**
     * Come sopra, ma con un fornitore SCELTO dall'utente per questo lavoro: è la via con cui si esce dal modello
     * sul dispositivo, e non ha ripiego (l'utente ha nominato un destinatario, non due).
     *
     * @param chosen {@link AiProvider#NONE} per usare le impostazioni
     */
    public static Call run(AiPayload payload, String taskKey, int chosen, Callback callback) {
        final Call call = new Call();
        if (payload == null) {
            fail(call, callback, new AiException(AiException.KIND_BAD_REQUEST, "", "no payload"));
            return call;
        }
        final int primaryId = chosen != AiProvider.NONE ? chosen : AiSettings.primary();
        final int fallbackId = chosen != AiProvider.NONE ? AiProvider.NONE : AiSettings.fallback();
        queue().postRunnable(() -> {
            AiException first = null;
            try {
                final Answer answer = attempt(primaryId, payload, taskKey, call, callback);
                succeed(call, callback, answer);
                return;
            } catch (AiException e) {
                first = e;
            } catch (Throwable e) {
                FileLog.e(e);
                first = new AiException(AiException.KIND_BAD_REQUEST, AiProvider.labelOf(primaryId), String.valueOf(e.getMessage()));
            }
            if (call.isAborted()) {
                return;
            }
            // ripiego: solo per indisponibilità del principale, solo se ne è configurato uno diverso utilizzabile,
            // e MAI dal dispositivo verso la rete (vedi il javadoc di classe)
            if (first.allowsFallback() && !AiProvider.isLocal(primaryId) && fallbackId != AiProvider.NONE
                    && fallbackId != primaryId && usable(fallbackId)) {
                try {
                    final Answer answer = attempt(fallbackId, payload, taskKey, call, callback);
                    succeed(call, callback, answer);
                    return;
                } catch (AiException e) {
                    fail(call, callback, e);
                    return;
                } catch (Throwable e) {
                    FileLog.e(e);
                    fail(call, callback, new AiException(AiException.KIND_BAD_REQUEST, AiProvider.labelOf(fallbackId), String.valueOf(e.getMessage())));
                    return;
                }
            }
            fail(call, callback, first);
        });
        return call;
    }

    /** Risposta e sua provenienza: chi l'ha prodotta va saputo a valle, non ricostruito dalle impostazioni. */
    private static final class Answer {

        final String text;
        final AiReports.Origin origin;

        Answer(String text, AiReports.Origin origin) {
            this.text = text;
            this.origin = origin;
        }
    }

    /** Se un fornitore può davvero ricevere un lavoro: il locale vuole il modello pronto, gli altri una chiave. */
    private static boolean usable(int providerId) {
        if (AiProvider.isLocal(providerId)) {
            return MorsegramGates.LOCAL_AI && OnDeviceModel.ready();
        }
        return AiKeys.hasKey(providerId);
    }

    private static Answer attempt(int providerId, AiPayload payload, String taskKey, Call call, Callback callback) throws AiException {
        final AiProvider provider = AiProvider.of(providerId);
        if (provider == null) {
            throw new AiException(AiException.KIND_BAD_REQUEST, "", "no provider");
        }
        if (call.isAborted()) {
            throw new AiException(AiException.KIND_CANCELLED, provider.label(), "cancelled");
        }
        if (!provider.local() && !AiKeys.hasKey(providerId)) {
            // la verifica vera (con decifratura) la rifà HttpAiProvider.complete: qui si legge solo una
            // preferenza, perché aprire il Keystore per scoprire che la chiave non c'è è spreco
            throw new AiException(AiException.KIND_NO_KEY, provider.label(), "no key");
        }
        final String text = provider.complete(payload, call, progress(call, callback));
        return new Answer(text, new AiReports.Origin(providerId, provider.model(), taskKey));
    }

    /** L'avanzamento torna sul thread UI, come il risultato: chi lo riceve è una finestra. */
    private static AiProvider.Progress progress(Call call, Callback callback) {
        if (callback == null) {
            return null;
        }
        return (step, total, partial) -> AndroidUtilities.runOnUIThread(() -> {
            if (!call.isAborted()) {
                callback.onAiProgress(step, total, partial);
            }
        });
    }

    private static void succeed(Call call, Callback callback, Answer answer) {
        if (callback == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (!call.isAborted()) {
                callback.onAiResult(answer.text, answer.origin);
            }
        });
    }

    private static void fail(Call call, Callback callback, AiException error) {
        if (callback == null || error.kind() == AiException.KIND_CANCELLED) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (!call.isAborted()) {
                callback.onAiError(error);
            }
        });
    }
}

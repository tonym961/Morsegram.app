/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;

import java.net.HttpURLConnection;

/**
 * Esegue una richiesta all'assistente: scelta del fornitore, ripiego, thread.
 *
 * <p>Threading: la richiesta gira su una coda di fondo nostra ({@code morsegramAi}, una sola, quindi due richieste
 * non si sovrappongono), e il risultato torna sul thread principale via
 * {@link AndroidUtilities#runOnUIThread(Runnable)}. Nessuna chiamata di rete sul thread UI, nessun aggiornamento di
 * vista fuori dal thread UI.</p>
 *
 * <p>Ripiego: si prova il fornitore di ripiego SOLO quando il principale ha fallito per
 * {@link AiException#allowsFallback()} — 429, 5xx, timeout, rete. Su rifiuto di contenuto, chiave non valida o
 * richiesta malformata l'errore arriva all'utente così com'è: mandare lo stesso testo a un secondo servizio non
 * cambierebbe la risposta e raddoppierebbe soltanto i destinatari del testo.</p>
 *
 * <p>Annullabile: {@link Call#cancel()} chiude la connessione viva, quindi l'annullamento è immediato e non resta
 * un thread appeso fino al timeout.</p>
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
    }

    /** Una richiesta in corso. */
    public static final class Call implements AiProvider.Abort {

        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        @Override
        public boolean isAborted() {
            return cancelled;
        }

        @Override
        public void attach(HttpURLConnection conn) {
            connection = conn;
        }

        /** Da thread UI: la risposta non arriverà, e la connessione viva viene chiusa subito. */
        public void cancel() {
            cancelled = true;
            final HttpURLConnection conn = connection;
            connection = null;
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
        final Call call = new Call();
        if (payload == null) {
            fail(call, callback, new AiException(AiException.KIND_BAD_REQUEST, "", "no payload"));
            return call;
        }
        final int primaryId = AiSettings.primary();
        final int fallbackId = AiSettings.fallback();
        queue().postRunnable(() -> {
            AiException first = null;
            try {
                final Answer answer = attempt(primaryId, payload, taskKey, call);
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
            // ripiego: solo per indisponibilità del principale, e solo se ne è configurato uno diverso con chiave
            if (first.allowsFallback() && fallbackId != AiProvider.NONE && fallbackId != primaryId && AiKeys.hasKey(fallbackId)) {
                try {
                    final Answer answer = attempt(fallbackId, payload, taskKey, call);
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

    private static Answer attempt(int providerId, AiPayload payload, String taskKey, Call call) throws AiException {
        final AiProvider provider = AiProvider.of(providerId);
        if (provider == null) {
            throw new AiException(AiException.KIND_BAD_REQUEST, "", "no provider");
        }
        if (call.isAborted()) {
            throw new AiException(AiException.KIND_CANCELLED, provider.label(), "cancelled");
        }
        final String apiKey = AiKeys.key(providerId);
        if (apiKey == null || apiKey.length() == 0) {
            throw new AiException(AiException.KIND_NO_KEY, provider.label(), "no key");
        }
        final String text = provider.complete(apiKey, payload.system, payload.user, payload.maxTokens, call);
        return new Answer(text, new AiReports.Origin(providerId, provider.model(), taskKey));
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

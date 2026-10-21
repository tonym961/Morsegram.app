/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import android.text.TextUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;

/**
 * Fornitore AI. Due implementazioni con forme di API diverse ({@link GroqProvider}, chat completions in stile
 * OpenAI con {@code Authorization: Bearer}; {@link GeminiProvider}, {@code generateContent} con
 * {@code x-goog-api-key} e {@code systemInstruction} separata): quello che hanno in comune — apertura della
 * connessione, invio del corpo, lettura della risposta e classificazione dell'errore in {@link AiException} — sta
 * qui una volta sola, e le sottoclassi dichiarano solo URL, intestazioni, corpo e lettura della risposta.
 *
 * <p>Tutti i metodi di questa classe girano su thread di fondo (vedi {@link AiClient}): non toccano la UI.</p>
 */
public abstract class AiProvider {

    public static final int GROQ = 0;
    public static final int GEMINI = 1;

    /** Nessun fornitore: valore del ripiego quando l'utente non ne vuole uno. */
    public static final int NONE = -1;

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 45000;
    /** Tetto difensivo sulla risposta: nessuna risposta utile è più grande, e una malformata non deve mangiare RAM. */
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Annullamento cooperativo: {@link AiClient.Call} lo implementa. */
    public interface Abort {
        boolean isAborted();

        /** Registra la connessione viva, così l'annullamento la può chiudere senza aspettare il timeout. */
        void attach(HttpURLConnection connection);
    }

    public abstract int id();

    /** Nome mostrato all'utente (non tradotto: è un marchio). */
    public abstract String label();

    /**
     * Modello usato dalle richieste. È una costante: i fornitori ritirano i nomi dei modelli ogni pochi mesi, e
     * quando succede va aggiornata qui (vedi il report di sviluppo).
     */
    public abstract String model();

    /** Il modello scelto dall'utente se c'e', altrimenti quello predefinito del fornitore. */
    protected static String pick(String chosen, String fallback) {
        return chosen == null || chosen.trim().isEmpty() ? fallback : chosen.trim();
    }


    /** URL completo della richiesta. */
    protected abstract String endpoint();

    /** Intestazione di autenticazione: {nome, valore}. */
    protected abstract String[] authHeader(String apiKey);

    /** Corpo JSON della richiesta. */
    protected abstract String body(String system, String user, int maxTokens) throws Exception;

    /**
     * Testo della risposta.
     *
     * @throws AiException con {@link AiException#KIND_CONTENT} se il modello ha rifiutato il contenuto.
     */
    protected abstract String reply(String json) throws Exception;

    public static AiProvider of(int id) {
        if (id == GEMINI) {
            return new GeminiProvider();
        }
        if (id == GROQ) {
            return new GroqProvider();
        }
        return null;
    }

    public static String labelOf(int id) {
        final AiProvider p = of(id);
        return p != null ? p.label() : "";
    }

    /**
     * Una richiesta sincrona. Chiama {@link #body}, legge la risposta e traduce ogni esito in un
     * {@link AiException} classificato: lo stato HTTP decide il {@code kind}, quindi la politica di ripiego di
     * {@link AiClient} non ha bisogno di interpretare testi di errore.
     */
    public final String complete(String apiKey, String system, String user, int maxTokens, Abort abort) throws AiException {
        if (TextUtils.isEmpty(apiKey)) {
            throw new AiException(AiException.KIND_NO_KEY, label(), "no key");
        }
        HttpURLConnection conn = null;
        try {
            final String payload = body(system, user, maxTokens);
            conn = (HttpURLConnection) new URL(endpoint()).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setUseCaches(false);
            // nessun User-Agent con il nome o la versione dell'app: al fornitore non serve sapere chi chiama
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            final String[] auth = authHeader(apiKey);
            if (auth != null && auth.length == 2) {
                conn.setRequestProperty(auth[0], auth[1]);
            }
            if (abort != null) {
                abort.attach(conn);
                if (abort.isAborted()) {
                    throw new AiException(AiException.KIND_CANCELLED, label(), "cancelled");
                }
            }
            final byte[] out = payload.getBytes(UTF8);
            conn.setFixedLengthStreamingMode(out.length);
            final OutputStream os = conn.getOutputStream();
            try {
                os.write(out);
                os.flush();
            } finally {
                close(os);
            }
            final int status = conn.getResponseCode();
            final String text = read(status >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (abort != null && abort.isAborted()) {
                throw new AiException(AiException.KIND_CANCELLED, label(), "cancelled");
            }
            if (status == 429) {
                throw new AiException(AiException.KIND_RATE_LIMIT, label(), snippet(text));
            }
            if (status >= 500) {
                throw new AiException(AiException.KIND_SERVER, label(), status + " " + snippet(text));
            }
            if (status == 401 || status == 403) {
                throw new AiException(AiException.KIND_AUTH, label(), status + " " + snippet(text));
            }
            if (status == 400 && isInvalidKey(text)) {
                // Gemini NON risponde 401 a una chiave sbagliata: risponde 400 INVALID_ARGUMENT con
                // details API_KEY_INVALID. Senza questo caso l'errore finiva in KIND_BAD_REQUEST, e la
                // finestra mostrava "richiesta non valida" SENZA il pulsante per aprire le impostazioni:
                // l'utente non aveva nessuna via per correggere la chiave da dove era.
                throw new AiException(AiException.KIND_AUTH, label(), "400 " + snippet(text));
            }
            if (status == 404 || (status == 400 && text != null && text.contains("NOT_FOUND"))) {
                // Modello ritirato dal fornitore: succede ogni pochi mesi. Non e' un guasto, quindi il
                // ripiego non deve scattare; all'utente si dice di cambiare modello nelle impostazioni.
                throw new AiException(AiException.KIND_MODEL, label(), model() + ": " + snippet(text));
            }
            if (status >= 400) {
                throw new AiException(AiException.KIND_BAD_REQUEST, label(), status + " " + snippet(text));
            }
            final String answer = reply(text);
            if (TextUtils.isEmpty(answer) || TextUtils.isEmpty(answer.trim())) {
                throw new AiException(AiException.KIND_EMPTY, label(), "empty reply");
            }
            return answer.trim();
        } catch (AiException e) {
            throw e;
        } catch (SocketTimeoutException e) {
            throw new AiException(abortedOr(abort, AiException.KIND_TIMEOUT), label(), "timeout");
        } catch (UnknownHostException e) {
            throw new AiException(abortedOr(abort, AiException.KIND_NETWORK), label(), "dns");
        } catch (IOException e) {
            // una connessione chiusa dall'annullamento arriva qui: non è un guasto di rete
            throw new AiException(abortedOr(abort, AiException.KIND_NETWORK), label(), String.valueOf(e.getMessage()));
        } catch (Throwable e) {
            throw new AiException(abortedOr(abort, AiException.KIND_BAD_REQUEST), label(), String.valueOf(e.getMessage()));
        } finally {
            if (conn != null) {
                if (abort != null) {
                    abort.attach(null);
                }
                try {
                    conn.disconnect();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    /** Un 400 che in realta' dice "chiave non valida": si riconosce dal corpo, perche' lo stato non lo dice. */
    private static boolean isInvalidKey(String text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        final String low = text.toLowerCase(java.util.Locale.ROOT);
        return low.contains("api_key_invalid") || low.contains("api key not valid") || low.contains("invalid api key");
    }

    private static int abortedOr(Abort abort, int kind) {
        return abort != null && abort.isAborted() ? AiException.KIND_CANCELLED : kind;
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream(8192);
            final byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_RESPONSE_BYTES) {
                    break;
                }
            }
            return new String(buf.toByteArray(), UTF8);
        } finally {
            close(in);
        }
    }

    private static void close(java.io.Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable ignore) {
        }
    }

    /** Dettaglio tecnico corto per i log: il corpo di errore di un fornitore può contenere la richiesta intera. */
    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        final String t = text.replace('\n', ' ').trim();
        return t.length() > 160 ? t.substring(0, 160) : t;
    }
}

/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

/**
 * Errore di un fornitore AI, classificato per decidere il ripiego.
 *
 * <p>La distinzione è la regola più importante di {@link AiClient}: si passa al fornitore di ripiego SOLO quando il
 * principale non ha potuto rispondere per ragioni di disponibilità ({@link #KIND_RATE_LIMIT} 429,
 * {@link #KIND_SERVER} 5xx, {@link #KIND_TIMEOUT}, {@link #KIND_NETWORK}). Un rifiuto di contenuto, una chiave non
 * valida o una richiesta malformata NON sono problemi di disponibilità: ripetere la stessa richiesta altrove
 * manderebbe lo stesso testo a un secondo servizio senza nessuna speranza di risposta diversa, e con una chiave
 * sbagliata nasconderebbe all'utente l'unico errore che deve vedere.</p>
 */
public final class AiException extends Exception {

    /** 429: quota o rate limit del fornitore. Ripiego SÌ. */
    public static final int KIND_RATE_LIMIT = 0;
    /** 5xx: guasto lato fornitore. Ripiego SÌ. */
    public static final int KIND_SERVER = 1;
    /** Timeout di connessione o di lettura. Ripiego SÌ. */
    public static final int KIND_TIMEOUT = 2;
    /** Rete assente o DNS/TLS fallito. Ripiego SÌ. */
    public static final int KIND_NETWORK = 3;
    /** 401/403: chiave assente, scaduta o senza permessi. Ripiego NO. */
    public static final int KIND_AUTH = 4;
    /** 400/404/413/422: richiesta malformata, modello inesistente, troppo lunga. Ripiego NO. */
    public static final int KIND_BAD_REQUEST = 5;
    /** Il modello ha rifiutato il contenuto (safety filter, blockReason, refusal). Ripiego NO. */
    public static final int KIND_CONTENT = 6;
    /** Risposta ricevuta ma vuota o illeggibile. Ripiego NO. */
    public static final int KIND_EMPTY = 7;
    /** Annullato dall'utente: non è un errore da mostrare. */
    public static final int KIND_CANCELLED = 8;
    /** Nessuna chiave salvata per il fornitore scelto. Ripiego NO (non è un guasto del fornitore). */
    public static final int KIND_NO_KEY = 9;
    /** Il modello non esiste piu' presso il fornitore: va cambiato nelle impostazioni. */
    public static final int KIND_MODEL = 10;

    /* I sei casi del modello SUL DISPOSITIVO. Nessuno di loro consente il ripiego automatico: vedi
     * {@link AiClient}, dove la regola e' che da locale a remoto non si passa senza un atto esplicito. */

    /** Il modello sul dispositivo non c'e': telefono non supportato, AICore assente o incompatibile. */
    public static final int KIND_LOCAL_MISSING = 11;
    /** Il modello va scaricato (o il download e' in corso): non e' un guasto, e' un passaggio da spiegare. */
    public static final int KIND_LOCAL_DOWNLOAD = 12;
    /** AICore ha rifiutato per carico: quota di inferenza per app. Si riprova, non si spedisce altrove. */
    public static final int KIND_LOCAL_BUSY = 13;
    /** Quota a lunga durata esaurita (batteria/giornaliera): esito normale del sistema, non un difetto. */
    public static final int KIND_LOCAL_QUOTA = 14;
    /** Spazio su disco insufficiente per il modello. */
    public static final int KIND_LOCAL_DISK = 15;
    /** Arretrato oltre il tetto dei blocchi: si dice quanto costerebbe invece di provarci per mezz'ora. */
    public static final int KIND_LOCAL_TOO_LONG = 16;

    private final int kind;
    private final String providerLabel;
    private final long retryDelayMs;

    public AiException(int kind, String providerLabel, String detail) {
        this(kind, providerLabel, detail, 0);
    }

    /**
     * @param retryDelayMs quanto conviene aspettare prima di riprovare, quando chi ha rifiutato lo dice (AICore lo
     *                     dice in {@code GenAiException.getRetryDelay()}); 0 = nessuna indicazione
     */
    public AiException(int kind, String providerLabel, String detail, long retryDelayMs) {
        super(detail);
        this.kind = kind;
        this.providerLabel = providerLabel;
        this.retryDelayMs = retryDelayMs;
    }

    public long retryDelayMs() {
        return retryDelayMs;
    }

    public int kind() {
        return kind;
    }

    /** Nome del fornitore che ha generato l'errore, per i messaggi all'utente. */
    public String providerLabel() {
        return providerLabel == null ? "" : providerLabel;
    }

    /**
     * Vero SOLO per 429, 5xx, timeout e rete: le uniche condizioni in cui provare il fornitore di ripiego ha senso.
     *
     * <p>Non basta piu' da sola: con il modello sul dispositivo come principale, un suo esito "di disponibilita'"
     * manderebbe il testo a Groq senza che nessuno l'abbia chiesto, cioe' esattamente cio' che l'anteprima deve
     * impedire. La seconda condizione sta in {@link AiClient}: il ripiego automatico vale solo se il principale e'
     * remoto. Dal locale si esce solo con un atto esplicito dell'utente.</p>
     */
    public boolean allowsFallback() {
        return kind == KIND_RATE_LIMIT || kind == KIND_SERVER || kind == KIND_TIMEOUT || kind == KIND_NETWORK;
    }

    /** Vero per gli esiti del modello sul dispositivo: la finestra di errore offre allora un fornitore remoto. */
    public boolean isLocalKind() {
        return kind == KIND_LOCAL_MISSING || kind == KIND_LOCAL_DOWNLOAD || kind == KIND_LOCAL_BUSY
                || kind == KIND_LOCAL_QUOTA || kind == KIND_LOCAL_DISK || kind == KIND_LOCAL_TOO_LONG;
    }

    /** Chiave di stringa del messaggio leggibile per questo errore. */
    public String messageKey() {
        switch (kind) {
            case KIND_RATE_LIMIT:
                return "MorsegramAiErrorRateLimit";
            case KIND_SERVER:
                return "MorsegramAiErrorServer";
            case KIND_TIMEOUT:
                return "MorsegramAiErrorTimeout";
            case KIND_NETWORK:
                return "MorsegramAiErrorNetwork";
            case KIND_MODEL:
                return "MorsegramAiErrorModel";
            case KIND_AUTH:
                return "MorsegramAiErrorAuth";
            case KIND_BAD_REQUEST:
                return "MorsegramAiErrorRequest";
            case KIND_CONTENT:
                return "MorsegramAiErrorContent";
            case KIND_EMPTY:
                return "MorsegramAiErrorEmpty";
            case KIND_NO_KEY:
                return "MorsegramAiErrorNoKey";
            case KIND_LOCAL_MISSING:
                return "MorsegramAiErrorLocalMissing";
            case KIND_LOCAL_DOWNLOAD:
                return "MorsegramAiErrorLocalDownload";
            case KIND_LOCAL_BUSY:
                return "MorsegramAiErrorLocalBusy";
            case KIND_LOCAL_QUOTA:
                return "MorsegramAiErrorLocalQuota";
            case KIND_LOCAL_DISK:
                return "MorsegramAiErrorLocalDisk";
            case KIND_LOCAL_TOO_LONG:
                return "MorsegramAiErrorLocalTooLong";
            case KIND_CANCELLED:
            default:
                return "MorsegramAiErrorCancelled";
        }
    }
}

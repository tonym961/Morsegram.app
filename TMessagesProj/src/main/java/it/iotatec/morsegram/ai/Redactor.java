/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Oscuramento degli identificatori strutturati prima di ogni invio a un fornitore AI.
 *
 * <p>Sostituisce con un segnaposto ciò che si riconosce dalla forma e che non serve a capire una conversazione:
 * numeri di telefono, indirizzi email, IBAN, numeri di carta (solo se passano la verifica di Luhn), codice fiscale
 * italiano, codici di verifica (riconosciuti dal contesto) e URL che portano un token. NON è un anonimizzatore: i
 * nomi, i luoghi e il contenuto dei messaggi escono dal dispositivo come sono, ed è esattamente questo che
 * l'anteprima di {@link AiGate} mostra all'utente prima della prima richiesta su una chat.</p>
 *
 * <p>L'ordine delle regole conta: gli URL per primi (un token in un URL contiene spesso qualcosa che somiglia a un
 * codice), le carte prima dei telefoni (un numero di carta a 16 cifre somiglia a un numero di telefono lungo), i
 * codici di verifica prima dei telefoni per la stessa ragione. I segnaposto non contengono cifre né {@code @}, così
 * nessuna regola successiva può colpire il risultato di una precedente.</p>
 *
 * <p>Classe di puro Java (nessun import Android, nessuno stato): è la parte di questo lavoro che si può provare
 * senza telefono e senza account.</p>
 */
public final class Redactor {

    public static final String PH_LINK = "[LINK]";
    public static final String PH_EMAIL = "[EMAIL]";
    public static final String PH_IBAN = "[IBAN]";
    public static final String PH_CARD = "[CARD]";
    public static final String PH_TAX_ID = "[TAX-ID]";
    public static final String PH_CODE = "[CODE]";
    public static final String PH_PHONE = "[PHONE]";

    /** Esito dell'oscuramento: testo da inviare e quante sostituzioni sono state fatte. */
    public static final class Result {
        public final String text;
        public final int count;

        Result(String text, int count) {
            this.text = text;
            this.count = count;
        }
    }

    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>\"']+");

    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+\\-]+@[A-Z0-9.\\-]+\\.[A-Z]{2,}\\b");

    /**
     * IBAN: 2 lettere di paese, 2 cifre di controllo, poi gruppi alfanumerici con spazi facoltativi.
     * {@code (?i)} come in {@link #TAX_ID}: la tastiera non maiuscolizza a meta' frase, quindi "it60 x054 ..."
     * e "Iban: It60 ..." sono la forma NORMALE, non il caso limite. Senza di esso l'IBAN non veniva oscurato e
     * {@link #PHONE_INTL} ne mangiava la coda (un BBAN che inizia per 00), lasciando paese e cifre in chiaro.
     */
    private static final Pattern IBAN = Pattern.compile("(?i)\\b[A-Z]{2}[0-9]{2}(?:[ ]?[A-Z0-9]{4}){2,7}(?:[ ]?[A-Z0-9]{1,3})?\\b");

    /**
     * Candidato carta: 13-19 cifre con separatori facoltativi. La decisione la prende Luhn.
     * Il PUNTO sta nella classe dei separatori come nelle regole del telefono: le classi incoerenti fra le regole
     * erano un buco, "4242.4242.4242.4242" non veniva oscurato mentre la stessa carta con spazi o trattini si'.
     */
    private static final Pattern CARD = Pattern.compile("\\b(?:[0-9][ .\\-]?){12,18}[0-9]\\b");

    /** Codice fiscale italiano: 6 lettere, 2 cifre, lettera del mese, 2 cifre, lettera, 3 cifre, lettera di controllo. */
    private static final Pattern TAX_ID = Pattern.compile("(?i)\\b[A-Z]{6}[0-9LMNPQRSTUV]{2}[ABCDEHLMPRST][0-9LMNPQRSTUV]{2}[A-Z][0-9LMNPQRSTUV]{3}[A-Z]\\b");

    /**
     * Codice di verifica: si riconosce dal contesto, non dalla forma (4-8 cifre sono anche un anno, un prezzo o un
     * numero civico). Solo il gruppo 1 viene sostituito, la parola che lo introduce resta.
     *
     * <p>Due misure dell'alternanza e della finestra, entrambe misurate su messaggi veri e non scelte a occhio:
     * {@code codic[ei]} perche' il plurale italiano ("i codici sono 123456 e 654321") non contiene ne' "codice"
     * ne' "code" con {@code \b}; e 48 caratteri, non 24, perche' la forma piu' comune degli SMS di servizio
     * italiani mette 29-44 caratteri fra la parola e le cifre ("Il codice per accedere al tuo conto e 739201",
     * "Codice di sicurezza per autorizzare il pagamento: 483920"). L'ancora resta la PAROLA introduttiva, non la
     * forma del numero, quindi allargare la finestra non tocca anni, prezzi e numeri civici.</p>
     */
    private static final Pattern CODE = Pattern.compile(
            "(?i)\\b(?:codic[ei]|codes?|otp|pin|verifica|verification|conferma|confirm|token|2fa|autenticazione|authentication|login|accesso|sblocco|unlock)\\b[^\\n]{0,48}?\\b(\\d{3}[ \\-]\\d{3}|\\d{4,8})\\b");

    /** Telefono internazionale (+39 ..., 0039 ...) oppure cellulare italiano a 10 cifre. */
    private static final Pattern PHONE_INTL = Pattern.compile("(?:\\+|\\b00)[0-9][0-9 .\\-()]{7,16}[0-9]\\b");
    private static final Pattern PHONE_IT = Pattern.compile("\\b3[0-9]{2}[ .\\-]?[0-9]{3}[ .\\-]?[0-9]{4}\\b");

    /**
     * Fisso italiano: prefisso 0 + 1-3 cifre, poi 5-8 cifre, con separatore facoltativo (anche la barra, che e'
     * la forma scritta a mano piu' comune: "06/1234567"). Per un'azienda il fisso e' il numero piu' scambiato in
     * chat, e {@link #PHONE_IT} copre solo i cellulari 3xx. Quante cifre in tutto lo decide
     * {@link #looksLikeLandline}: la regex da sola prenderebbe anche una partita IVA.
     */
    private static final Pattern PHONE_LAND_IT = Pattern.compile("\\b0[0-9]{1,3}[ ./\\-]?[0-9]{5,8}\\b");

    /**
     * Formato nazionale estero a tre gruppi ("415-555-0123", "415.555.0123"): i separatori sono OBBLIGATORI, cosi'
     * la regola non tocca una sequenza nuda di dieci cifre (che sarebbe un codice d'ordine quanto un numero).
     */
    private static final Pattern PHONE_GROUPS = Pattern.compile("\\b[0-9]{3}[ .\\-][0-9]{3}[ .\\-][0-9]{4}\\b");

    /** Nomi di parametro che rendono un URL un segreto. */
    private static final String[] TOKEN_PARAMS = {
            "token", "access_token", "id_token", "refresh_token", "api_key", "apikey", "key", "secret",
            "password", "passwd", "pwd", "auth", "authorization", "session", "sessionid", "sid", "sig",
            "signature", "code", "otp", "hash", "tg_hash", "credential", "invite", "passcode"
    };

    private Redactor() {
    }

    /** Il testo da inviare, con i segnaposto al posto degli identificatori. */
    public static String redact(String input) {
        return apply(input).text;
    }

    /** Come {@link #redact}, più il numero di sostituzioni (serve a dirlo nell'anteprima). */
    public static Result apply(String input) {
        if (input == null || input.length() == 0) {
            return new Result(input == null ? "" : input, 0);
        }
        final int[] count = new int[1];
        String s = input;
        s = replaceUrls(s, count);
        s = replaceAll(s, EMAIL, PH_EMAIL, count);
        s = replaceIf(s, IBAN, PH_IBAN, count, 0, Redactor::looksLikeIban);
        s = replaceIf(s, CARD, PH_CARD, count, 0, Redactor::luhn);
        s = replaceIf(s, TAX_ID, PH_TAX_ID, count, 0, Redactor::looksLikeTaxId);
        // CODE a PUNTO FISSO, non a passo singolo: il match consuma la parola introduttiva, quindi un secondo
        // numero introdotto dalla stessa parola ("codice 123456 e 654321") resta senza ancora e si vedrebbe solo
        // alla passata dopo. Senza il ciclo apply() non e' idempotente e il secondo codice uscirebbe in chiaro.
        for (int pass = 0; pass < 8; pass++) {
            final int before = count[0];
            s = replaceIf(s, CODE, PH_CODE, count, 1, null);
            if (count[0] == before) {
                break;
            }
        }
        s = replaceAll(s, PHONE_INTL, PH_PHONE, count);
        s = replaceAll(s, PHONE_IT, PH_PHONE, count);
        s = replaceIf(s, PHONE_LAND_IT, PH_PHONE, count, 0, Redactor::looksLikeLandline);
        s = replaceAll(s, PHONE_GROUPS, PH_PHONE, count);
        return new Result(s, count[0]);
    }

    /* ======================================================================================= regole */

    private static String replaceAll(String s, Pattern p, String placeholder, int[] count) {
        return replaceIf(s, p, placeholder, count, 0, null);
    }

    /**
     * Sostituisce il gruppo {@code group} di ogni occorrenza (0 = tutta l'occorrenza) quando {@code accept} lo
     * approva. Costruisce il risultato a mano invece di usare appendReplacement: il segnaposto non deve passare per
     * l'interpretazione di {@code $} e {@code \} delle sostituzioni di regex.
     */
    private static String replaceIf(String s, Pattern p, String placeholder, int[] count, int group, Check accept) {
        final Matcher m = p.matcher(s);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            final int start = m.start(group);
            final int end = m.end(group);
            if (start < 0 || end <= start) {
                continue;
            }
            if (accept != null && !accept.ok(s.substring(start, end))) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(s.length() + 16);
            }
            out.append(s, last, start).append(placeholder);
            last = end;
            count[0]++;
        }
        if (out == null) {
            return s;
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    /**
     * URL: si sostituisce l'intero indirizzo solo se porta un token (parametro sensibile, segmento opaco lungo,
     * link di invito Telegram). Un link pubblico resta: è contesto utile e non è un identificatore.
     */
    private static String replaceUrls(String s, int[] count) {
        final Matcher m = URL.matcher(s);
        StringBuilder out = null;
        int last = 0;
        while (m.find()) {
            int end = m.end();
            // la punteggiatura finale di una frase non fa parte dell'URL
            while (end > m.start() && ".,;:!?)]}>".indexOf(s.charAt(end - 1)) >= 0) {
                end--;
            }
            final String url = s.substring(m.start(), end);
            if (!urlHasToken(url)) {
                continue;
            }
            if (out == null) {
                out = new StringBuilder(s.length() + 16);
            }
            out.append(s, last, m.start()).append(PH_LINK);
            last = end;
            count[0]++;
        }
        if (out == null) {
            return s;
        }
        out.append(s, last, s.length());
        return out.toString();
    }

    static boolean urlHasToken(String url) {
        final String lower = url.toLowerCase(java.util.Locale.ROOT);
        // link di invito e di accesso: il segreto è il link stesso
        if (lower.contains("t.me/+") || lower.contains("t.me/joinchat/") || lower.contains("/invite/")) {
            return true;
        }
        final int q = indexOfAny(lower, "?#");
        if (q >= 0) {
            final String query = lower.substring(q + 1);
            for (String param : query.split("[&;]")) {
                final int eq = param.indexOf('=');
                final String name = eq >= 0 ? param.substring(0, eq) : param;
                final String trimmed = name.trim();
                for (String sensitive : TOKEN_PARAMS) {
                    if (trimmed.equals(sensitive)) {
                        return true;
                    }
                }
            }
        }
        // segmento opaco lungo: /reset/9f3b2c81d4e5a67089ab, #access_token=..., ?t=eyJhbGciOi...
        for (String segment : url.split("[/?#&=;]")) {
            if (looksOpaque(segment)) {
                return true;
            }
        }
        return false;
    }

    private static int indexOfAny(String s, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            final int at = s.indexOf(chars.charAt(i));
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }

    /** Segmento lungo, senza vocali-e-consonanti da parola, con cifre e lettere: una chiave, non un titolo. */
    private static boolean looksOpaque(String segment) {
        if (segment.length() < 20) {
            return false;
        }
        boolean digit = false;
        boolean letter = false;
        for (int i = 0; i < segment.length(); i++) {
            final char c = segment.charAt(i);
            if (c >= '0' && c <= '9') {
                digit = true;
            } else if (c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z') {
                letter = true;
            } else if (c != '-' && c != '_' && c != '.' && c != '~' && c != '%') {
                return false;
            }
        }
        return digit && letter;
    }

    /**
     * Codice fiscale: la forma la garantisce la regex, qui si chiede almeno una cifra. Senza questo controllo una
     * parola italiana di 16 lettere potrebbe soddisfare la forma (l'omocodia ammette lettere al posto delle cifre)
     * e una parola qualunque finirebbe oscurata nel mezzo di una frase.
     */
    static boolean looksLikeTaxId(String candidate) {
        for (int i = 0; i < candidate.length(); i++) {
            if (Character.isDigit(candidate.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * IBAN: lunghezza complessiva 15-34 caratteri alfanumerici. Il resto della forma lo garantisce la regex; non si
     * verifica il modulo 97 perché un IBAN scritto a mano con una cifra sbagliata va oscurato comunque.
     */
    static boolean looksLikeIban(String candidate) {
        final String compact = candidate.replace(" ", "");
        if (compact.length() < 15 || compact.length() > 34) {
            return false;
        }
        // un IBAN ha almeno una cifra oltre alle due di controllo: esclude sigle tutte-lettere
        int digits = 0;
        for (int i = 4; i < compact.length(); i++) {
            if (Character.isDigit(compact.charAt(i))) {
                digits++;
            }
        }
        return digits > 0;
    }

    /**
     * Fisso italiano: 9 o 10 cifre IN TUTTO, prefisso compreso. Il tetto non e' estetico: una partita IVA
     * italiana e' di 11 cifre e soddisfa la forma della regex ("P.IVA 01234567890"), quindi senza questo
     * controllo finirebbe oscurata come se fosse un numero di telefono.
     */
    static boolean looksLikeLandline(String candidate) {
        int digits = 0;
        for (int i = 0; i < candidate.length(); i++) {
            if (Character.isDigit(candidate.charAt(i))) {
                digits++;
            }
        }
        return digits >= 9 && digits <= 10;
    }

    /** Verifica di Luhn sulle sole cifre: separa un numero di carta da una sequenza di cifre qualunque. */
    static boolean luhn(String candidate) {
        final StringBuilder digits = new StringBuilder(24);
        for (int i = 0; i < candidate.length(); i++) {
            final char c = candidate.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        final int n = digits.length();
        if (n < 13 || n > 19) {
            return false;
        }
        int sum = 0;
        boolean dbl = false;
        for (int i = n - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    private interface Check {
        boolean ok(String candidate);
    }
}

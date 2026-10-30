/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.backup;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Codec del salvataggio delle impostazioni di Morsegram: scrive un file, lo rilegge, dice cosa cambierebbe e
 * applica. <b>Zero import Android</b>, e non e' un vezzo: la serializzazione e' il pezzo dove si sbaglia, e
 * sull'emulatore non si puo' accedere a un account, quindi questa classe deve restare provabile sulla JVM
 * (vedi il banco di prova nel report della missione). L'anello Android sta in {@link BackupStore}.
 *
 * <p><b>Formato.</b> Righe di testo, un campo per riga, campi separati da UNO spazio:
 * <pre>
 * morsegram-impostazioni 1
 * app 0.1.13 sideload 1761800000000 2026-10-30T07:33:20Z
 * g b fold.twoPanes true
 * g i ai.primary 0
 * g s update.source me
 * a 123456789 s pins.order -1001,-1002
 * w 5 0 -1001 0 0 100
 * t giorno Morsegram
 * </pre>
 * La prima riga e' la riga magica con la <b>versione del formato</b>; la seconda dice versione dell'app,
 * edizione e <b>data</b> (millisecondi piu' la stessa data in chiaro, per chi legge il file a occhio). Un
 * valore vuoto, dove e' un valore legittimo, si scrive {@link #VUOTO}: cosi' la riga non perde un campo.
 *
 * <p><b>Perche' non JSON.</b> E' una scelta di sicurezza, non di gusto, e viene dallo studio di fattibilita':
 * il lettore apre un file che puo' venire da fonte ignota (la lezione di NekoX, che ha dovuto avvisare che un
 * backup importato puo' sovrascrivere il passcode e chiudere l'utente fuori dall'app). Un parser a split su
 * spazio, senza ricorsione e senza macchina a stati, <b>salta</b> la riga che non capisce invece di sbagliarla,
 * e non ha nessuno dei modi di rompersi di un parser annidato. In piu' il file resta leggibile a occhio, quindi
 * l'utente verifica da se' che dentro non c'e' nessun PIN.
 *
 * <p><b>Elenco consentito nei DUE sensi.</b> Lo scrittore enumera le chiavi da esportare (mai {@code getAll()}:
 * cosi' una chiave segreta aggiunta domani non esce per inerzia) e il lettore rifiuta tutto quello che non e' nel
 * suo elenco, con il tipo dichiarato e il valore validato. Quello che non esce <b>mai</b>, per nessuna strada:
 * il PIN delle chat nascoste, l'elenco delle chat nascoste (nemmeno dentro un CSV di altro), le chiavi dei
 * fornitori dell'assistente, le accettazioni dell'anteprima, lo stato della modalita' aereo, le sonde di questo
 * telefono, il seme dei temi, la cache di contenuto dei widget. Vedi {@link #vietata(String)}.
 *
 * <p><b>Niente ripristini a meta'.</b> Si legge, si valida, si costruisce un {@link Piano} che dice voce per
 * voce cosa cambierebbe, e solo dopo si applica. Un file troncato, manomesso, di un'altra app o di una versione
 * futura non arriva mai alla scrittura.
 */
public final class MorsegramBackup {

    /** Prima parola della prima riga: se manca, il file non e' nostro. */
    public static final String MAGIC = "morsegram-impostazioni";
    /** Versione del formato scritta nella riga magica. Un file con un numero piu' alto NON si indovina. */
    public static final int FORMATO = 1;
    public static final String NOME_FILE = "morsegram-impostazioni.txt";
    public static final String MIME = "text/plain";
    /** Un file di impostazioni sta in pochi KB: oltre questo tetto non si legge nemmeno. */
    public static final int MAX_BYTES = 256 * 1024;
    /**
     * Tetto per singolo valore di testo libero (nomi di modello, nome utente): oltre, la riga si scarta. Il nome
     * di tema ha un tetto suo, piu' stretto, perche' e' l'unico testo libero con gli spazi: vedi
     * {@link #MAX_NOME_TEMA}.
     */
    public static final int MAX_VALORE = 256;
    /**
     * Tetto alla versione dell'app letta dal file. Non e' un dato nostro: viene dal file, finisce nel dialogo
     * dell'anteprima (riga {@code MorsegramBackupFrom}) e una versione non e' mai piu' lunga di cosi'.
     */
    public static final int MAX_VERSIONE = 32;
    /**
     * Tetto al nome di chiave che si MOSTRA nell'elenco delle righe buttate via. Anche questo e' testo scelto
     * da chi ha scritto il file, e compare nel dialogo accanto ai testi dell'app: vedi {@link #nomePlausibile}.
     */
    public static final int MAX_NOME_CHIAVE = 48;
    /**
     * Tetto al nome di TEMA, che e' l'altro testo scelto da chi ha scritto il file e che l'anteprima stampa
     * dentro le sue frasi ({@code MorsegramBackupTheme}, {@code MorsegramBackupThemeMissing}). Al contrario di
     * un nome di chiave puo' avere spazi, quindi {@link #nomePlausibile} non lo copre: vedi
     * {@link #nomeTemaPlausibile}.
     */
    public static final int MAX_NOME_TEMA = MAX_NOME_CHIAVE;
    /**
     * Quante parole al massimo in un nome di tema. I nomi veri sono di una o due parole — quelli di Telegram
     * ({@code Blue}, {@code Dark Blue}, {@code Night}), quelli generati quando si crea un tema
     * ({@code Blazing Berry}: aggettivo piu' colore) e la chiave di un tema scaricato ({@code remote1234},
     * una parola sola) — e tre e' il margine. Il tetto non e' estetica: e' quello che impedisce a una riga
     * {@code t giorno ...} di diventare una FRASE dentro le frasi dell'anteprima.
     */
    public static final int MAX_PAROLE_TEMA = 3;
    /**
     * Segnaposto del valore VUOTO nel file. In un formato a campi separati da spazio un campo vuoto non e'
     * rappresentabile (cambierebbe il numero di campi della riga), ma la stringa vuota e' un valore legittimo
     * per {@code voice.lang}: e' "lingua dell'app", il primo dei tag proposti da {@code VoiceSettings}. Si
     * scrive questo e si rilegge {@code ""}, solo per le voci la cui validazione ammette il vuoto
     * ({@link Voce#ammetteVuoto()}), quindi non puo' essere confuso con il valore vero di nessun'altra voce.
     */
    public static final String VUOTO = "-";
    /**
     * Quante voci al massimo in un CSV di dialog id. Lo applicano ENTRAMBI i lati: il lettore in
     * {@link #idCsvValido}, lo scrittore in {@link #filtraIds}, cosi' il file che scriviamo e' sempre un file
     * che rileggiamo.
     */
    public static final int MAX_VOCI_CSV = 512;
    /**
     * Tetto per un CSV di dialog id, in caratteri: {@link #MAX_VOCI_CSV} voci larghe quanto il peggior caso
     * ({@code -9223372036854775808:2} piu' la virgola), cioe' circa 12 KB, che stanno comodi dentro
     * {@link #MAX_BYTES}. Non e' un dettaglio di stile: {@code pins.order} esiste proprio per superare il
     * limite dei pin di Telegram, quindi oltre la ventina di chat e' il caso NORMALE di chi usa la funzione, e
     * con un tetto di 256 caratteri l'esportazione perdeva le chat dalla diciottesima in avanti senza dirlo.
     */
    public static final int MAX_CSV = MAX_VOCI_CSV * 24;
    /** Tetto ai modelli di widget in un file: un launcher non ne ha cento, e un file ostile non ci prova. */
    public static final int MAX_WIDGET = 64;

    /* tipi dichiarati nella riga, un carattere */
    public static final char T_BOOL = 'b';
    public static final char T_INT = 'i';
    public static final char T_LONG = 'l';
    public static final char T_STR = 's';

    /* ambiti (primo campo della riga) */
    public static final char A_GLOBALE = 'g';
    public static final char A_ACCOUNT = 'a';
    public static final char A_WIDGET = 'w';
    public static final char A_TEMA = 't';

    /* esiti della lettura */
    public static final int OK = 0;
    public static final int ERR_VUOTO = 1;
    public static final int ERR_NON_NOSTRO = 2;
    public static final int ERR_FORMATO_FUTURO = 3;
    public static final int ERR_TROPPO_GRANDE = 4;
    public static final int ERR_NIENTE_DA_FARE = 5;

    private MorsegramBackup() {
    }

    /* ==================================================================== elenco delle voci consentite */

    /**
     * Come si valida il testo di una voce {@link #T_STR}. Il primo modo e' il piu' largo: <b>testo libero</b>,
     * cioe' una parola sola di caratteri visibili — niente spazi, nemmeno quelli invisibili (vedi
     * {@link #visibile}). E' il modo dei nomi di modello dell'assistente, che nel file non hanno spazi e
     * nell'anteprima si stampano nudi.
     */
    public static final int TESTO_LIBERO = 0;
    /** CSV di dialog id: ogni id va filtrato dalle chat nascoste in scrittura e validato in lettura. */
    public static final int TESTO_ID_CSV = 1;
    /** CSV di coppie {@code <dialogId>:<livello>}. Stesso filtro, piu' il livello 0..2. */
    public static final int TESTO_ID_LIVELLO_CSV = 2;
    /** Uno fra i valori ammessi dichiarati nella voce (es. i tag di lingua proposti). */
    public static final int TESTO_SCELTA = 3;
    /** Nome utente di una chat sorgente: lettere, cifre, underscore, punto. */
    public static final int TESTO_NOME_UTENTE = 4;

    /** Una preferenza che si puo' portare via: chiave, tipo, e come si valida il valore. */
    public static final class Voce {
        public final String chiave;
        public final char tipo;
        /** solo {@link #T_INT}: intervallo ammesso, estremi inclusi. */
        public final int min;
        public final int max;
        /** solo {@link #T_STR}: quale validazione. */
        public final int testo;
        /** solo {@link #TESTO_SCELTA}: i valori ammessi. */
        public final String[] ammessi;
        /** vero se la stringa vuota e' fra i valori ammessi: vedi {@link #VUOTO}. */
        private final boolean vuotoAmmesso;

        private Voce(String chiave, char tipo, int min, int max, int testo, String[] ammessi) {
            this.chiave = chiave;
            this.tipo = tipo;
            this.min = min;
            this.max = max;
            this.testo = testo;
            this.ammessi = ammessi;
            boolean vuoto = false;
            if (testo == TESTO_SCELTA && ammessi != null) {
                for (String a : ammessi) {
                    if (a != null && a.isEmpty()) {
                        vuoto = true;
                        break;
                    }
                }
            }
            this.vuotoAmmesso = vuoto;
        }

        public static Voce bool(String chiave) {
            return new Voce(chiave, T_BOOL, 0, 0, TESTO_LIBERO, null);
        }

        public static Voce intero(String chiave, int min, int max) {
            return new Voce(chiave, T_INT, min, max, TESTO_LIBERO, null);
        }

        public static Voce testo(String chiave, int validazione) {
            return new Voce(chiave, T_STR, 0, 0, validazione, null);
        }

        public static Voce scelta(String chiave, String[] ammessi) {
            return new Voce(chiave, T_STR, 0, 0, TESTO_SCELTA, ammessi);
        }

        /**
         * Quanti caratteri al massimo per il valore di QUESTA voce: un CSV di dialog id ha bisogno di molto
         * piu' spazio di un nome di modello, e il tetto deve essere lo stesso in scrittura e in lettura.
         */
        public int tetto() {
            return testo == TESTO_ID_CSV || testo == TESTO_ID_LIVELLO_CSV ? MAX_CSV : MAX_VALORE;
        }

        /**
         * Vero se per questa voce la stringa vuota e' un valore come gli altri ({@code voice.lang}: "lingua
         * dell'app"). Nel file quel valore si scrive {@link #VUOTO}, cosi' la riga non perde un campo.
         */
        public boolean ammetteVuoto() {
            return vuotoAmmesso;
        }

        /** Il valore logico di un campo letto dal file: il segnaposto torna stringa vuota. */
        public String dalFile(String campo) {
            return vuotoAmmesso && VUOTO.equals(campo) ? "" : campo;
        }

        /** Il campo da scrivere nel file per un valore logico: la stringa vuota diventa il segnaposto. */
        public String alFile(String valore) {
            return vuotoAmmesso && valore != null && valore.isEmpty() ? VUOTO : valore;
        }

        /** Vero se il valore in forma di testo e' accettabile per questa voce. */
        public boolean valida(String valore) {
            if (valore == null || valore.length() > tetto()) {
                return false;
            }
            if (valore.isEmpty() && !vuotoAmmesso) {
                return false;
            }
            switch (tipo) {
                case T_BOOL:
                    return "true".equals(valore) || "false".equals(valore);
                case T_INT: {
                    final long v = numero(valore, Long.MIN_VALUE);
                    return v != Long.MIN_VALUE && v >= min && v <= max;
                }
                case T_LONG:
                    return numero(valore, Long.MIN_VALUE) != Long.MIN_VALUE;
                default:
                    return validaTesto(valore);
            }
        }

        private boolean validaTesto(String valore) {
            switch (testo) {
                case TESTO_SCELTA: {
                    if (ammessi == null) {
                        return false;
                    }
                    for (String a : ammessi) {
                        if (a != null && a.equals(valore)) {
                            return true;
                        }
                    }
                    return false;
                }
                case TESTO_ID_CSV:
                    return idCsvValido(valore, false);
                case TESTO_ID_LIVELLO_CSV:
                    return idCsvValido(valore, true);
                case TESTO_NOME_UTENTE: {
                    // niente chiocciola: Updater.setSource la toglie SEMPRE prima di salvare, quindi un nome
                    // con la chiocciola non e' un valore che il percorso normale dell'app possa produrre.
                    // Accettarlo significherebbe scrivere "@canale" sotto al setter, e poi resolveSource lo
                    // passerebbe cosi' com'e' al server, che risponde con un errore di nome utente: gli
                    // aggiornamenti in-app resterebbero rotti finche' l'utente non riscrive la fonte a mano
                    if (valore.length() > 64) {
                        return false;
                    }
                    for (int i = 0; i < valore.length(); i++) {
                        final char c = valore.charAt(i);
                        final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                                || (c >= '0' && c <= '9') || c == '_' || c == '.';
                        if (!ok) {
                            return false;
                        }
                    }
                    return true;
                }
                default: {
                    // testo libero: un campo solo, e nient'altro che caratteri VISIBILI. Il valore finisce
                    // nudo nell'anteprima (BackupSettingsFragment.valore, ramo default), che e' la schermata
                    // su cui l'utente decide: con il solo controllo dei caratteri di controllo la riga
                    // "g s ai.model.0 gpt-4 - ATTENZIONE: il file non contiene dati personali." passava
                    // intera e diventava "Modello di Groq: gpt-4 - ATTENZIONE: ...", cioe' fino a 256
                    // caratteri scelti da chi ha scritto il file, che affermano il falso sulla riservatezza
                    // accanto ai testi dell'app. Un nome di modello non ha spazi (gpt-4o-mini,
                    // llama-3.3-70b-versatile, llama3.1:8b), quindi qui lo spazio non e' un valore da
                    // salvare; e U+00A0 e compagni sarebbero perfino invisibili, oltre a far saltare il giro
                    // scrivi-leggi-scrivi, perche' ripulisci() li tratta da separatore
                    for (int i = 0; i < valore.length(); i++) {
                        if (!visibile(valore.charAt(i))) {
                            return false;
                        }
                    }
                    return true;
                }
            }
        }
    }

    /**
     * CSV di dialog id (o di coppie {@code id:livello}): almeno una voce, tutte numeriche, livello 0..2, e un
     * tetto al numero di voci. Non e' una formalita': questi valori finiscono dentro i parser di
     * {@code LocalPins}, {@code AiGate} e {@code MorsegramAlerts}, che gia' scartano le voci rotte ma non
     * devono vedersi arrivare un CSV lungo un file.
     */
    private static boolean idCsvValido(String valore, boolean conLivello) {
        // split con limite NEGATIVO: senza il -1 i campi vuoti finali cadono, quindi "-1001,,,,,,,,," passava
        // la validazione come un id solo e il valore entrava nel piano con le virgole attaccate. L'anteprima
        // conta le virgole (BackupSettingsFragment.quante) e dichiarava 12001 chat dove la chat era una, nella
        // schermata che deve essere attendibile. Con il -1 i campi vuoti arrivano al controllo s.isEmpty()
        final String[] pezzi = valore.split(",", -1);
        if (pezzi.length == 0 || pezzi.length > MAX_VOCI_CSV) {
            return false;
        }
        for (String p : pezzi) {
            final String s = p.trim();
            if (s.isEmpty()) {
                return false;
            }
            final int sep = s.indexOf(':');
            if (conLivello != (sep > 0)) {
                return false;
            }
            if (numero(sep > 0 ? s.substring(0, sep) : s, 0) == 0) {
                return false;
            }
            if (conLivello) {
                final long livello = numero(s.substring(sep + 1), -1);
                if (livello < 0 || livello > 2) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * L'elenco consentito, uguale in scrittura e in lettura. Lo costruisce l'anello Android passando quello che
     * solo lui sa: se siamo nell'edizione sideload, le chiavi degli sblocchi (che <b>non</b> vanno scritte qui
     * come letterali: vivono in {@code LocalUnlocks}, che nell'edizione per Play e' codice morto, cosi' R8 le
     * cancella dal dex invece di lasciarle nell'APK dello store) e i tag di lingua proposti dalla trascrizione.
     */
    public static final class Elenco {
        public final List<Voce> globali;
        public final List<Voce> account;
        private final Map<String, Voce> indice = new LinkedHashMap<>();

        public Elenco(List<Voce> globali, List<Voce> account) {
            this.globali = globali == null ? Collections.<Voce>emptyList() : globali;
            this.account = account == null ? Collections.<Voce>emptyList() : account;
            for (Voce v : this.globali) {
                if (!vietata(v.chiave)) {
                    indice.put(A_GLOBALE + "/" + v.chiave, v);
                }
            }
            for (Voce v : this.account) {
                if (!vietata(v.chiave)) {
                    indice.put(A_ACCOUNT + "/" + v.chiave, v);
                }
            }
        }

        /** La voce consentita per quell'ambito e quella chiave, o null: e' il filtro del LETTORE. */
        public Voce voce(char ambito, String chiave) {
            return chiave == null ? null : indice.get(ambito + "/" + chiave);
        }
    }

    /**
     * Elenco delle preferenze globali di Morsegram che si portano via.
     *
     * @param modelloLocale vero se questa edizione contiene il fornitore dell'assistente SUL DISPOSITIVO
     *                      ({@code MorsegramGates.LOCAL_AI}): decide l'intervallo delle voci dei fornitori,
     *                      perche' l'anteprima non puo' nominare un destinatario che questa edizione non ha
     */
    public static List<Voce> vociGlobali(boolean sideload, boolean modelloLocale,
                                         List<String> chiaviSblocchi, String[] lingueAmmesse) {
        final List<Voce> v = new ArrayList<>(32);
        /* pieghevole: su uno schermo singolo la UI li ignora, non danneggiano nulla */
        v.add(Voce.bool("fold.twoPanes"));
        v.add(Voce.bool("fold.hingeSplit"));
        /* avvisi per categoria: livello generale (MODE_DEFAULT/ALWAYS/CHAT) */
        v.add(Voce.intero("alerts.mentions", 0, 2));
        /* trascrizione vocali */
        v.add(Voce.intero("voice.mode", 0, 2));
        v.add(lingueAmmesse != null && lingueAmmesse.length > 0
                ? Voce.scelta("voice.lang", lingueAmmesse)
                : Voce.testo("voice.lang", TESTO_LIBERO));
        v.add(Voce.bool("voice.allowSystem"));
        v.add(Voce.bool("voice.lockMode"));
        /* modalita' mancini: la pillola di invio a sinistra. Sta accanto al blocco della modalita' di
           registrazione perche' e' l'altra meta' della stessa risposta ("l'app con il pollice sinistro"): quel
           blocco tiene il vocale a portata di pollice, questa sposta l'invio */
        v.add(Voce.bool("hand.sendLeft"));
        /* assistente, SENZA le chiavi: dopo il ripristino resta solo da incollare quella del fornitore scelto */
        v.add(Voce.bool("ai.enabled"));
        // l'intervallo dei fornitori dipende dall'EDIZIONE, non solo dal sideload: senza il modello sul
        // dispositivo AiProvider.labelOf(2) torna la stringa vuota, quindi l'anteprima stampava "Nessuno" per
        // una riga che AiSettings.normalize risolve in Groq, cioe' un fornitore DI RETE, e dichiarare un
        // destinatario piu' debole di quello che il ripristino scrive e' l'unica cosa che l'anteprima non puo'
        // fare. Fuori dall'intervallo la riga finisce fra le "righe che questa versione non riconosce", dove e'
        // vera, e il valore resta uno che questa edizione sa servire. Per lo stesso motivo ai.model.2 (il
        // modello del fornitore locale) si dichiara solo dove quel fornitore esiste: altrimenti l'etichetta
        // "Modello di %1$s" dell'anteprima resterebbe senza soggetto
        final int ultimoFornitore = modelloLocale ? 2 : 1;
        v.add(Voce.intero("ai.primary", 0, ultimoFornitore));
        v.add(Voce.intero("ai.fallback", -1, ultimoFornitore));
        v.add(Voce.testo("ai.model.0", TESTO_LIBERO));
        v.add(Voce.testo("ai.model.1", TESTO_LIBERO));
        if (modelloLocale) {
            v.add(Voce.testo("ai.model.2", TESTO_LIBERO));
        }
        /* aggiornamento in-app: nell'edizione Play l'Updater non esiste e questi valori restano inerti */
        v.add(Voce.testo("update.source", TESTO_NOME_UTENTE));
        v.add(Voce.bool("update.auto"));
        if (sideload && chiaviSblocchi != null) {
            for (String k : chiaviSblocchi) {
                if (k != null && k.startsWith("unlock.")) {
                    v.add(Voce.bool(k));
                }
            }
        }
        return v;
    }

    /**
     * Elenco delle preferenze per account. Mai {@code hidden.ids} (un backup non deve creare conoscenza che il
     * cloud non ha: il server sa che quelle chat esistono, non sa che le nascondi) e mai {@code ai.previewed}
     * (il consenso all'anteprima si richiede su QUESTA installazione, non si eredita da un file).
     */
    public static List<Voce> vociAccount() {
        final List<Voce> v = new ArrayList<>(4);
        v.add(Voce.testo("ai.chats", TESTO_ID_CSV));
        v.add(Voce.testo("alerts.chats", TESTO_ID_LIVELLO_CSV));
        v.add(Voce.testo("pins.order", TESTO_ID_CSV));
        return v;
    }

    /**
     * Vietato per costruzione: nessuna riga del file puo' nominare questi spazi di chiavi, in nessun ambito, ne'
     * in scrittura ne' in lettura. Non e' prudenza generica, e' il cuore del disegno; il motivo voce per voce:
     * <ul>
     *   <li>{@code hidden.*} — il PIN difende da chi ha il telefono in mano, e quel qualcuno ha anche Telegram
     *       aperto: scriverlo in un file e' mettere la chiave accanto alla serratura. E un ripristino che
     *       SCRIVE un PIN e' un modo di chiudere l'utente fuori dalla sua app. Qui non si evita solo di
     *       scriverlo: si RIFIUTA la riga che lo nomina.</li>
     *   <li>{@code hidden.ids} — l'elenco delle chat nascoste vive solo su questo dispositivo, e un file che lo
     *       dice vanifica la funzione.</li>
     *   <li>{@code ai.previewed} — il consenso nomina il destinatario: si richiede, non si eredita.</li>
     *   <li>{@code ai.local.declined}, {@code voice.probe} — decisioni e sonde di QUESTO telefono.</li>
     *   <li>{@code theme.applied} — non e' una preferenza, e' il numero di versione del seme dei temi:
     *       ripristinarlo farebbe uscire {@code seedIfFirstRun} alla prima riga e l'utente resterebbe sul tema
     *       di Telegram con un backup che dice "fatto".</li>
     *   <li>{@code airplane.*} — stato, non impostazione: ripristinare "aereo acceso" taglierebbe la
     *       connettivita' senza che nessuno lo abbia chiesto.</li>
     *   <li>{@code update.lastCheck}, {@code perf.log} — un timestamp e un interruttore di debug.</li>
     *   <li>{@code key.*} — le chiavi dei fornitori dell'assistente, che oggi stanno cifrate con una AES-256 del
     *       Keystore che non puo' uscire dal dispositivo: un backup le degraderebbe da "non possono uscire dal
     *       telefono" a "stanno in un file", e sono l'unica voce che e' insieme credenziale e spesa a consumo.</li>
     * </ul>
     * Tutto cio' che non e' nell'{@link Elenco} e' comunque escluso: questo metodo e' la seconda rete, quella
     * che vale anche se un giorno qualcuno aggiunge una voce per distrazione.
     */
    public static boolean vietata(String chiave) {
        return chiave == null
                || chiave.isEmpty()
                || chiave.startsWith("hidden.")
                || chiave.startsWith("airplane.")
                || chiave.startsWith("key.")
                || chiave.equals("theme.applied")
                || chiave.equals("voice.probe")
                || chiave.equals("ai.previewed")
                || chiave.equals("ai.local.declined")
                || chiave.equals("update.lastCheck")
                || chiave.equals("perf.log");
    }

    /**
     * Vero se questa stringa somiglia a un nome di chiave ({@code hidden.pin.hash}, {@code key.groq},
     * {@code passcodeHash}). Serve a una cosa sola, ed e' il motivo per cui e' stretta: i nomi delle righe
     * buttate via si MOSTRANO nel dialogo dell'anteprima, cioe' l'unica schermata che deve essere attendibile,
     * e nel file il nome e' solo "il campo fra due spazi" di un documento che puo' venire da chiunque. Senza
     * questo filtro nell'elenco entrerebbe una chiave lunga quanto il file, o una frase con spazi insecabili
     * (U+00A0, che passa il separatore) scritta per somigliare a un avviso dell'app sopra il pulsante
     * Ripristina. La riga resta contata: qui si decide solo se il suo NOME si puo' stampare.
     */
    public static boolean nomePlausibile(String chiave) {
        if (chiave == null || chiave.isEmpty() || chiave.length() > MAX_NOME_CHIAVE) {
            return false;
        }
        for (int i = 0; i < chiave.length(); i++) {
            final char c = chiave.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Vero se questa stringa somiglia a un nome di TEMA ({@code Blue}, {@code Dark Blue}, {@code remote1234}).
     * E' il gemello di {@link #nomePlausibile} per il secondo testo che il file sceglie e l'anteprima stampa, e
     * serve per lo stesso motivo: il nome arriva interpolato dentro le frasi dell'app
     * ({@code "Tema diurno: %1$s"}, {@code "Il tema %1$s del file non c e su questo telefono: resta quello di
     * adesso."}), cioe' nell'unica schermata che deve essere attendibile, e sopra il pulsante Ripristina. Con il
     * solo tetto di {@link #MAX_VALORE} passavano 256 caratteri per riga e due righe (giorno e notte), abbastanza
     * per far comparire nel dialogo una frase che sembra dell'app.
     *
     * <p>Un nome di tema vero e' corto: {@link #MAX_NOME_TEMA} caratteri e non piu' di
     * {@link #MAX_PAROLE_TEMA} parole separate da UN solo spazio. Qui si rifiuta e la riga si scarta, non si
     * taglia: tagliare trasformerebbe la frase lunga in una frase corta <b>accettata</b>.
     */
    public static boolean nomeTemaPlausibile(String nome) {
        if (nome == null || nome.isEmpty() || nome.length() > MAX_NOME_TEMA) {
            return false;
        }
        int parole = 1;
        for (int i = 0; i < nome.length(); i++) {
            final char c = nome.charAt(i);
            if (c == ' ') {
                // ripulisci() riduce gli spazi a uno solo e non ne lascia ai bordi, quindi uno spazio doppio o
                // di bordo qui non puo' arrivare; se arriva, la riga non viene da noi e si scarta
                if (i == 0 || i == nome.length() - 1 || nome.charAt(i - 1) == ' ') {
                    return false;
                }
                parole++;
                if (parole > MAX_PAROLE_TEMA) {
                    return false;
                }
                continue;
            }
            // niente caratteri di controllo e niente spazi invisibili (U+00A0 e compagni): sono proprio quelli
            // con cui si fabbrica una frase che il conteggio delle parole non vede
            if (!visibile(c)) {
                return false;
            }
        }
        return true;
    }

    /* ============================================================================= deposito e dintorni */

    /** Su Android: {@code SharedPreferences} + un {@code Editor} che si committa in blocco. Nel test: una mappa. */
    public interface Deposito {
        boolean contiene(String chiave);

        boolean leggiBool(String chiave, boolean def);

        int leggiInt(String chiave, int def);

        long leggiLong(String chiave, long def);

        String leggiStr(String chiave, String def);

        void scriviBool(String chiave, boolean valore);

        void scriviInt(String chiave, int valore);

        void scriviLong(String chiave, long valore);

        void scriviStr(String chiave, String valore);

        /** Rende definitive le scritture accumulate. Falso se non si e' potuto scrivere. */
        boolean salva();
    }

    /** Su Android: {@code HiddenChats.isHidden}. Un id nascosto non esce MAI, nemmeno dentro un CSV di altro. */
    public interface Nascoste {
        boolean nascosta(int slot, long dialogId);
    }

    /** Un account da esportare o su cui ripristinare: slot, user id, deposito. */
    public static final class Conto {
        public final int slot;
        public final long userId;
        public final Deposito deposito;

        public Conto(int slot, long userId, Deposito deposito) {
            this.slot = slot;
            this.userId = userId;
            this.deposito = deposito;
        }
    }

    /**
     * Un widget, SENZA appWidgetId: gli id li assegna il launcher alla reinstallazione, quindi non si
     * trasportano. Quello che si trasporta e' il modello (tipo + chat + aspetto), che viene proposto quando si
     * piazza un widget dello stesso tipo.
     *
     * <p>La chat viaggia con lo <b>user id</b> del suo account, non con lo slot: lo slot lo decide l'ordine di
     * login sul telefono nuovo, ed e' lo stesso motivo per cui le preferenze per account viaggiano per user id
     * (vedi {@link #pianifica}). Con il solo slot, dopo un ripristino con gli account in ordine diverso, il
     * widget puntava a un altro account e il controllo delle chat nascoste cadeva sulla lista sbagliata: una
     * chat nascosta nell'account giusto tornava leggibile dalla schermata iniziale. Lo slot si scrive ancora,
     * perche' e' il terzo campo del formato 1, ma in lettura conta lo user id.
     */
    public static final class Widget {
        public final int tipo;
        /** Lo slot che l'account aveva quando il file e' stato scritto: NON e' portabile, vedi {@link #userId}. */
        public final int slot;
        public final long dialogId;
        public final int tema;
        public final int angoli;
        public final int opacita;
        /** L'account della chat, 0 se il widget non mostra una chat (aereo, non letti) o se il file non lo dice. */
        public final long userId;

        public Widget(int tipo, int slot, long dialogId, int tema, int angoli, int opacita, long userId) {
            this.tipo = tipo;
            this.slot = slot;
            this.dialogId = dialogId;
            this.tema = tema;
            this.angoli = angoli;
            this.opacita = opacita;
            this.userId = userId;
        }

        /** Vero se i campi stanno negli intervalli dei widget Morsegram (tipi 1..5, tema 0..2, angoli 0..2). */
        public boolean valido() {
            return tipo >= 1 && tipo <= 5 && slot >= 0 && slot < 8
                    && tema >= 0 && tema <= 2 && angoli >= 0 && angoli <= 2
                    && opacita >= 0 && opacita <= 100 && userId >= 0;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Widget)) {
                return false;
            }
            final Widget w = (Widget) o;
            // lo user id entra nel confronto: due widget di chat uguali su account diversi sono due modelli
            return tipo == w.tipo && slot == w.slot && dialogId == w.dialogId && userId == w.userId
                    && tema == w.tema && angoli == w.angoli && opacita == w.opacita;
        }

        @Override
        public int hashCode() {
            return (tipo * 31 + (int) dialogId) * 31 + tema;
        }

        @Override
        public String toString() {
            return "w" + tipo + "/" + slot + "/" + dialogId + "/" + tema + "/" + angoli + "/" + opacita
                    + "/u" + userId;
        }
    }

    /**
     * I due nomi di tema configurati (chiavi upstream {@code lastDayTheme} / {@code lastDarkTheme}). Il tema in
     * se' non e' roba nostra e non si esporta: alla reinstallazione {@code MorsegramTheme.seedIfFirstRun}
     * riscrive i nostri due file e riseleziona il nostro tema da solo. Quello che si perde, e che questi due
     * nomi recuperano, e' la DEVIAZIONE dell'utente: un altro tema, uno scaricato.
     */
    public static final class Tema {
        public final String giorno;
        public final String notte;

        public Tema(String giorno, String notte) {
            this.giorno = giorno == null ? "" : giorno;
            this.notte = notte == null ? "" : notte;
        }

        public boolean vuoto() {
            return giorno.isEmpty() && notte.isEmpty();
        }
    }

    /** Cosa c'e' da esportare: il deposito globale, i conti attivi, i widget piazzati, i nomi dei temi. */
    public static final class Sorgente {
        public final Deposito globale;
        public final List<Conto> conti;
        public final List<Widget> widget;
        public final Tema tema;

        public Sorgente(Deposito globale, List<Conto> conti, List<Widget> widget, Tema tema) {
            this.globale = globale;
            this.conti = conti == null ? Collections.<Conto>emptyList() : conti;
            this.widget = widget == null ? Collections.<Widget>emptyList() : widget;
            this.tema = tema;
        }
    }

    /* ======================================================================================= scrittura */

    /**
     * Scrive il file. Enumera l'{@link Elenco}: mai {@code getAll()}, cosi' una preferenza segreta aggiunta
     * domani non esce per inerzia. Ogni dialog id passa dal filtro delle chat nascoste, id per id, su ogni CSV
     * e su ogni widget: escludere lo spazio di chiavi non basterebbe, perche' un id nascosto puo' comparire in
     * {@code pins.order}, in {@code alerts.chats} e nella configurazione di un widget di chat singola.
     */
    public static String scrivi(Elenco elenco, Sorgente sorgente, Nascoste nascoste,
                                String versioneApp, boolean sideload, long adesso) {
        final StringBuilder sb = new StringBuilder(2048);
        sb.append(MAGIC).append(' ').append(FORMATO).append('\n');
        sb.append("app ").append(senzaSpazi(versioneApp)).append(' ')
                .append(sideload ? "sideload" : "play").append(' ')
                .append(adesso).append(' ').append(dataUtc(adesso)).append('\n');
        if (sorgente == null) {
            return sb.toString();
        }
        for (Voce v : elenco.globali) {
            riga(sb, A_GLOBALE, 0, v, sorgente.globale, nascoste, -1);
        }
        for (Conto c : sorgente.conti) {
            if (c == null || c.userId == 0 || c.deposito == null) {
                continue;
            }
            for (Voce v : elenco.account) {
                riga(sb, A_ACCOUNT, c.userId, v, c.deposito, nascoste, c.slot);
            }
        }
        int quantiWidget = 0;
        for (Widget w : sorgente.widget) {
            if (w == null || !w.valido() || quantiWidget >= MAX_WIDGET) {
                continue;
            }
            // il widget di una chat nascosta non entra nel file: altrimenti il dialog id uscirebbe da qui
            // dopo essere stato escluso da hidden.ids, e la cache di contenuto esiste proprio per non
            // lasciare niente di una chat nascosta leggibile dalla schermata iniziale
            if (w.dialogId != 0 && nascoste != null && nascoste.nascosta(w.slot, w.dialogId)) {
                continue;
            }
            // lo user id va in CODA, come settimo campo: un lettore del formato 1 controlla solo che i campi
            // siano almeno sette (vedi il ramo A_WIDGET di leggi), quindi un campo in piu' non gli fa scartare
            // la riga e il formato resta 1
            sb.append(A_WIDGET).append(' ').append(w.tipo).append(' ').append(w.slot).append(' ')
                    .append(w.dialogId).append(' ').append(w.tema).append(' ')
                    .append(w.angoli).append(' ').append(w.opacita).append(' ')
                    .append(w.userId).append('\n');
            quantiWidget++;
        }
        if (sorgente.tema != null) {
            // i nomi dei temi possono contenere spazi ("Morsegram notte"): sono l'ultimo campo della riga e il
            // lettore riunisce i campi rimanenti con un singolo spazio, quindi il giro scrivi-leggi e' stabile.
            // Si scrive solo il nome che il NOSTRO lettore riaccetta (nomeTemaPlausibile), come per ogni altra
            // voce: il file che scriviamo deve restare un file che rileggiamo
            final String giorno = ripulisci(sorgente.tema.giorno);
            final String notte = ripulisci(sorgente.tema.notte);
            if (nomeTemaPlausibile(giorno)) {
                sb.append(A_TEMA).append(" giorno ").append(giorno).append('\n');
            }
            if (nomeTemaPlausibile(notte)) {
                sb.append(A_TEMA).append(" notte ").append(notte).append('\n');
            }
        }
        return sb.toString();
    }

    private static void riga(StringBuilder sb, char ambito, long userId, Voce v,
                             Deposito d, Nascoste nascoste, int slot) {
        if (v == null || d == null || vietata(v.chiave) || !d.contiene(v.chiave)) {
            return;
        }
        String valore;
        switch (v.tipo) {
            case T_BOOL:
                valore = Boolean.toString(d.leggiBool(v.chiave, false));
                break;
            case T_INT:
                valore = Integer.toString(d.leggiInt(v.chiave, 0));
                break;
            case T_LONG:
                valore = Long.toString(d.leggiLong(v.chiave, 0));
                break;
            default: {
                valore = d.leggiStr(v.chiave, "");
                if (valore == null) {
                    return;
                }
                if (v.testo == TESTO_ID_CSV || v.testo == TESTO_ID_LIVELLO_CSV) {
                    // un CSV di dialog id non passa da ripulisci, che taglia a CARATTERI: un taglio in mezzo
                    // a un id ne inventerebbe uno piu' corto, e sarebbe un id valido per filtraIds, per il
                    // nostro lettore e per LocalPins. Qui si tagliano VOCI INTERE, e dopo il filtro delle
                    // nascoste, perche' filtraIds riscrive ogni voce dai numeri che ha validato: quello che
                    // esce da li' non ha ne' spazi ne' caratteri di controllo
                    valore = filtraIds(valore, nascoste, slot, v.testo == TESTO_ID_LIVELLO_CSV, v.tetto());
                } else {
                    valore = ripulisci(valore, v.tetto());
                }
                if (valore.isEmpty() && !v.ammetteVuoto()) {
                    return;
                }
                break;
            }
        }
        // ultima rete: un valore che il nostro stesso lettore scarterebbe non si scrive
        if (!v.valida(valore)) {
            return;
        }
        sb.append(ambito).append(' ');
        if (ambito == A_ACCOUNT) {
            sb.append(userId).append(' ');
        }
        // alFile: il valore vuoto diventa il segnaposto, cosi' la riga ha sempre i suoi campi (vedi VUOTO)
        sb.append(v.tipo).append(' ').append(v.chiave).append(' ').append(v.alFile(valore)).append('\n');
    }

    /**
     * Toglie dal CSV ogni id di chat nascosta e ogni voce rotta, e si ferma al {@code tetto} di caratteri
     * <b>fra due voci</b>: un id mozzo non si scrive mai. Vuoto se non resta niente.
     *
     * <p>Si scorre a mano invece di {@code split(",")} per due motivi: si smette appena il tetto e' pieno
     * (un valore assurdo nelle preferenze non si copia tutto per poi buttarlo), e si rispetta lo stesso tetto
     * di voci che applica il lettore. Ogni voce accettata viene RISCRITTA dai numeri validati, quindi quello
     * che esce da qui non contiene ne' spazi ne' caratteri di controllo e non ha bisogno di {@link #ripulisci}.
     */
    private static String filtraIds(String csv, Nascoste nascoste, int slot, boolean conLivello, int tetto) {
        if (csv == null || csv.isEmpty()) {
            return "";
        }
        final StringBuilder out = new StringBuilder(Math.min(csv.length(), tetto) + 1);
        int voci = 0;
        int da = 0;
        while (da <= csv.length() && voci < MAX_VOCI_CSV) {
            int fine = csv.indexOf(',', da);
            if (fine < 0) {
                fine = csv.length();
            }
            final String p = csv.substring(da, fine).trim();
            da = fine + 1;
            if (p.isEmpty()) {
                continue;
            }
            final int sep = p.indexOf(':');
            if (conLivello != (sep > 0)) {
                continue;
            }
            final long id = numero(sep > 0 ? p.substring(0, sep) : p, 0);
            if (id == 0) {
                continue;
            }
            long livello = 0;
            if (conLivello) {
                livello = numero(p.substring(sep + 1), -1);
                if (livello < 0 || livello > 2) {
                    continue;
                }
            }
            if (nascoste != null && nascoste.nascosta(slot, id)) {
                continue;
            }
            final String voce = conLivello ? id + ":" + livello : Long.toString(id);
            if (out.length() + (out.length() > 0 ? 1 : 0) + voce.length() > tetto) {
                break;      // il tetto si riempie FRA due voci: meglio una chat in meno che un id inventato
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(voce);
            voci++;
        }
        return out.toString();
    }

    /**
     * Il formato e' a campi su una riga: via i caratteri di controllo e i capo riga, gli spazi multipli
     * diventano uno solo (cosi' il giro scrivi-leggi-scrivi e' stabile byte per byte).
     */
    private static String ripulisci(String s) {
        return ripulisci(s, MAX_VALORE);
    }

    /** Come {@link #ripulisci(String)}, ma con il tetto della voce: vedi {@link Voce#tetto()}. */
    private static String ripulisci(String s, int tetto) {
        if (s == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(Math.min(s.length(), tetto));
        boolean spazio = false;
        for (int i = 0; i < s.length() && sb.length() < tetto; i++) {
            final char c = s.charAt(i);
            if (!visibile(c)) {
                spazio = sb.length() > 0;
                continue;
            }
            if (spazio) {
                sb.append(' ');
                spazio = false;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Falso per i caratteri di controllo e per OGNI spazio, compresi quelli invisibili (U+00A0, U+2007 e
     * simili): sono separatori per {@link #ripulisci} e valori rifiutati per il testo libero, cosi' i due lati
     * dicono la stessa cosa e il giro scrivi-leggi-scrivi resta stabile byte per byte.
     */
    private static boolean visibile(char c) {
        return c > ' ' && c != 127 && !Character.isSpaceChar(c) && !Character.isWhitespace(c);
    }

    private static String senzaSpazi(String s) {
        return ripulisci(s).replace(' ', '_');
    }

    /**
     * Una parola sola, ripulita e tagliata al {@code tetto}: la versione dell'app e l'edizione, che si leggono
     * dal file e finiscono nel dialogo dell'anteprima. Nel file sono testo arbitrario come tutto il resto
     * (provato sulla JVM: una versione di 2000 caratteri passava intatta), quindi si tengono solo i caratteri
     * di cui e' fatto un numero di versione e si taglia. Se non resta niente, stringa vuota: l'anteprima
     * mostra "?" come per un file senza riga {@code app}.
     */
    private static String parola(String s, int tetto) {
        if (s == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(Math.min(s.length(), tetto));
        for (int i = 0; i < s.length() && sb.length() < tetto; i++) {
            final char c = s.charAt(i);
            final boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '-' || c == '_' || c == '+';
            if (ok) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** La data in chiaro accanto ai millisecondi, perche' il file si legge anche a occhio. */
    private static String dataUtc(long millis) {
        final java.util.Calendar c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        c.setTimeInMillis(millis);
        return String.format(Locale.ROOT, "%04d-%02d-%02dT%02d:%02d:%02dZ",
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY),
                c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND));
    }

    /* ========================================================================================= lettura */

    /** Cosa c'era nel file, dopo il filtro del lettore. */
    public static final class Letto {
        public int formato;
        public String versioneApp = "";
        public String edizione = "";
        public long creato;
        /** chiave -> valore in forma di testo, nell'ordine del file. */
        public final Map<String, String> globali = new LinkedHashMap<>();
        /** user id -> (chiave -> valore). */
        public final Map<Long, Map<String, String>> account = new LinkedHashMap<>();
        public final List<Widget> widget = new ArrayList<>();
        public String temaGiorno = "";
        public String temaNotte = "";
        /**
         * Righe che il lettore non ha RICONOSCIUTO: chiave che non conosce, tipo che non corrisponde, valore
         * fuori intervallo, riga mozza. Sono il caso "file di un'altra versione o ritoccato a mano".
         */
        public int righeIgnote;
        /**
         * Righe RIFIUTATE per scelta: nominano dati che non si ripristinano ({@link #vietata(String)}), il PIN
         * e le chat nascoste per prime. Si contano a parte perche' dire "questa versione non le riconosce" di
         * una riga {@code hidden.pin.hash} sarebbe falso: la riconosciamo benissimo, e proprio per questo la
         * buttiamo via.
         */
        public int righeRifiutate;
        /** le chiavi non riconosciute (al massimo le prime dieci: un file ostile non riempie la UI). */
        public final List<String> chiaviIgnote = new ArrayList<>();
        /** le chiavi rifiutate, per poterle dire (al massimo le prime dieci). */
        public final List<String> chiaviRifiutate = new ArrayList<>();

        /** Il totale delle righe buttate via, per i messaggi che dicono un numero solo. */
        public int righeScartate() {
            return righeIgnote + righeRifiutate;
        }

        void scarta(String chiave) {
            righeIgnote++;
            aggiungi(chiaviIgnote, chiave);
        }

        void rifiuta(String chiave) {
            righeRifiutate++;
            aggiungi(chiaviRifiutate, chiave);
        }

        /**
         * Mette in elenco il NOME della chiave, e solo se e' un nome di chiave plausibile. Il conto della riga
         * (sopra) si fa comunque: quello che si filtra qui e' il testo che la UI stampa nel dialogo
         * dell'anteprima, che e' testo scelto da chi ha scritto il file e compare accanto ai testi dell'app, in
         * un elenco che l'utente legge come un referto. Vedi {@link MorsegramBackup#nomePlausibile}.
         */
        private static void aggiungi(List<String> dove, String chiave) {
            if (nomePlausibile(chiave) && dove.size() < 10 && !dove.contains(chiave)) {
                dove.add(chiave);
            }
        }

        public boolean nienteDaLeggere() {
            return globali.isEmpty() && account.isEmpty() && widget.isEmpty()
                    && temaGiorno.isEmpty() && temaNotte.isEmpty();
        }
    }

    /** Esito della lettura: {@link #OK} con il contenuto, oppure un codice di errore e {@code letto} null. */
    public static final class Esito {
        public final int codice;
        public final Letto letto;
        /** La versione del formato trovata, utile per il messaggio "backup di una versione piu' nuova". */
        public final int formato;

        Esito(int codice, Letto letto, int formato) {
            this.codice = codice;
            this.letto = letto;
            this.formato = formato;
        }

        public boolean ok() {
            return codice == OK && letto != null;
        }
    }

    /**
     * Legge e valida, senza scrivere niente da nessuna parte. Rifiuta: file vuoto, piu' grande del tetto, con la
     * riga magica sbagliata (un file di un'altra app) o con una versione del formato piu' nuova di noi — che non
     * si indovina, e il messaggio all'utente deve dire proprio questo invece di "file non valido".
     */
    public static Esito leggi(byte[] bytes, Elenco elenco) {
        if (bytes == null || bytes.length == 0) {
            return new Esito(ERR_VUOTO, null, 0);
        }
        if (bytes.length > MAX_BYTES) {
            return new Esito(ERR_TROPPO_GRANDE, null, 0);
        }
        String testo = new String(bytes, StandardCharsets.UTF_8);
        // il BOM UTF-8, che diversi editor di Windows aggiungono al salvataggio: senza questa riga il primo
        // campo diventa "﻿morsegram-impostazioni" e un backup valido, aperto e risalvato a mano dall'utente,
        // diventerebbe un "file non nostro". trim() non lo toglie: non e' un carattere minore o uguale allo
        // spazio. E il formato e' dichiarato leggibile e ritoccabile a mano proprio per farsi controllare
        if (testo.startsWith("﻿")) {
            testo = testo.substring(1);
        }
        // CRLF, CR soli e riga vuota finale non devono rompere nulla
        final String[] righe = testo.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        final String[] capo = campi(righe[0]);
        if (capo.length < 2 || !MAGIC.equals(capo[0])) {
            return new Esito(ERR_NON_NOSTRO, null, 0);
        }
        final long formato = numero(capo[1], -1);
        if (formato < 1) {
            return new Esito(ERR_NON_NOSTRO, null, 0);
        }
        if (formato > FORMATO) {
            return new Esito(ERR_FORMATO_FUTURO, null, (int) formato);
        }
        final Letto letto = new Letto();
        letto.formato = (int) formato;
        for (int i = 1; i < righe.length; i++) {
            final String riga = righe[i].trim();
            if (riga.isEmpty()) {
                continue;   // riga vuota (anche quella finale): non e' un errore, non si conta
            }
            final String[] c = campi(riga);
            if ("app".equals(c[0])) {
                // versione ed edizione si mostrano all'utente: si tagliano e si ripuliscono (vedi parola)
                letto.versioneApp = c.length > 1 ? parola(c[1], MAX_VERSIONE) : "";
                letto.edizione = c.length > 2 ? parola(c[2], MAX_VERSIONE) : "";
                letto.creato = c.length > 3 ? Math.max(0, numero(c[3], 0)) : 0;
                continue;
            }
            if (c[0].length() != 1) {
                letto.scarta(null);
                continue;
            }
            switch (c[0].charAt(0)) {
                case A_GLOBALE: {
                    final Coppia k = preferenza(letto, elenco, A_GLOBALE, c, 1);
                    if (k != null) {
                        letto.globali.put(k.chiave, k.valore);
                    }
                    break;
                }
                case A_ACCOUNT: {
                    final long userId = c.length > 1 ? numero(c[1], 0) : 0;
                    if (userId <= 0) {
                        letto.scarta(null);
                        break;
                    }
                    final Coppia k = preferenza(letto, elenco, A_ACCOUNT, c, 2);
                    if (k == null) {
                        break;      // niente blocco vuoto: un utente senza righe valide non esiste nel file
                    }
                    Map<String, String> m = letto.account.get(userId);
                    if (m == null) {
                        m = new LinkedHashMap<>();
                        letto.account.put(userId, m);
                    }
                    m.put(k.chiave, k.valore);
                    break;
                }
                case A_WIDGET: {
                    if (c.length < 7 || letto.widget.size() >= MAX_WIDGET) {
                        letto.scarta(null);
                        break;
                    }
                    // il settimo campo (user id) manca nei file scritti prima che lo si trasportasse: 0, e
                    // allora la chat non si rimette su nessun account (BackupWidgets.proponi), perche' lo slot
                    // del file non dice a chi apparteneva
                    final Widget w = new Widget((int) numero(c[1], -1), (int) numero(c[2], -1), numero(c[3], 0),
                            (int) numero(c[4], -1), (int) numero(c[5], -1), (int) numero(c[6], -1),
                            c.length >= 8 ? Math.max(0, numero(c[7], 0)) : 0);
                    if (w.valido()) {
                        letto.widget.add(w);
                    } else {
                        letto.scarta(null);
                    }
                    break;
                }
                case A_TEMA: {
                    if (c.length < 3) {
                        letto.scarta(null);
                        break;
                    }
                    final String nome = ripulisci(unisci(c, 2));
                    // il nome di tema e' testo scelto da chi ha scritto il file e finisce DENTRO le frasi
                    // dell'anteprima: qui si rifiuta quello che non e' un nome di tema, invece di tagliarlo
                    // (vedi nomeTemaPlausibile). La riga resta contata fra quelle non riconosciute, quindi il
                    // file che portava solo un tema finto non diventa un file "senza niente da dire"
                    if (!nomeTemaPlausibile(nome)) {
                        letto.scarta(null);
                    } else if ("giorno".equals(c[1])) {
                        letto.temaGiorno = nome;
                    } else if ("notte".equals(c[1])) {
                        letto.temaNotte = nome;
                    } else {
                        letto.scarta(null);
                    }
                    break;
                }
                default:
                    letto.scarta(null);
                    break;
            }
        }
        if (letto.nienteDaLeggere()) {
            return new Esito(ERR_NIENTE_DA_FARE, letto, letto.formato);
        }
        return new Esito(OK, letto, letto.formato);
    }

    /** Una chiave e il suo valore, accettati dal lettore. */
    private static final class Coppia {
        final String chiave;
        final String valore;

        Coppia(String chiave, String valore) {
            this.chiave = chiave;
            this.valore = valore;
        }
    }

    /**
     * Una riga di preferenza: {@code <ambito> [userId] <tipo> <chiave> <valore>}. Il lettore e' un elenco
     * consentito a sua volta: una chiave che non conosce, un tipo che non corrisponde a quello dichiarato
     * nell'elenco o un valore fuori intervallo <b>non entrano da nessuna parte</b> e vengono contati. Cosi' un
     * file manomesso che nomina {@code hidden.pin.hash} o {@code passcodeHash} non scrive niente. I due motivi
     * si contano separati: {@code passcodeHash} non lo conosciamo, {@code hidden.pin.hash} lo conosciamo e lo
     * rifiutiamo, e all'utente va detto quello vero.
     *
     * @return la coppia accettata, oppure null (riga scartata e contata)
     */
    private static Coppia preferenza(Letto letto, Elenco elenco, char ambito, String[] c, int da) {
        if (c.length < da + 3) {
            letto.scarta(null);
            return null;
        }
        final String tipo = c[da];
        final String chiave = c[da + 1];
        if (vietata(chiave)) {
            // prima di tutto il resto: una riga che nomina il PIN, le chat nascoste o una chiave di fornitore
            // non e' una riga "non riconosciuta", e' una riga che rifiutiamo per scelta. L'utente deve leggere
            // il motivo giusto, perche' e' su quello che decide
            letto.rifiuta(chiave);
            return null;
        }
        final Voce v = elenco == null ? null : elenco.voce(ambito, chiave);
        if (v == null || tipo.length() != 1 || v.tipo != tipo.charAt(0)) {
            letto.scarta(chiave);
            return null;
        }
        // dalFile: il segnaposto del valore vuoto torna stringa vuota PRIMA della validazione, cosi' il resto
        // del codice vede il valore logico e non il modo in cui e' scritto nel file (vedi VUOTO)
        final String valore = v.dalFile(unisci(c, da + 2));
        if (!v.valida(valore)) {
            letto.scarta(chiave);
            return null;
        }
        return new Coppia(chiave, valore);
    }

    /**
     * I campi di una riga. Si separa su RUNGHE di spazi (e tabulazioni), non su un singolo spazio: un file
     * ritoccato a mano o passato da un editor che allinea le colonne resta leggibile, e lo scrittore riduce
     * comunque gli spazi interni dei valori a uno solo, quindi il giro scrivi-leggi-scrivi non cambia i byte.
     */
    private static String[] campi(String riga) {
        final String t = riga.trim();
        return t.isEmpty() ? new String[]{""} : t.split("[ \\t]+");
    }

    private static String unisci(String[] c, int da) {
        final StringBuilder sb = new StringBuilder();
        for (int i = da; i < c.length; i++) {
            if (c[i].isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(c[i]);
        }
        return sb.toString();
    }

    /** {@code def} se non e' un numero: nessuna eccezione esce da qui. */
    private static long numero(String s, long def) {
        if (s == null) {
            return def;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /* ======================================================================================= anteprima */

    /** Una cosa che il ripristino cambierebbe: dove, cosa c'e' adesso, cosa ci sarebbe dopo. */
    public static final class Cambio {
        public final char ambito;
        public final long userId;
        public final String chiave;
        public final char tipo;
        /** valore attuale in forma di testo, vuoto se la preferenza non c'e' ancora. */
        public final String attuale;
        public final String nuovo;

        Cambio(char ambito, long userId, String chiave, char tipo, String attuale, String nuovo) {
            this.ambito = ambito;
            this.userId = userId;
            this.chiave = chiave;
            this.tipo = tipo;
            this.attuale = attuale == null ? "" : attuale;
            this.nuovo = nuovo;
        }
    }

    /**
     * Cosa accadrebbe applicando il file: l'anteprima che l'utente vede PRIMA di sovrascrivere qualcosa.
     * Contiene solo le differenze vere: una preferenza che nel file ha lo stesso valore che ha adesso non e' un
     * cambio e non va mostrata.
     */
    public static final class Piano {
        public final List<Cambio> cambi = new ArrayList<>();
        /** modelli di widget da offrire al prossimo piazzamento. */
        public final List<Widget> widget = new ArrayList<>();
        /** nomi dei temi da riapplicare, vuoti se non ce ne sono o se coincidono con quelli attuali. */
        public Tema tema;
        /**
         * I nomi di tema che il file portava e che su questo telefono NON esistono, separati da virgola: li
         * toglie l'anello Android ({@code BackupStore.pianifica}), perche' il codec non sa quali temi ci sono.
         * Vuoto se non ne e' stato scartato nessuno.
         *
         * <p>Serve perche' il caso che il filtro esiste per gestire — un tema SCARICATO, scomparso dopo la
         * reinstallazione — e' anche quello in cui il piano resta vuoto, e un piano vuoto diceva "questo file
         * contiene le stesse impostazioni che hai adesso", cioe' l'unica cosa falsa che si poteva dire.
         *
         * <p>Contiene solo nomi che passano {@link MorsegramBackup#nomeTemaPlausibile}, cioe' corti: e' testo
         * del file che l'anteprima stampa dentro le sue frasi.
         */
        public String temaNonTrovato = "";
        /** user id presenti nel file che su questo telefono non sono loggati: si salta in silenzio. */
        public final List<Long> accountMancanti = new ArrayList<>();
        /** righe che il lettore non ha riconosciuto (vedi {@link Letto#righeIgnote}). */
        public int righeIgnote;
        /** righe rifiutate perche' nominano dati che non si ripristinano (vedi {@link Letto#righeRifiutate}). */
        public int righeRifiutate;
        /** le chiavi non riconosciute: si mostrano, cosi' si vede COSA e' stato buttato via. */
        public final List<String> chiaviIgnote = new ArrayList<>();
        /** le chiavi rifiutate: si mostrano, cosi' si vede che il PIN o una chiave di fornitore era nel file. */
        public final List<String> chiaviRifiutate = new ArrayList<>();
        public String versioneApp = "";
        public String edizione = "";
        public long creato;

        public boolean vuoto() {
            return cambi.isEmpty() && widget.isEmpty() && (tema == null || tema.vuoto());
        }

        /** Quanti cambi riguardano le preferenze globali. */
        public int quantiGlobali() {
            int n = 0;
            for (Cambio c : cambi) {
                if (c.ambito == A_GLOBALE) {
                    n++;
                }
            }
            return n;
        }

        public int quantiAccount() {
            return cambi.size() - quantiGlobali();
        }

        /** Il totale delle righe buttate via, per i messaggi che dicono un numero solo. */
        public int righeScartate() {
            return righeIgnote + righeRifiutate;
        }
    }

    /**
     * Confronta il file con quello che c'e' adesso. Non scrive niente: e' la fase che rende impossibile un
     * ripristino a meta', perche' tutto cio' che non passa di qui non arriva alla scrittura.
     *
     * @param conti i soli account LOGGATI su questo telefono, per user id: le preferenze per account si
     *              ripristinano per user id, perche' {@code MorsegramPrefs.account(int)} apre
     *              {@code morsegram_u<userId>}; un blocco di un utente che qui non c'e' si salta in silenzio
     */
    public static Piano pianifica(Letto letto, Elenco elenco, Deposito globale,
                                  Map<Long, Conto> conti, Tema temaAttuale) {
        final Piano p = new Piano();
        if (letto == null) {
            return p;
        }
        p.righeIgnote = letto.righeIgnote;
        p.righeRifiutate = letto.righeRifiutate;
        p.chiaviIgnote.addAll(letto.chiaviIgnote);
        p.chiaviRifiutate.addAll(letto.chiaviRifiutate);
        p.versioneApp = letto.versioneApp;
        p.edizione = letto.edizione;
        p.creato = letto.creato;
        for (Map.Entry<String, String> e : letto.globali.entrySet()) {
            final Voce v = elenco.voce(A_GLOBALE, e.getKey());
            if (v == null) {
                continue;
            }
            final String attuale = attuale(globale, v);
            if (!e.getValue().equals(attuale)) {
                p.cambi.add(new Cambio(A_GLOBALE, 0, v.chiave, v.tipo, attuale, e.getValue()));
            }
        }
        for (Map.Entry<Long, Map<String, String>> blocco : letto.account.entrySet()) {
            final long userId = blocco.getKey();
            final Conto conto = conti == null ? null : conti.get(userId);
            if (conto == null || conto.deposito == null) {
                p.accountMancanti.add(userId);
                continue;
            }
            for (Map.Entry<String, String> e : blocco.getValue().entrySet()) {
                final Voce v = elenco.voce(A_ACCOUNT, e.getKey());
                if (v == null) {
                    continue;
                }
                final String attuale = attuale(conto.deposito, v);
                if (!e.getValue().equals(attuale)) {
                    p.cambi.add(new Cambio(A_ACCOUNT, userId, v.chiave, v.tipo, attuale, e.getValue()));
                }
            }
        }
        p.widget.addAll(letto.widget);
        if (!letto.temaGiorno.isEmpty() || !letto.temaNotte.isEmpty()) {
            final String giorno = temaAttuale != null && letto.temaGiorno.equals(temaAttuale.giorno)
                    ? "" : letto.temaGiorno;
            final String notte = temaAttuale != null && letto.temaNotte.equals(temaAttuale.notte)
                    ? "" : letto.temaNotte;
            final Tema t = new Tema(giorno, notte);
            p.tema = t.vuoto() ? null : t;
        }
        return p;
    }

    private static String attuale(Deposito d, Voce v) {
        if (d == null || !d.contiene(v.chiave)) {
            return "";
        }
        switch (v.tipo) {
            case T_BOOL:
                return Boolean.toString(d.leggiBool(v.chiave, false));
            case T_INT:
                return Integer.toString(d.leggiInt(v.chiave, 0));
            case T_LONG:
                return Long.toString(d.leggiLong(v.chiave, 0));
            default: {
                final String s = d.leggiStr(v.chiave, "");
                return s == null ? "" : s;
            }
        }
    }

    /* ===================================================================================== ripristino */

    /**
     * Applica il {@link Piano} ai depositi: prima tutte le scritture in memoria, poi un {@code salva()} per
     * file. Se un {@code salva()} non riesce, quel file resta com'era (e' il commit delle SharedPreferences, che
     * e' atomico per file): si ritorna -1 e il chiamante lo dice, invece di far credere a un ripristino fatto.
     *
     * <p>Qui NON si tocca niente di upstream: nessun passcode, nessuna sessione, nessun {@code mainconfig}. E
     * nemmeno i widget e i temi, che hanno bisogno di Android e li applica {@link BackupStore}: la SELEZIONE del
     * tema e' l'unica cosa di upstream che il ripristino scrive, da la', e il javadoc di {@link BackupStore} dice
     * quali chiavi esattamente.
     *
     * @return quante preferenze sono state scritte, -1 se un file non si e' potuto scrivere
     */
    public static int applica(Piano piano, Deposito globale, Map<Long, Conto> conti) {
        if (piano == null) {
            return 0;
        }
        int n = 0;
        final List<Deposito> daSalvare = new ArrayList<>(4);
        for (Cambio c : piano.cambi) {
            if (vietata(c.chiave)) {
                continue;   // non puo' accadere: il lettore l'ha gia' escluso. Resta come rete, costa nulla
            }
            final Deposito d;
            if (c.ambito == A_GLOBALE) {
                d = globale;
            } else {
                final Conto conto = conti == null ? null : conti.get(c.userId);
                d = conto == null ? null : conto.deposito;
            }
            if (d == null) {
                continue;
            }
            switch (c.tipo) {
                case T_BOOL:
                    d.scriviBool(c.chiave, "true".equals(c.nuovo));
                    break;
                case T_INT:
                    d.scriviInt(c.chiave, (int) numero(c.nuovo, 0));
                    break;
                case T_LONG:
                    d.scriviLong(c.chiave, numero(c.nuovo, 0));
                    break;
                default:
                    d.scriviStr(c.chiave, c.nuovo);
                    break;
            }
            n++;
            if (!daSalvare.contains(d)) {
                daSalvare.add(d);
            }
        }
        for (Deposito d : daSalvare) {
            if (!d.salva()) {
                return -1;
            }
        }
        return n;
    }
}

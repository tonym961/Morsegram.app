/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Archivio LOCALE delle segnalazioni di un risultato generato dall'assistente.
 *
 * <p>Esiste per un obbligo di Google Play in vigore dal 31 gennaio 2024 e ribadito nel 2026: un'app che genera
 * contenuti con l'AI deve permettere di SEGNALARE un risultato offensivo <b>senza uscire dall'app</b>. La finestra
 * è {@link AiReportSheet}, l'elenco è {@link AiReportsActivity}, i dati stanno qui.</p>
 *
 * <p>Cosa si registra, e cosa no:</p>
 * <ul>
 * <li><b>Sì</b>: data, fornitore, modello, funzione usata, motivo scelto, commento facoltativo e il testo
 *     GENERATO, cioè quello che l'utente sta segnalando.</li>
 * <li><b>No</b>: il testo originale della chat. È dell'utente, è già sul dispositivo, e duplicarlo in un secondo
 *     file solo per avere "contesto" sarebbe una copia in più di una conversazione privata.</li>
 * </ul>
 *
 * <p>Dove: una riga JSON per segnalazione in {@value #FILE}, nella cartella privata dell'app
 * ({@code getFilesDirFixed()}, {@code MODE_PRIVATE}). Fuori dai backup per costruzione, per la stessa ragione
 * spiegata in {@link AiKeys}: l'app dichiara un {@code backupAgent} che registra un solo helper, e nient'altro
 * viene salvato. Il formato a righe serve a poter aggiungere in coda senza rileggere e riscrivere tutto.</p>
 *
 * <p><b>Una segnalazione non parte da nessuna parte.</b> Non c'è rete, non c'è un intent di consegna, e dentro
 * l'app non esiste nessun indirizzo a cui mandarla: un indirizzo scritto nel sorgente finirebbe dentro l'APK, e
 * un APK è pubblico. La segnalazione resta sul telefono di chi l'ha fatta, e lì la rilegge o la cancella. È
 * anche quanto Google Play chiede: che il percorso di segnalazione esista dentro l'app, non che l'app spedisca.
 * I testi mostrati all'utente non promettono altro.</p>
 *
 * <p>Tutti i metodi che toccano il file vanno chiamati da un thread di fondo.</p>
 */
public final class AiReports {

    /** Contenuto offensivo, volgare, odioso. */
    public static final int REASON_OFFENSIVE = 0;
    /** Falso o fuorviante: il modello ha inventato. */
    public static final int REASON_FALSE = 1;
    /** Altro: il motivo lo scrive l'utente nel commento. */
    public static final int REASON_OTHER = 2;

    /** Un file per riga, nella cartella privata dell'app: niente backup, niente accesso da altre app. */
    public static final String FILE = "morsegram_ai_reports.jsonl";

    /**
     * Tetti: l'archivio è una coda di segnalazioni, non una seconda copia della cronologia. Le più vecchie
     * cadono, il testo segnalato viene troncato: un risultato dell'assistente sta sotto i 700 token.
     */
    private static final int MAX_REPORTS = 50;
    private static final int MAX_TEXT_CHARS = 4000;
    private static final int MAX_COMMENT_CHARS = 1000;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private AiReports() {
    }

    /* ======================================================================================= tipi */

    /**
     * Da dove viene un risultato: chi l'ha generato e con quale funzione. Lo riempie {@link AiClient} con il
     * fornitore che ha RISPOSTO DAVVERO (che con un ripiego configurato può non essere il principale).
     */
    public static final class Origin {

        public final int providerId;
        public final String model;
        /** Chiave di stringa della funzione: MorsegramAiSummarize, MorsegramAiTranslate, ... */
        public final String taskKey;

        public Origin(int providerId, String model, String taskKey) {
            this.providerId = providerId;
            this.model = model == null ? "" : model;
            this.taskKey = taskKey == null ? "" : taskKey;
        }
    }

    /** Una segnalazione registrata. {@link #id} è il momento in cui è stata fatta, e insieme la sua chiave. */
    public static final class Report {

        public final long id;
        public final String provider;
        public final String model;
        public final String taskKey;
        public final int reason;
        public final String comment;
        /** Il testo GENERATO, quello segnalato. Mai il testo originale della chat. */
        public final String text;

        Report(long id, String provider, String model, String taskKey, int reason, String comment, String text) {
            this.id = id;
            this.provider = provider == null ? "" : provider;
            this.model = model == null ? "" : model;
            this.taskKey = taskKey == null ? "" : taskKey;
            this.reason = reason;
            this.comment = comment == null ? "" : comment;
            this.text = text == null ? "" : text;
        }

        /** Data e ora nel formato dell'utente ("ott 21 2026, 19:40"), 12 o 24 ore come da impostazioni. */
        public String when() {
            try {
                return LocaleController.getInstance().getFormatterStats().format(id);
            } catch (Throwable ignore) {
                return stamp(id);
            }
        }

        public String reasonLabel() {
            return LocaleController.getString(reasonKey(reason));
        }

        /** Nome della funzione da cui veniva il risultato, o vuoto se la chiave non esiste più. */
        public String taskLabel() {
            if (taskKey.length() == 0) {
                return "";
            }
            final String label = LocaleController.getString(taskKey);
            return label == null || label.startsWith("LOC_ERR") ? "" : label;
        }
    }

    public static String reasonKey(int reason) {
        switch (reason) {
            case REASON_FALSE:
                return "MorsegramAiReportReasonFalse";
            case REASON_OTHER:
                return "MorsegramAiReportReasonOther";
            case REASON_OFFENSIVE:
            default:
                return "MorsegramAiReportReasonOffensive";
        }
    }

    /* ==================================================================================== scrittura */

    /**
     * Registra una segnalazione. Da thread di fondo.
     *
     * @return la segnalazione scritta, o null se il file non si è potuto scrivere (lo si dice all'utente: una
     *         conferma per una segnalazione che non è stata registrata sarebbe una bugia)
     */
    public static Report add(Origin origin, int reason, String comment, String generated) {
        if (origin == null || TextUtils.isEmpty(generated)) {
            return null;
        }
        final Report report = new Report(
                System.currentTimeMillis(),
                AiProvider.labelOf(origin.providerId),
                origin.model,
                origin.taskKey,
                reason,
                cut(comment, MAX_COMMENT_CHARS),
                cut(generated, MAX_TEXT_CHARS));
        final List<Report> all = all();
        all.add(0, report);
        while (all.size() > MAX_REPORTS) {
            all.remove(all.size() - 1);
        }
        return write(all) ? report : null;
    }

    /** Cancella una segnalazione. Da thread di fondo. */
    public static boolean remove(long id) {
        final List<Report> all = all();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id == id) {
                all.remove(i);
                return write(all);
            }
        }
        return false;
    }

    /** Cancella tutto, file compreso: niente file vuoto a ricordare che c'era qualcosa. Da thread di fondo. */
    public static boolean clear() {
        try {
            final File file = file();
            return file == null || !file.exists() || file.delete();
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /* ==================================================================================== lettura */

    /** Le segnalazioni, dalla più recente. Lista modificabile, sempre non null. Da thread di fondo. */
    public static List<Report> all() {
        final ArrayList<Report> out = new ArrayList<>();
        final File file = file();
        if (file == null || !file.exists()) {
            return out;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(file), UTF8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().length() == 0) {
                    continue;
                }
                final Report report = parse(line);
                if (report != null) {
                    out.add(report);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            close(reader);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.id, a.id));
        return out;
    }

    /** Quante segnalazioni ci sono: per il valore della riga nelle impostazioni. Da thread di fondo. */
    public static int count() {
        return all().size();
    }

    /* ==================================================================================== supporto */

    /** Ripiego di {@link Report#when()} quando il formattatore dell'app non è pronto. */
    private static String stamp(long millis) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date(millis));
    }

    static File file() {
        try {
            final File dir = ApplicationLoader.getFilesDirFixed();
            return dir == null ? null : new File(dir, FILE);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Riscrive il file intero. Si passa da un file temporaneo e si rinomina: un'interruzione a metà scrittura
     * lascerebbe l'archivio troncato, e con esso perderemmo segnalazioni che l'utente crede registrate.
     */
    private static boolean write(List<Report> reports) {
        final File file = file();
        if (file == null) {
            return false;
        }
        final File tmp = new File(file.getParentFile(), FILE + ".tmp");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new FileOutputStream(tmp, false), UTF8);
            for (Report report : reports) {
                writer.write(serialize(report));
                writer.write("\n");
            }
            writer.flush();
            close(writer);
            writer = null;
            // rename(2) su Linux sovrascrive il bersaglio: si prova PRIMA così, perché cancellare e poi
            // rinominare lascia una finestra in cui un rename fallito ha già distrutto l'archivio.
            if (tmp.renameTo(file)) {
                return true;
            }
            return file.exists() && file.delete() && tmp.renameTo(file);
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        } finally {
            close(writer);
            try {
                if (tmp.exists()) {
                    tmp.delete();
                }
            } catch (Throwable ignore) {
            }
        }
    }

    private static String serialize(Report report) {
        try {
            final JSONObject json = new JSONObject();
            json.put("id", report.id);
            json.put("provider", report.provider);
            json.put("model", report.model);
            json.put("task", report.taskKey);
            json.put("reason", report.reason);
            json.put("comment", report.comment);
            json.put("text", report.text);
            // JSONObject.toString() non emette ritorni a capo: una segnalazione resta UNA riga
            return json.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return "{}";
        }
    }

    private static Report parse(String line) {
        try {
            final JSONObject json = new JSONObject(line);
            final long id = json.optLong("id", 0);
            if (id <= 0) {
                return null;
            }
            return new Report(id,
                    json.optString("provider", ""),
                    json.optString("model", ""),
                    json.optString("task", ""),
                    json.optInt("reason", REASON_OFFENSIVE),
                    json.optString("comment", ""),
                    json.optString("text", ""));
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        final String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "...";
    }

    private static void close(java.io.Closeable c) {
        try {
            if (c != null) {
                c.close();
            }
        } catch (Throwable ignore) {
        }
    }
}

/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import android.text.TextUtils;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Costruisce le richieste delle QUATTRO funzioni dell'assistente, e nient'altro:
 * <ol>
 * <li>{@link #summary} — riassunto dell'arretrato (i non letti, oppure le ultime N ore);</li>
 * <li>{@link #translate} — traduzione di un messaggio;</li>
 * <li>{@link #draft} — bozza di risposta, che finisce NEL CAMPO DI SCRITTURA (vedi {@link AiEntry});</li>
 * <li>{@link #search} — ricerca nella cronologia caricata con una domanda in parole normali.</li>
 * </ol>
 *
 * <p><b>L'invariante non è "ogni richiesta è oscurata" ma: il testo che ESCE dal dispositivo è sempre
 * oscurato.</b> Ogni funzione prende la destinazione ({@link #LOCAL} o {@link #NETWORK}); con {@link #NETWORK}
 * l'oscuramento è l'ultimo atto della costruzione, quindi non esiste un percorso che lo salti, e il payload nasce
 * con {@link AiPayload#redacted} vero. Con {@link #LOCAL} il Redactor si salta e le frasi del prompt che spiegano
 * i segnaposto cadono: non c'è nessun destinatario da cui proteggersi, e [PHONE] o [CODE] in una finestra da 4000
 * token sono rumore che peggiora il risultato. Perché quella scelta non possa diventare una falla,
 * {@link HttpAiProvider#complete} rifiuta un payload non oscurato: il controllo sta sul percorso di uscita.</p>
 *
 * <p><b>Si oscura PRIMA di tagliare</b>, mai il contrario. Tagliare per primo spezza un identificatore a cavallo
 * del limite e ne lascia uscire i pezzi: un IBAN tagliato a metà esce come "IT60 X0..." e una carta troncata
 * scende sotto le 13 cifre minime della regola CARD, quindi nessuna regola la riconosce più. I segnaposto sono
 * più corti dei valori che sostituiscono, quindi invertire l'ordine non sfonda i tetti.</p>
 *
 * <p>Limiti dichiarati: la cronologia è quella CARICATA nella chat aperta (quello che si è scorso), non l'archivio
 * completo sul server; e poiché gli identificatori sono oscurati, una domanda del tipo "qual era il mio IBAN" non
 * può essere soddisfatta — per scelta.</p>
 */
public final class AiTasks {

    /** Tetti: una richiesta non deve diventare un invio di massa della chat a un servizio esterno. */
    private static final int MAX_MESSAGES = 120;
    private static final int MAX_CHARS = 8000;
    private static final int MAX_MESSAGE_CHARS = 1200;

    /** Destinazione del testo: sul dispositivo (niente esce) oppure a un fornitore di rete. */
    public static final int LOCAL = 0;
    public static final int NETWORK = 1;

    /** Scelte di intervallo per il riassunto: non letti, poi ore. */
    public static final int RANGE_UNREAD = 0;
    public static final int[] RANGE_HOURS = {3, 12, 24, 72};

    private AiTasks() {
    }

    /* ==================================================================================== funzioni */

    /**
     * Riassunto dell'arretrato: i messaggi oltre il segno di lettura del server, oppure quelli delle ultime N ore.
     *
     * @param unreadCount ripiego quando il segno di lettura non si conosce: quanti messaggi prendere dalla coda
     * @param hours       se &gt; 0, prendere invece i messaggi delle ultime N ore e ignorare il segno di lettura
     */
    public static AiPayload summary(int account, long dialogId, List<MessageObject> loaded, int unreadCount, int hours, int destination) {
        List<MessageObject> picked;
        if (hours > 0) {
            picked = pick(account, dialogId, loaded, MAX_MESSAGES, hours);
        } else {
            // i non letti per davvero: tutto quello che sta oltre il segno di lettura del server. Se il segno non
            // si sa (dialogo non in cache), si ripiega sulla coda della cronologia caricata.
            picked = pick(account, dialogId, loaded, MAX_MESSAGES, 0, readMaxId(account, dialogId));
            if (picked.isEmpty()) {
                picked = pick(account, dialogId, loaded, unreadCount, 0);
            }
        }
        if (picked.isEmpty()) {
            return null;
        }
        final Redactor.Result body = redactedTranscript(account, picked, destination);
        final String system = "You summarise a chat backlog for the person who was away. "
                + "Write a short summary in " + language() + ": the topics, the decisions, and anything addressed to the reader that needs an answer. "
                + "Use short bullet points, no preamble, no closing remarks."
                + placeholders(destination, "Some values appear as placeholders like [PHONE] or [CODE] because they were removed before sending: "
                + "mention that something was there, never invent the value.");
        return new AiPayload(system, body.text, 700, body.count, picked.size(), destination == NETWORK);
    }

    /** Traduzione di un messaggio nella lingua dell'app. */
    public static AiPayload translate(String text, int destination) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        // oscurare PRIMA di tagliare: al contrario una carta a cavallo del limite uscirebbe a pezzi
        final Redactor.Result body = redactThenCut(text.trim(), MAX_MESSAGE_CHARS, destination);
        final String system = "Translate the message below into " + language() + ". "
                + "Return the translation only: no explanation, no quotes, no notes. "
                + "If it is already in that language, return it unchanged."
                + placeholders(destination, "Leave placeholders like [PHONE] or [LINK] exactly as they are.");
        return new AiPayload(system, body.text, 1000, body.count, 1, destination == NETWORK);
    }

    /**
     * Bozza di risposta. Il prompt dice al modello che il testo finirà in un campo di scrittura perché la persona
     * lo corregga: l'app NON invia niente da sé, in nessun caso.
     */
    public static AiPayload draft(int account, long dialogId, List<MessageObject> loaded, String hint, int destination) {
        return draft(account, dialogId, loaded, hint, null, destination);
    }

    /**
     * Bozza di risposta, eventualmente a un messaggio preciso.
     *
     * @param hint    cosa l'utente vuole dire (facoltativo)
     * @param replyTo testo del messaggio a cui si risponde, quando la bozza nasce dal menu di quel messaggio
     */
    public static AiPayload draft(int account, long dialogId, List<MessageObject> loaded, String hint, String replyTo, int destination) {
        final List<MessageObject> picked = pick(account, dialogId, loaded, 20, 0);
        if (picked.isEmpty() && TextUtils.isEmpty(replyTo)) {
            return null;
        }
        final Redactor.Result tr = redactedTranscript(account, picked, destination);
        int redactions = tr.count;
        final StringBuilder sb = new StringBuilder(tr.text);
        if (!TextUtils.isEmpty(replyTo)) {
            final Redactor.Result reply = redactThenCut(oneLine(replyTo), MAX_MESSAGE_CHARS, destination);
            redactions += reply.count;
            sb.append("\n\n--\n").append("The reply is to this message: ").append(reply.text);
        }
        if (!TextUtils.isEmpty(hint)) {
            final Redactor.Result what = redactThenCut(hint.trim(), 400, destination);
            redactions += what.count;
            sb.append("\n\n--\n").append("What I want to say: ").append(what.text);
        }
        final Redactor.Result body = new Redactor.Result(sb.toString(), redactions);
        final String system = "You draft a reply that the person will read, edit and send themselves: "
                + "it goes into their message box, it is never sent automatically. "
                + "Write it in " + language() + ", in the voice of the lines marked \"" + meLabel() + "\", "
                + "one or two sentences unless the conversation clearly needs more. "
                + "Return the reply text only: no greeting of your own, no explanation, no quotes."
                + placeholders(destination, "Never fill in a placeholder like [PHONE] or [IBAN]: if the reply needs one, leave the placeholder in place.");
        return new AiPayload(system, body.text, 300, body.count, picked.size(), destination == NETWORK);
    }

    /**
     * Ricerca nella cronologia caricata con una domanda in parole normali.
     *
     * <p>La domanda sta nel prompt di sistema e non nel corpo: è un'istruzione, non contenuto della chat, e
     * soprattutto il corpo va spezzato in blocchi quando il modello sul dispositivo non ce lo fa stare tutto
     * (vedi {@link OnDeviceProvider}). Una domanda finita nel primo blocco e non negli altri lascerebbe il
     * modello a indovinare che cosa sta cercando.</p>
     */
    public static AiPayload search(int account, long dialogId, List<MessageObject> loaded, String question, int destination) {
        if (TextUtils.isEmpty(question)) {
            return null;
        }
        final List<MessageObject> picked = pick(account, dialogId, loaded, MAX_MESSAGES, 0);
        if (picked.isEmpty()) {
            return null;
        }
        final Redactor.Result ask = redactThenCut(question.trim(), 300, destination);
        final Redactor.Result tr = redactedTranscript(account, picked, destination);
        final String system = "Answer this question using only the chat excerpt below: " + ask.text + "\n"
                + "Reply in " + language() + ", briefly, and quote the line you are relying on with its date. "
                + "If the excerpt does not contain the answer, say so plainly instead of guessing."
                + placeholders(destination, "Values shown as [PHONE], [CODE], [IBAN] and so on were removed before sending: say where they were, never reconstruct them.");
        return new AiPayload(system, tr.text, 700, ask.count + tr.count, picked.size(), destination == NETWORK);
    }

    /** Le frasi del prompt sui segnaposto: hanno senso solo se i segnaposto ci sono, cioè verso la rete. */
    private static String placeholders(int destination, String sentence) {
        return destination == NETWORK ? " " + sentence : "";
    }

    /* =================================================================================== selezione */

    /**
     * I messaggi da mandare, dal più vecchio al più recente. Filtra la chat corrente (la lista caricata può
     * contenere anche la chat unita), scarta i messaggi di servizio e quelli senza testo, e taglia ai tetti.
     */
    private static List<MessageObject> pick(int account, long dialogId, List<MessageObject> loaded, int count, int hours) {
        return pick(account, dialogId, loaded, count, hours, 0);
    }

    /** @param afterId se &gt; 0, solo i messaggi con id maggiore (il segno di lettura) */
    private static List<MessageObject> pick(int account, long dialogId, List<MessageObject> loaded, int count, int hours, int afterId) {
        final ArrayList<MessageObject> out = new ArrayList<>();
        if (loaded == null || loaded.isEmpty()) {
            return out;
        }
        final int since = hours > 0 ? (int) (System.currentTimeMillis() / 1000L) - hours * 3600 : 0;
        // copia difensiva: la lista di ChatActivity vive sul thread UI e può cambiare sotto di noi
        final ArrayList<MessageObject> all = new ArrayList<>(loaded);
        for (int i = 0; i < all.size(); i++) {
            final MessageObject m = all.get(i);
            if (m == null || m.messageOwner == null || m.messageOwner.action != null) {
                continue;
            }
            if (dialogId != 0 && m.getDialogId() != dialogId) {
                continue;
            }
            if (TextUtils.isEmpty(m.messageOwner.message)) {
                continue;
            }
            if (since > 0 && m.messageOwner.date < since) {
                continue;
            }
            if (afterId > 0 && m.getId() <= afterId) {
                continue;
            }
            out.add(m);
        }
        Collections.sort(out, new Comparator<MessageObject>() {
            @Override
            public int compare(MessageObject a, MessageObject b) {
                if (a.messageOwner.date != b.messageOwner.date) {
                    return a.messageOwner.date < b.messageOwner.date ? -1 : 1;
                }
                return Integer.compare(a.getId(), b.getId());
            }
        });
        final int limit = Math.min(count > 0 ? count : MAX_MESSAGES, MAX_MESSAGES);
        while (out.size() > limit) {
            out.remove(0); // si tiene la coda: l'arretrato è ciò che è arrivato per ultimo
        }
        return out;
    }

    /**
     * "15/03 09:12 Nome: testo", una riga per messaggio, dal più vecchio al più recente, GIÀ OSCURATA.
     *
     * <p>Quando si supera {@link #MAX_CHARS} si scartano le righe PIÙ VECCHIE, non le più recenti: l'arretrato è
     * ciò che è arrivato per ultimo, e troncare dalla parte sbagliata darebbe un riassunto di quello che l'utente
     * ha già letto.</p>
     *
     * <p>Ogni messaggio passa da {@link Redactor} PRIMA del taglio a {@link #MAX_MESSAGE_CHARS} (vedi il javadoc
     * di classe), e alla fine c'è una passata sul testo assemblato per quello che non sta nei messaggi: un nome
     * di autore può essere a sua volta un numero di telefono. {@link Redactor#apply} è idempotente, quindi la
     * seconda passata non riconta i segnaposto già messi dalla prima.</p>
     */
    private static Redactor.Result redactedTranscript(int account, List<MessageObject> messages, int destination) {
        final ArrayList<String> lines = new ArrayList<>(messages.size());
        int budget = MAX_CHARS;
        int redactions = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            final MessageObject m = messages.get(i);
            final Redactor.Result text = redactThenCut(oneLine(m.messageOwner.message), MAX_MESSAGE_CHARS, destination);
            final String line = stamp(m) + " " + author(account, m) + ": " + text.text;
            if (line.length() + 1 > budget) {
                break;
            }
            budget -= line.length() + 1;
            redactions += text.count;
            lines.add(line);
        }
        final StringBuilder sb = new StringBuilder(MAX_CHARS - budget);
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        if (destination == LOCAL) {
            return new Redactor.Result(sb.toString(), 0);
        }
        final Redactor.Result whole = Redactor.apply(sb.toString());
        return new Redactor.Result(whole.text, redactions + whole.count);
    }

    /**
     * Oscura e POI taglia: l'ordine inverso lascerebbe uscire a pezzi un identificatore a cavallo del limite.
     * Con destinazione {@link #LOCAL} non si oscura: niente esce, e i segnaposto costerebbero solo qualità.
     */
    private static Redactor.Result redactThenCut(String text, int max, int destination) {
        if (destination == LOCAL) {
            return new Redactor.Result(cut(text == null ? "" : text, max), 0);
        }
        final Redactor.Result redacted = Redactor.apply(text == null ? "" : text);
        return new Redactor.Result(cut(redacted.text, max), redacted.count);
    }

    /** Segno di lettura della chat: oltre questo id i messaggi sono arretrato. 0 se non si sa. */
    private static int readMaxId(int account, long dialogId) {
        try {
            final TLRPC.Dialog dialog = MessagesController.getInstance(account).getDialog(dialogId);
            return dialog != null ? Math.max(0, dialog.read_inbox_max_id) : 0;
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private static String stamp(MessageObject m) {
        final long millis = (long) m.messageOwner.date * 1000L;
        return new java.text.SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new java.util.Date(millis));
    }

    /** Nome dell'autore. I messaggi dell'utente sono marcati con un'etichetta fissa: il modello deve distinguerli. */
    private static String author(int account, MessageObject m) {
        if (m.isOut()) {
            return meLabel();
        }
        try {
            final long senderId = m.getSenderId();
            if (senderId > 0) {
                final TLRPC.User user = MessagesController.getInstance(account).getUser(senderId);
                final String name = UserObject.getFirstName(user);
                if (!TextUtils.isEmpty(name)) {
                    return oneLine(name);
                }
            } else if (senderId < 0) {
                final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-senderId);
                if (chat != null && !TextUtils.isEmpty(chat.title)) {
                    return oneLine(chat.title);
                }
            }
        } catch (Throwable ignore) {
        }
        return "?";
    }

    /** Etichetta dei messaggi dell'utente: fissa e non tradotta, così il prompt può riferirsi a essa. */
    private static String meLabel() {
        return "Me";
    }

    private static String oneLine(CharSequence text) {
        if (text == null) {
            return "";
        }
        return text.toString().replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String cut(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    /** La lingua in cui il modello deve rispondere: quella dell'app, in inglese per il prompt. */
    private static String language() {
        try {
            final Locale locale = LocaleController.getInstance().getCurrentLocale();
            if (locale != null) {
                final String name = locale.getDisplayLanguage(Locale.ENGLISH);
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
            }
        } catch (Throwable ignore) {
        }
        return "English";
    }
}

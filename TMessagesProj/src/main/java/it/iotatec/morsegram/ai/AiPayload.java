/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

/**
 * Il testo esatto che il modello riceverà, e se è lecito che esca dal dispositivo.
 *
 * <p>Esiste per una ragione sola: l'anteprima della prima attivazione deve mostrare QUESTO, non una
 * ricostruzione. Chi costruisce la richiesta ({@link AiTasks}) produce un {@code AiPayload}; l'anteprima ne mostra
 * {@link #preview()}; {@link HttpAiProvider} manda gli stessi {@link #system} e {@link #user} al fornitore. Non
 * c'è un secondo percorso che possa divergere da quello mostrato.</p>
 *
 * <p>{@link #redacted} porta la garanzia fino al punto di uscita. L'invariante del progetto non è "ogni payload è
 * oscurato" — con il modello sul dispositivo l'oscuramento non protegge nessuno e peggiora il risultato — ma
 * questa: <b>il testo che esce dal dispositivo è sempre oscurato</b>. {@link AiTasks} oscura quando la
 * destinazione è la rete e lo dichiara qui; {@link HttpAiProvider#complete} rifiuta un payload con
 * {@code redacted == false}. Un payload per il modello locale non può essere spedito per sbaglio.</p>
 */
public final class AiPayload {

    /** Istruzioni al modello (prompt di sistema). */
    public final String system;
    /** Contenuto: la conversazione o il messaggio. */
    public final String user;
    /** Tetto alla lunghezza della risposta. */
    public final int maxTokens;
    /** Quante sostituzioni ha fatto il Redactor: va detto nell'anteprima. 0 anche quando non è stato chiamato. */
    public final int redactions;
    /** Quanti messaggi sono finiti nella richiesta (0 per la traduzione di un singolo messaggio). */
    public final int messages;
    /**
     * Vero se il testo è passato da {@link Redactor} e può quindi uscire dal dispositivo. Falso = elaborazione
     * sul dispositivo e basta: nessun fornitore di rete lo accetta.
     */
    public final boolean redacted;

    public AiPayload(String system, String user, int maxTokens, int redactions, int messages, boolean redacted) {
        this.system = system == null ? "" : system;
        this.user = user == null ? "" : user;
        this.maxTokens = maxTokens;
        this.redactions = redactions;
        this.messages = messages;
        this.redacted = redacted;
    }

    /** Il testo come lo vedrebbe il fornitore, nell'ordine in cui lo riceve. */
    public String preview() {
        if (system.length() == 0) {
            return user;
        }
        return system + "\n\n" + user;
    }

    public boolean isEmpty() {
        return user.trim().length() == 0;
    }
}

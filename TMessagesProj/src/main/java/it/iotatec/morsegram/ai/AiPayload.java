/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

/**
 * Il testo esatto che uscirà dal dispositivo, già oscurato da {@link Redactor}.
 *
 * <p>Esiste per una ragione sola: l'anteprima della prima attivazione deve mostrare QUESTO, non una
 * ricostruzione. Chi costruisce la richiesta ({@link AiTasks}) produce un {@code AiPayload}; l'anteprima ne mostra
 * {@link #preview()}; {@link AiClient} manda gli stessi {@link #system} e {@link #user} al fornitore. Non c'è un
 * secondo percorso che possa divergere da quello mostrato.</p>
 */
public final class AiPayload {

    /** Istruzioni al modello (prompt di sistema). */
    public final String system;
    /** Contenuto: la conversazione o il messaggio, oscurato. */
    public final String user;
    /** Tetto alla lunghezza della risposta. */
    public final int maxTokens;
    /** Quante sostituzioni ha fatto il Redactor: va detto nell'anteprima. */
    public final int redactions;
    /** Quanti messaggi sono finiti nella richiesta (0 per la traduzione di un singolo messaggio). */
    public final int messages;

    public AiPayload(String system, String user, int maxTokens, int redactions, int messages) {
        this.system = system == null ? "" : system;
        this.user = user == null ? "" : user;
        this.maxTokens = maxTokens;
        this.redactions = redactions;
        this.messages = messages;
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

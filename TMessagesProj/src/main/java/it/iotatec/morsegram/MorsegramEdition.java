package it.iotatec.morsegram;

import org.telegram.messenger.BuildConfig;

/**
 * Le due edizioni di Morsegram, e cosa puo' vivere solo in una.
 *
 * <p>Dallo stesso codice escono due app diverse:
 * <ul>
 *   <li><b>sideload</b> — compilata con {@code -PMORSEGRAM_SIDELOAD}, distribuita come APK dalle
 *       release su GitHub. Qui l'unico limite sono i termini delle API di Telegram.</li>
 *   <li><b>Google Play</b> — compilata senza quel flag. Qui valgono anche le policy dello Store,
 *       che sono piu' strette e la cui violazione fa rimuovere l'app.</li>
 * </ul>
 *
 * <p><b>Cosa puo' stare solo nel sideload, e perche':</b>
 * <ul>
 *   <li><i>Aggiornamento in-app</i> — un'app pubblicata su Play non puo' aggiornarsi da sola
 *       (policy Device and Network Abuse).</li>
 *   <li><i>Sblocchi locali delle funzioni a pagamento di Telegram</i> — sbloccare le funzioni a
 *       pagamento di un altro servizio viola le policy di Play su comportamento ingannevole e
 *       proprieta' intellettuale.</li>
 * </ul>
 *
 * <p><b>Cosa NON puo' stare in nessuna delle due</b>, perche' lo vietano i termini delle API di
 * Telegram e il prezzo e' l'account dell'utente, non una multa: nascondere le conferme di lettura o
 * fingersi offline mentre si legge; rimuovere o nascondere i messaggi sponsorizzati; salvare i
 * messaggi che gli altri hanno cancellato; inviare messaggi senza che sia l'utente a premere invio.
 *
 * <p>Una funzione dietro {@link #sideloadOnly()} deve essere <b>inerte</b> nell'altra edizione, non
 * solo nascosta: il campo che la governa e' una costante di compilazione, quindi R8 elimina i rami
 * morti e nel pacchetto per Play quel codice non viene nemmeno eseguito.
 */
public final class MorsegramEdition {

    private MorsegramEdition() {
    }

    /** Vero solo nell'APK distribuito fuori da Google Play. */
    public static boolean sideloadOnly() {
        return BuildConfig.MORSEGRAM_SIDELOAD;
    }

    /** Nome dell'edizione, per la schermata Info. */
    public static String label() {
        return sideloadOnly() ? "sideload" : "Play";
    }
}

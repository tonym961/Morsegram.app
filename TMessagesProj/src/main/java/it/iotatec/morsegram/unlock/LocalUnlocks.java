/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.unlock;

import org.telegram.messenger.UserConfig;

import it.iotatec.morsegram.MorsegramEdition;

/**
 * I <b>soli</b> vincoli dell'abbonamento Telegram che decide il client da solo, e che quindi si possono togliere
 * senza mentire a nessuno.
 *
 * <p><b>Cosa NON fa questa classe.</b> Non tocca {@code UserConfig.isPremium()}, non scrive il flag premium
 * dell'utente e non falsifica nessuna risposta del server. Un'app che si crede abbonata chiede al server cose che
 * il server rifiuta e l'utente vede errori: e' il modo in cui lo fanno gli altri fork, ed e' sbagliato. Qui si
 * interviene su <b>ogni singolo vincolo locale, uno per uno</b>, con un aggancio di una riga nel punto dove il
 * client — non il server — decide.
 *
 * <p><b>Perche' quasi niente e' qui dentro.</b> Censimento del 22/10/2026 su 356 chiamate a {@code isPremium()} in
 * 105 file: la superficie a pagamento di questo client e' quasi tutta decisa dai server di Telegram (caricamenti
 * oltre 2 GB, trascrizione sul server, reazioni multiple, cartelle sincronizzate, storie, didascalie e bio lunghe,
 * nomi utente e link pubblici in piu', privacy a pagamento, colori e stato emoji, checklist, Business). Spostare
 * quei flag produrrebbe solo errori di rete: {@code PREMIUM_ACCOUNT_REQUIRED}, {@code REACTIONS_TOO_MANY},
 * {@code CHANNELS_ADMIN_PUBLIC_TOO_MUCH}, {@code USERNAMES_ACTIVE_TOO_MUCH}, {@code PRIVACY_PREMIUM_REQUIRED}.
 * Restano due vincoli davvero locali, che sono quelli agganciati qui.
 *
 * <p><b>Solo edizione sideload.</b> Tutto passa da {@link #enabled()}, che e' {@link MorsegramEdition#sideloadOnly()},
 * cioe' una costante di compilazione: nel pacchetto per Google Play R8 elimina i rami morti e il codice non esiste.
 * Sbloccare le funzioni a pagamento di un altro servizio viola le policy di Play (comportamento ingannevole e
 * proprieta' intellettuale) e farebbe rimuovere l'app: vedi il javadoc di {@link MorsegramEdition}.
 *
 * <p><b>Le due sostituzioni locali del fork non stanno qui</b> e non sono sblocchi: {@code LocalPins} rimpiazza il
 * pin oltre il limite con un ordinamento nostro e {@code voice.LocalTranscriber} rimpiazza la trascrizione del
 * server con il riconoscitore vocale di Android. E' il pattern che si ripaga; questa classe e' il resto, ed e'
 * piccola per un motivo.
 *
 * <p>L'elenco mostrato all'utente sta nella sezione "Sblocchi locali" di
 * {@code it.iotatec.morsegram.ui.MorsegramSettingsActivity}, visibile nella sola edizione sideload.
 */
public final class LocalUnlocks {

    private LocalUnlocks() {
    }

    /**
     * Interruttore unico: costante di compilazione, quindi gli sblocchi sono <b>inerti</b> (non solo nascosti)
     * nell'edizione per Google Play.
     */
    public static boolean enabled() {
        return MorsegramEdition.sideloadOnly();
    }

    /**
     * Quarto slot nel commutatore degli account: upstream ne concede
     * {@code MAX_ACCOUNT_DEFAULT_COUNT} = 3 a chi non paga, mentre gli slot esistenti sono
     * {@code UserConfig#MAX_ACCOUNT_COUNT} = 4.
     *
     * <p>Vincolo locale al 100%: ogni account e' un login indipendente e il server non sa quante sessioni stanno
     * dentro la stessa installazione. {@code MAX_ACCOUNT_COUNT} resta 4, quindi nessun array cambia dimensione.
     *
     * <p>Il riferimento a {@code UserConfig.MAX_ACCOUNT_COUNT} e' una costante di compilazione (javac la copia
     * come letterale): non crea una dipendenza di inizializzazione verso {@code UserConfig}, che e' proprio la
     * classe che chiama questo metodo dal suo initializer.
     *
     * @param upstreamDefault il valore di upstream, restituito tale e quale nell'edizione per Play
     */
    public static int accountSlots(int upstreamDefault) {
        return enabled() ? UserConfig.MAX_ACCOUNT_COUNT : upstreamDefault;
    }

    /** Vero quando il quarto slot e' attivo: serve a spegnere i richiami "abbonati per avere 4 account". */
    public static boolean accountSlotsUnlocked() {
        return enabled();
    }

    /**
     * Barra "Traduci" e traduzione automatica di tutta la conversazione
     * ({@code TranslateController#isFeatureAvailable}).
     *
     * <p>Il gate e' del client: la traduzione del <b>singolo</b> messaggio passa gia' dalla stessa
     * {@code messages.translateText} anche senza abbonamento, e nel codice non esiste nessuna gestione di errore
     * premium per quella richiesta.
     *
     * <p><b>Limite che resta, ed e' scritto nelle impostazioni:</b> tradurre una conversazione intera moltiplica le
     * richieste e il server puo' imporre un {@code FLOOD_WAIT} (per funzioni sorelle esistono gia'
     * {@code AICOMPOSE_FLOOD_PREMIUM} e {@code SUMMARY_FLOOD_PREMIUM}). E' un degrado, non un rifiuto: la
     * traduzione rallenta o si ferma per un po', il resto della chat continua a funzionare. Non verificato a
     * runtime: sull'emulatore non si puo' accedere a un account.
     */
    public static boolean chatTranslate() {
        return enabled();
    }
}

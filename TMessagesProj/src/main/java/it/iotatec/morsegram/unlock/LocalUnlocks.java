/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.unlock;

import android.content.SharedPreferences;

import org.telegram.messenger.UserConfig;

import it.iotatec.morsegram.MorsegramEdition;
import it.iotatec.morsegram.MorsegramPrefs;

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
 * <p><b>Perche' qui dentro c'e' cosi' poco rispetto alla superficie a pagamento.</b> Censimento completo del
 * 22/10/2026 (tre aree, 393 punti letti uno per uno su 359 chiamate a {@code isPremium()}): la superficie a
 * pagamento di questo client e' quasi tutta decisa dai server di Telegram — caricamenti oltre 2 GB, trascrizione
 * sul server, reazioni multiple, emoji animate (il flag {@code free} e' del server), effetti dei messaggi,
 * creazione di cartelle oltre il limite, tag delle cartelle e dei messaggi salvati, storie, didascalie, biografie e
 * messaggi lunghi, nomi utente e link pubblici in piu', privacy a pagamento, colore del nome e stato emoji,
 * checklist, Business, potenziamento dei canali, ricerca nei post pubblici. Spostare quei flag produrrebbe solo
 * errori di rete: {@code PREMIUM_ACCOUNT_REQUIRED}, {@code REACTIONS_TOO_MANY}, {@code MESSAGE_TOO_LONG},
 * {@code MEDIA_CAPTION_TOO_LONG}, {@code DIALOG_FILTERS_TOO_MUCH}, {@code CHANNELS_ADMIN_PUBLIC_TOO_MUCH},
 * {@code USERNAMES_ACTIVE_TOO_MUCH}, {@code PRIVACY_PREMIUM_REQUIRED}. Oppure, peggio, interruttori che si
 * spengono da soli alla prima risincronizzazione. Quello che resta e' agganciato qui, voce per voce.
 *
 * <p><b>Cosa e' stato escluso per scelta, non per ignoranza</b> (il censimento le classifica locali, ma non entrano):
 * <ul>
 *   <li><i>Icone alternative dell'app</i> ({@code LauncherIconController}) — sono artwork di Telegram, una si
 *       chiama perfino "Premium": metterle sul telefono sotto il nome Morsegram e' marchio altrui.</li>
 *   <li><i>Installazione di pacchetti di emoji a pagamento</i> e <i>copia di una emoji animata</i> — si otterrebbe
 *       l'ingombro senza la funzione, perche' l'uso dipende dal flag {@code free} del server.</li>
 *   <li><i>Voce "Potenzia" nel menu della chat</i> — la visibilita' e' locale, ma apre una porta chiusa.</li>
 *   <li><i>Richiami pubblicitari all'abbonamento</i> (bolla di trascrizione, bollettini sulla velocita',
 *       contatore {@code moreemojihint}) — togliere un annuncio non e' sbloccare una funzione, ed e' una decisione
 *       a parte, non da infilare qui.</li>
 *   <li><i>Posizione della scheda "Tutte le chat"</i> e <i>qualita' piena delle storie</i> — classificate locali in
 *       una sola delle tre aree e incerte nell'altra: nel caso peggiore l'ordine torna indietro da solo e la storia
 *       non parte affatto. Vanno provate su un account vero, cosa impossibile sull'emulatore.</li>
 * </ul>
 *
 * <p><b>Solo edizione sideload.</b> Tutto passa da {@link #enabled()}, che e' {@link MorsegramEdition#sideloadOnly()},
 * cioe' una costante di compilazione: nel pacchetto per Google Play R8 elimina i rami morti e il codice non esiste,
 * nemmeno le chiavi di preferenza di questa classe e nemmeno i testi (che per lo stesso motivo stanno in
 * {@code TMessagesProj_App/src/sideload/res/}, fuori da {@code src/main}: gli asset di localizzazione non passano da
 * R8). Sbloccare le funzioni a pagamento di un altro servizio viola le policy di Play (comportamento ingannevole e
 * proprieta' intellettuale) e farebbe rimuovere l'app: vedi il javadoc di {@link MorsegramEdition}.
 *
 * <p><b>Accesi di default, spegnibili uno per uno.</b> Questi sblocchi sono il motivo per cui l'edizione sideload
 * esiste, quindi nascono attivi; chi preferisce l'aspetto originale li spegne singolarmente dalla sezione
 * "Sblocchi locali" di {@code it.iotatec.morsegram.ui.MorsegramSettingsActivity}, che elenca ogni voce con una riga
 * che dice cosa fa. L'unica voce senza interruttore e' il quarto slot account, perche' il numero di slot si decide
 * una volta all'avvio ({@code UserConfig.MAX_ACCOUNT_DEFAULT_COUNT} e' un campo statico finale).
 *
 * <p><b>Le due sostituzioni locali del fork non stanno qui</b> e non sono sblocchi: {@code LocalPins} rimpiazza il
 * pin oltre il limite con un ordinamento nostro e {@code voice.LocalTranscriber} rimpiazza la trascrizione del
 * server con il riconoscitore vocale di Android. E' il pattern che si ripaga.
 *
 * <p><b>Niente di tutto questo e' provato a runtime:</b> sull'emulatore Android 36 non si puo' accedere a un
 * account Telegram, quindi la classificazione viene dalla lettura del codice e del flusso successivo. Dove resta un
 * residuo, e' scritto nel javadoc del metodo e nella riga che l'utente legge nelle impostazioni.
 */
public final class LocalUnlocks {

    /* ---------------------------------------------------------------- identificatori delle voci con interruttore */

    /** Barra "Traduci" e traduzione di tutta la conversazione. */
    public static final int ID_CHAT_TRANSLATE = 0;
    /** Sticker premium: invio, menu, lucchetto sulla cella, suggerimenti, effetto sul proprio messaggio. */
    public static final int ID_PREMIUM_STICKERS = 1;
    /** Riga delle categorie di ricerca nella tastiera emoji. */
    public static final int ID_EMOJI_CATEGORIES = 2;
    /** Cartelle gia' sincronizzate dal server che il client marca col lucchetto. */
    public static final int ID_LOCKED_FOLDERS = 3;
    /** Le dieci sfumature a pagamento del costruttore di avatar. */
    public static final int ID_AVATAR_GRADIENTS = 4;
    /** Sfumatura personalizzata (colori scelti dall'utente) del costruttore di avatar. */
    public static final int ID_AVATAR_CUSTOM_GRADIENT = 5;
    /** Link cliccabili nella nota privata su un contatto. */
    public static final int ID_NOTE_LINKS = 6;
    /** Link cliccabili nella biografia di un altro utente. */
    public static final int ID_BIO_LINKS = 7;
    /**
     * Quante voci con interruttore esistono. La sezione delle impostazioni le elenca <b>in questo ordine</b>
     * (prima la chat, poi la lista chat, poi il profilo), quindi gli identificatori sono anche l'ordine di lettura.
     * Non sono persistiti: su disco finiscono le chiavi di {@link #key(int)}, percio' riordinarli non perde le
     * scelte di chi ha spento qualcosa.
     */
    public static final int IDS = 8;

    /**
     * Il numero di slot account che upstream concede senza abbonamento: <b>lo stesso 3</b> che
     * {@code UserConfig.MAX_ACCOUNT_DEFAULT_COUNT} passa ad {@link #accountSlots(int)}. Se upstream lo cambia va
     * cambiato in entrambi i posti; qui serve solo a non scrivere un numero falso nel listino dei limiti
     * raddoppiati, quindi una divergenza e' cosmetica e non cambia il comportamento dell'app.
     */
    private static final int UPSTREAM_ACCOUNT_SLOTS = 3;

    private LocalUnlocks() {
    }

    /**
     * Interruttore unico dell'edizione: costante di compilazione, quindi gli sblocchi sono <b>inerti</b> (non solo
     * nascosti) nell'edizione per Google Play, dove R8 cancella i rami e con essi le chiavi qui sotto.
     */
    public static boolean enabled() {
        return MorsegramEdition.sideloadOnly();
    }

    /* ------------------------------------------------------------------------------- interruttori per singola voce */

    /**
     * Chiave di preferenza della voce. Volutamente <b>privata e scritta a mano dentro questo metodo</b>: cosi' le
     * stringhe vivono in un solo posto raggiungibile solo da {@link #on(int)}, che nell'edizione per Play e' codice
     * morto, e R8 le elimina dal dex insieme al metodo.
     */
    private static String key(int id) {
        switch (id) {
            case ID_PREMIUM_STICKERS: return "unlock.premiumStickers";
            case ID_EMOJI_CATEGORIES: return "unlock.emojiCategories";
            case ID_AVATAR_GRADIENTS: return "unlock.avatarGradients";
            case ID_AVATAR_CUSTOM_GRADIENT: return "unlock.avatarCustomGradient";
            case ID_LOCKED_FOLDERS: return "unlock.lockedFolders";
            case ID_NOTE_LINKS: return "unlock.noteLinks";
            case ID_BIO_LINKS: return "unlock.bioLinks";
            case ID_CHAT_TRANSLATE: return "unlock.chatTranslate";
            default: return null;
        }
    }

    /**
     * Stato della voce: <b>acceso se la preferenza non c'e'</b>, perche' questi sblocchi sono il motivo per cui
     * l'edizione sideload esiste. Letta da percorsi caldi (binding delle celle sticker, filtro dei suggerimenti),
     * quindi passa per {@link MorsegramPrefs} che e' una mappa in memoria dopo il primo caricamento; niente I/O.
     *
     * <p>Nell'edizione per Play non viene mai chiamata: ogni metodo pubblico qui sotto e' {@code enabled() && on(..)}
     * e {@code enabled()} e' una costante falsa.
     */
    public static boolean on(int id) {
        final String key = key(id);
        return key == null || MorsegramPrefs.global().getBoolean(key, true);
    }

    /** Spegne o riaccende la voce. Chiamata solo dalla sezione "Sblocchi locali" delle impostazioni. */
    public static void set(int id, boolean value) {
        final String key = key(id);
        if (key != null) {
            final SharedPreferences prefs = MorsegramPrefs.global();
            if (value) {
                // il default e' acceso: togliere la chiave invece di scrivere true tiene il file pulito e fa
                // seguire automaticamente il default se un giorno cambiasse
                prefs.edit().remove(key).apply();
            } else {
                prefs.edit().putBoolean(key, false).apply();
            }
        }
    }

    /* --------------------------------------------------------------------------------------------- slot account */

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
     * classe che chiama questo metodo dal suo initializer. <b>Per lo stesso motivo questa voce non ha un
     * interruttore:</b> girerebbe dentro il class initializer di {@code UserConfig}, dove leggere le
     * {@code SharedPreferences} significherebbe toccare {@code ApplicationLoader} prima che ci sia un contesto.
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
     * Il numero da scrivere come limite <b>gratuito</b> nel listino "Limiti raddoppiati"
     * ({@code DoubledLimitsBottomSheet}), che e' un listino dei limiti di Telegram Premium e non dei nostri.
     *
     * <p>Serve perche' lo sblocco qui sopra porta {@code MAX_ACCOUNT_DEFAULT_COUNT} a 4 e quella riga finirebbe per
     * dire "4 su 4", cioe' niente. Non e' uno sblocco: e' la correzione di una sbavatura che abbiamo introdotto noi.
     */
    public static int accountSlotsListed(int upstreamDefault) {
        return enabled() ? UPSTREAM_ACCOUNT_SLOTS : upstreamDefault;
    }

    /* ----------------------------------------------------------------------------------------- voce per voce */

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
        return enabled() && on(ID_CHAT_TRANSLATE);
    }

    /**
     * Sticker premium (quelli con l'effetto a tutto schermo): si inviano, si tengono premuti, non portano il
     * lucchetto e compaiono fra i suggerimenti. Un solo interruttore per sette agganci, perche' le parti sono
     * inseparabili: sbloccare l'invio e lasciare il lucchetto, o sbloccare il pannello e non il foglio del
     * pacchetto, e' la bandiera accesa a meta' che rende fastidiosi i fork concorrenti.
     *
     * <p><b>Prova che il vincolo e' del client, non del server.</b> La premium-ita' di uno sticker non e' un campo
     * del protocollo: {@code TL_documentAttributeSticker} non ha nessun flag (a differenza di
     * {@code TL_documentAttributeCustomEmoji}, che ha {@code free}, ed e' per questo che le emoji animate restano
     * fuori). {@code MessageObject.isPremiumSticker} la deduce dalla sola presenza di un {@code video_thumbs} di
     * tipo {@code "f"} nei dati che il server ha gia' mandato, e in {@code SendMessagesHelper} non esiste nemmeno
     * una chiamata a {@code isPremium()}: l'invio e' una {@code messages.sendMedia} con un documento che il server
     * serve lui. Indizio in piu': un non abbonato puo' gia' oggi <i>inoltrare</i> un messaggio con uno sticker
     * premium, perche' sull'inoltro non c'e' alcun gate.
     *
     * <p><b>Residuo dichiarato, e scritto anche nelle impostazioni:</b> lo sticker si invia e si vede, ma
     * l'animazione a tutto schermo e' sospesa dal flag {@code nopremium} del media
     * ({@code TLRPC.MessageMediaDocument}), che nel client viene <b>solo letto</b> (due punti: {@code MessageObject}
     * e {@code ChatActivity}) e quindi lo scrive il server. Se il server lo imposta per i mittenti non abbonati,
     * l'effetto non parte per nessuno e l'aggancio in {@code ChatActivity} e' inerte; se non lo imposta, senza
     * quell'aggancio lo vedrebbero i destinatari e non il mittente, che e' peggio. In entrambi i casi lo sticker si
     * manda, e l'effetto si puo' comunque far partire col dito. Non verificabile sull'emulatore.
     */
    public static boolean premiumStickers() {
        return enabled() && on(ID_PREMIUM_STICKERS);
    }

    /**
     * La riga delle categorie di ricerca nella scheda emoji della tastiera (cuore, festa, animali...), che upstream
     * mostra ai soli abbonati mentre nella scheda sticker la mostra a tutti.
     *
     * <p>Vincolo locale: la riga e' alimentata da {@code StickerCategoriesListView} con
     * {@code CategoriesType.DEFAULT}, cioe' dalla stessa {@code messages.getEmojiGroups} che <b>oggi</b> serve la
     * riga della scheda sticker a chi non e' abbonato — quindi il dato e' dimostrato disponibile e non parte nessuna
     * richiesta nuova. Toccare una categoria chiama soltanto {@code emojiSearchAdapter.search(category.emojis)},
     * una ricerca in locale, e la ricerca delle emoji semplici funziona gia' per tutti (il percorso delle emoji
     * animate e' filtrato altrove e resta com'e').
     *
     * <p>Coerenza: fra i risultati possono comparire emoji animate col lucchetto, esattamente come succede oggi nel
     * resto del pannello. Non si introduce nessun lucchetto nuovo.
     */
    public static boolean emojiSearchCategories() {
        return enabled() && on(ID_EMOJI_CATEGORIES);
    }

    /**
     * Le dieci sfumature marcate a pagamento nel costruttore di avatar (emoji o sticker su sfondo sfumato).
     *
     * <p>Vincolo locale: la sfumatura e' disegnata in locale dentro il file che viene caricato, e nel pacchetto
     * inviato al server ({@code photos.uploadProfilePhoto} con {@code video_emoji_markup}) viaggia come semplice
     * lista di interi ARGB in {@code TL_videoSizeEmojiMarkup.background_colors} — <b>gli stessi campi, con lo stesso
     * numero di elementi</b>, che un non abbonato riempie gia' oggi con le sfumature gratuite (che hanno quattro
     * colori, mentre quelle a pagamento ne hanno due). Nessun punto del client prevede un errore premium su quella
     * richiesta.
     *
     * <p><b>Residuo dichiarato:</b> se il server confrontasse quei colori con una tavolozza fissa, il caricamento
     * dell'avatar fallirebbe in blocco con un errore generico. Il fatto che la forma della richiesta sia identica a
     * quella gia' accettata rende la cosa improbabile, ma non e' verificabile senza un account.
     */
    public static boolean avatarGradients() {
        return enabled() && on(ID_AVATAR_GRADIENTS);
    }

    /**
     * La pastiglia "Personalizza" del costruttore di avatar: sfumatura composta con i propri colori.
     *
     * <p>Interruttore separato da {@link #avatarGradients()} perche' e' una funzione distinta (si puo' volere una e
     * non l'altra) e perche' il residuo e' un filo piu' alto: qui i colori sono arbitrari e non appartengono a
     * nessuna tavolozza di Telegram, quindi se una validazione lato server esistesse sarebbe questo il caso che la
     * incontra. Resta il fatto che i colori gratuiti sono gia' quattro interi arbitrari dal punto di vista del
     * protocollo. Nessun marchio altrui: i colori sono dell'utente.
     */
    public static boolean avatarCustomGradient() {
        return enabled() && on(ID_AVATAR_CUSTOM_GRADIENT);
    }

    /**
     * Le cartelle di chat che il server ha <b>gia' sincronizzato</b> e che il client marca col lucchetto quando sono
     * piu' del limite gratuito ({@code MessagesController#lockFiltersInternal}): scheda apribile, riga modificabile,
     * nessun paywall al tocco.
     *
     * <p>Vincolo locale e dimostrabile: {@code MessagesStorage.checkLoadedRemoteFilters} carica e salva <b>tutte</b>
     * le cartelle che il server manda, senza applicare alcun limite, e il contenuto di una cartella e' calcolato in
     * locale da {@code DialogFilter.includesDialog} sulla lista di dialoghi gia' sincronizzata. Il server non ha mai
     * nascosto niente: era il client a chiudere la porta. Il campo {@code DialogFilter.locked} vive solo in memoria
     * (nessuna persistenza), quindi basta non accenderlo mai e i nove consumatori in {@code DialogsActivity} e
     * {@code FiltersSetupActivity} diventano inerti da soli, senza una riga in quei file.
     *
     * <p><b>Resta a pagamento, e giustamente, CREARE</b> una cartella oltre il limite: quel percorso manda una
     * richiesta che il server rifiuta con {@code DIALOG_FILTERS_TOO_MUCH}, e non lo si tocca.
     *
     * <p><b>Perimetro di utilita' onesto:</b> serve solo a chi ha piu' cartelle del limite gratuito, cioe' a chi ha
     * avuto l'abbonamento e lo ha perso o le ha create da un altro client. A chi non ne ha mai avute piu' di dieci
     * non cambia nulla. Non viene toccato il riordino che {@code updatePremium} fa della cartella predefinita: e' la
     * posizione della scheda "Tutte le chat", che e' un'altra voce e resta fuori perche' incerta.
     */
    public static boolean lockedFolders() {
        return enabled() && on(ID_LOCKED_FOLDERS);
    }

    /**
     * I link nella nota privata che l'utente ha scritto su un contatto restano cliccabili invece di essere spogliati
     * ({@code ProfileActivity}, riga della nota).
     *
     * <p>Vincolo locale al 100% e sul testo dell'utente stesso: {@code MessageObject.removeLinks} e' un filtro di
     * sola visualizzazione che butta via le entita' {@code TL_messageEntityUrl} e {@code TL_messageEntityTextUrl} da
     * una copia di un testo che e' <b>gia' tutto</b> in {@code userInfo.note}, scaricato dal server. Scrivere e
     * cancellare le note non e' a pagamento ({@code ContactAddActivity} non ha nessun controllo
     * sull'abbonamento), quindi non si crea nessuna aspettativa su un'operazione che poi fallisce. Nessuna richiesta
     * parte: il tocco apre il browser.
     */
    public static boolean profileNoteLinks() {
        return enabled() && on(ID_NOTE_LINKS);
    }

    /**
     * Gli indirizzi web nella biografia di un <b>altro</b> utente diventano toccabili ({@code ProfileActivity},
     * riga della biografia, terzo parametro {@code parseLinks} di {@code AboutLinkCell}).
     *
     * <p>Vincolo locale: il testo della biografia arriva dal server integro ({@code userInfo.about}, testo semplice);
     * {@code parseLinks} diventa {@code internalOnly = !parseLinks} in {@code AboutLinkCell} e scende fino ad
     * {@code AndroidUtilities.gatherLinks}, dove {@code internalOnly} scarta tutto cio' che non e' un link interno
     * {@code t.me}/{@code tg://}. Non si inventa niente: si smette solo di filtrare in disegno. Nessuna richiesta.
     *
     * <p><b>Rischio di sostanza, dichiarato anche nella riga delle impostazioni:</b> upstream tiene chiusa questa
     * porta anche come freno alle truffe nelle biografie, e qui il gate guarda l'abbonamento di <i>chi guarda</i>,
     * non del proprietario del profilo. Con lo sblocco acceso Morsegram rende cliccabile cio' che i client ufficiali
     * non rendono cliccabile, e il tocco apre il browser senza conferma. E' una funzione vera, non una porta chiusa,
     * quindi rispetta la regola; ma e' la voce che ha piu' senso spegnere, e per questo ha un interruttore suo.
     */
    public static boolean profileBioLinks() {
        return enabled() && on(ID_BIO_LINKS);
    }
}

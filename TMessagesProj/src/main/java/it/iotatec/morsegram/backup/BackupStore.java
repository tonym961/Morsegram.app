/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.backup;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import it.iotatec.morsegram.LocalPins;
import it.iotatec.morsegram.MorsegramEdition;
import it.iotatec.morsegram.MorsegramGates;
import it.iotatec.morsegram.MorsegramPrefs;
import it.iotatec.morsegram.ai.AiGate;
import it.iotatec.morsegram.fold.FoldSettings;
import it.iotatec.morsegram.hidden.HiddenChats;
import it.iotatec.morsegram.notify.MorsegramAlerts;
import it.iotatec.morsegram.theme.MorsegramTheme;
import it.iotatec.morsegram.unlock.LocalUnlocks;
import it.iotatec.morsegram.voice.VoiceSettings;

/**
 * L'anello Android del salvataggio delle impostazioni: prende da {@link MorsegramPrefs} (e dai widget e dai
 * temi) quello che {@link MorsegramBackup} sa scrivere, e riporta indietro quello che il codec ha validato.
 *
 * <p>Il codec non ha un solo import Android proprio per restare provabile sulla JVM; tutto quello che ha
 * bisogno del telefono sta qui: l'adattatore {@link Prefs} sulle {@code SharedPreferences}, il filtro delle
 * chat nascoste, i modelli dei widget ({@link BackupWidgets}), i nomi dei temi e — dopo un ripristino — le
 * cache volatili da invalidare, senza le quali la UI mostrerebbe i valori vecchi fino al riavvio.
 *
 * <p><b>Nessuna riga upstream.</b> Il file si scrive e si rilegge dal selettore di sistema, e il ripristino non
 * tocca niente fuori dai file {@code morsegram*} tranne la <b>selezione del tema</b>, che e' una scelta
 * dell'utente e si riapplica solo se quel tema esiste anche sul telefono nuovo. La selezione non sta in un posto
 * solo: {@code MorsegramTheme.applicaNomi} scrive {@code lastDayTheme} e {@code lastDarkTheme} in
 * {@code themeconfig}, e attraverso {@code Theme.applyTheme} e {@code Theme.saveAutoNightThemeConfig} finisce
 * anche nel {@code mainconfig} di upstream, dove entrano la chiave {@code theme}, la chiave {@code nighttheme} e
 * il blocco {@code autoNight*} riscritto dai valori che Telegram ha in memoria. Sono tutte e sole le chiavi della
 * selezione del tema — quello che l'anteprima mostra — e niente altro: nessun passcode, nessuna sessione, nessuna
 * impostazione di chat.
 *
 * <p>Una cosa la cancella: cambiare il fornitore dell'assistente azzera il consenso all'anteprima su OGNI
 * account ({@link AiGate#forgetPreviews()}), perche' il consenso era per un altro destinatario. E' l'unico
 * dato che il ripristino tocca senza che il file lo contenga, quindi l'anteprima lo dice
 * ({@code MorsegramBackupAiConsentAgain}) invece di lasciarlo scoprire.
 */
public final class BackupStore {

    private BackupStore() {
    }

    /* ======================================================================================= adattatori */

    /**
     * Un file di {@code SharedPreferences} visto dal codec. Le scritture si accumulano in un {@code Editor} e
     * diventano definitive con {@link #salva()}, cioe' un {@code commit()}: sincrono e atomico per file, cosi'
     * un ripristino non resta a metà dentro lo stesso file.
     */
    static final class Prefs implements MorsegramBackup.Deposito {
        private final SharedPreferences prefs;
        private SharedPreferences.Editor editor;

        Prefs(SharedPreferences prefs) {
            this.prefs = prefs;
        }

        private SharedPreferences.Editor editor() {
            if (editor == null) {
                editor = prefs.edit();
            }
            return editor;
        }

        @Override
        public boolean contiene(String chiave) {
            try {
                return prefs.contains(chiave);
            } catch (Throwable ignore) {
                return false;
            }
        }

        // i getter sono protetti contro il tipo sbagliato su disco (ClassCastException): una preferenza
        // scritta da una versione precedente con un altro tipo non deve far esplodere l'esportazione
        @Override
        public boolean leggiBool(String chiave, boolean def) {
            try {
                return prefs.getBoolean(chiave, def);
            } catch (Throwable ignore) {
                return def;
            }
        }

        @Override
        public int leggiInt(String chiave, int def) {
            try {
                return prefs.getInt(chiave, def);
            } catch (Throwable ignore) {
                return def;
            }
        }

        @Override
        public long leggiLong(String chiave, long def) {
            try {
                return prefs.getLong(chiave, def);
            } catch (Throwable ignore) {
                return def;
            }
        }

        @Override
        public String leggiStr(String chiave, String def) {
            try {
                return prefs.getString(chiave, def);
            } catch (Throwable ignore) {
                return def;
            }
        }

        @Override
        public void scriviBool(String chiave, boolean valore) {
            editor().putBoolean(chiave, valore);
        }

        @Override
        public void scriviInt(String chiave, int valore) {
            editor().putInt(chiave, valore);
        }

        @Override
        public void scriviLong(String chiave, long valore) {
            editor().putLong(chiave, valore);
        }

        @Override
        public void scriviStr(String chiave, String valore) {
            editor().putString(chiave, valore);
        }

        @Override
        public boolean salva() {
            if (editor == null) {
                return true;
            }
            try {
                final boolean ok = editor.commit();
                editor = null;
                return ok;
            } catch (Throwable e) {
                FileLog.e(e);
                return false;
            }
        }
    }

    /**
     * Il filtro delle chat nascoste, e <b>fallisce chiuso</b>: se lo slot non e' caricato,
     * {@code HiddenChats.isHidden} risponderebbe false perche' non sa ancora niente, e un id nascosto
     * finirebbe nel file. Nel dubbio si considera nascosto, cioe' si esporta meno.
     */
    static MorsegramBackup.Nascoste nascoste() {
        return (slot, dialogId) -> {
            if (dialogId == 0) {
                return false;
            }
            if (slot < 0 || slot >= UserConfig.MAX_ACCOUNT_COUNT) {
                return true;
            }
            try {
                // come MorsegramWidgets.canReadDialogs: dalla schermata di configurazione di un widget il
                // processo puo' essere appena nato, e senza init UserConfig non sa ancora niente
                ApplicationLoader.postInitApplication();
                final UserConfig config = UserConfig.getInstance(slot);
                if (!config.isClientActivated() || config.clientUserId == 0) {
                    return true;
                }
                return HiddenChats.isHidden(slot, dialogId);
            } catch (Throwable e) {
                FileLog.e(e);
                return true;
            }
        };
    }

    /**
     * L'elenco consentito. Le chiavi degli sblocchi arrivano da {@link LocalUnlocks#chiavi()} e non sono
     * scritte come letterali da nessuna parte in questo package: nell'edizione per Google Play quel metodo e'
     * codice morto e R8 lo cancella con le sue stringhe, mentre un elenco di letterali qui le farebbe
     * ricomparire nell'APK dello store.
     *
     * <p>L'elenco dipende anche dal gate del modello sul dispositivo ({@link MorsegramGates#LOCAL_AI}): le voci
     * dei fornitori dell'assistente arrivano fino a quello locale solo nell'edizione che lo contiene, perche'
     * l'anteprima deve nominare il destinatario che il ripristino scrive davvero (vedi
     * {@link MorsegramBackup#vociGlobali}).
     */
    static MorsegramBackup.Elenco elenco() {
        final boolean sideload = MorsegramEdition.sideloadOnly();
        return new MorsegramBackup.Elenco(
                MorsegramBackup.vociGlobali(sideload, MorsegramGates.LOCAL_AI,
                        sideload ? LocalUnlocks.chiavi() : null, VoiceSettings.LANGUAGE_TAGS),
                MorsegramBackup.vociAccount());
    }

    /* ======================================================================================= esportazione */

    /** Gli account loggati adesso, per user id: le preferenze per account vivono in {@code morsegram_u<userId>}. */
    private static Map<Long, MorsegramBackup.Conto> conti() {
        final Map<Long, MorsegramBackup.Conto> conti = new HashMap<>();
        for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; slot++) {
            final long userId = MorsegramPrefs.userId(slot);
            if (userId == 0) {
                continue;
            }
            conti.put(userId, new MorsegramBackup.Conto(slot, userId, new Prefs(MorsegramPrefs.account(slot))));
        }
        return conti;
    }

    /** Il file da salvare, in UTF-8. Mai {@code getAll()}: solo le voci dell'elenco. */
    public static byte[] esporta() {
        final MorsegramBackup.Sorgente sorgente = new MorsegramBackup.Sorgente(
                new Prefs(MorsegramPrefs.global()),
                new ArrayList<>(conti().values()),
                BackupWidgets.piazzati(nascoste()),
                temaAttuale());
        final String testo = MorsegramBackup.scrivi(elenco(), sorgente, nascoste(),
                BuildConfig.MORSEGRAM_VERSION, MorsegramEdition.sideloadOnly(), System.currentTimeMillis());
        return testo.getBytes(StandardCharsets.UTF_8);
    }

    /** I due nomi di tema configurati adesso (gia' validati da {@code MorsegramTheme}). */
    static MorsegramBackup.Tema temaAttuale() {
        try {
            return new MorsegramBackup.Tema(MorsegramTheme.dayThemeName(), MorsegramTheme.nightThemeName());
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /* ========================================================================================== lettura */

    public static MorsegramBackup.Esito leggi(byte[] bytes) {
        return MorsegramBackup.leggi(bytes, elenco());
    }

    /**
     * Cosa cambierebbe questo file: si costruisce senza scrivere niente, ed e' quello che la UI mostra. Si
     * chiama da un <b>thread di fondo</b>: il parsing di un file che puo' arrivare a 256 KB e il primo accesso
     * alle SharedPreferences (I/O sincrono) non stanno sul thread UI.
     */
    public static MorsegramBackup.Piano pianifica(MorsegramBackup.Letto letto) {
        final MorsegramBackup.Piano piano = MorsegramBackup.pianifica(letto, elenco(),
                new Prefs(MorsegramPrefs.global()), conti(), temaAttuale());
        filtraTema(piano);
        return piano;
    }

    /**
     * Del tema tiene solo i nomi che su QUESTO telefono esistono e sono del verso giusto, cioe' quelli che
     * {@code MorsegramTheme.applicaNomi} applichera' davvero.
     *
     * <p>Non e' una pignoleria: la deviazione che il tema serve a recuperare e' di solito un tema SCARICATO,
     * che dopo la reinstallazione non c'e' piu'. Senza questo filtro l'anteprima dice "Tema: Nebbia", l'utente
     * tocca Ripristina, legge "Impostazioni ripristinate" e resta sul tema di partenza senza sapere perche'.
     * L'anteprima deve promettere solo quello che accadra'.
     *
     * <p>I nomi scartati restano in {@link MorsegramBackup.Piano#temaNonTrovato}: l'anteprima li nomina, e
     * cosi' il file che portava solo un tema scomparso non finisce piu' dentro "niente da cambiare".
     */
    private static void filtraTema(MorsegramBackup.Piano piano) {
        final MorsegramBackup.Tema chiesto = piano.tema;
        if (chiesto == null) {
            return;
        }
        final String giorno;
        final String notte;
        try {
            giorno = MorsegramTheme.esisteNome(chiesto.giorno, false) ? chiesto.giorno : "";
            notte = MorsegramTheme.esisteNome(chiesto.notte, true) ? chiesto.notte : "";
        } catch (Throwable e) {
            FileLog.e(e);
            return;     // nel dubbio si tiene il tema come l'ha letto il codec
        }
        final MorsegramBackup.Tema t = new MorsegramBackup.Tema(giorno, notte);
        piano.tema = t.vuoto() ? null : t;
        // i nomi scartati si ricordano: se il file portava SOLO un tema che qui non c'e', il piano resta vuoto
        // e senza questa riga l'anteprima diceva "le stesse impostazioni che hai adesso", che e' falso, e
        // l'unica cosa utile da dire (il file portava il tema X, qui non c'e') non si leggeva da nessuna parte.
        //
        // E i nomi si STAMPANO, quindi passano dal filtro dei nomi di tema: il codec non fa entrare altro in
        // Letto (una riga t con una frase al posto del nome la scarta e la conta), e questa e' la seconda rete,
        // quella che vale anche se un giorno il lettore si allargasse. Un nome che non lo superasse resterebbe
        // fuori da qui senza perdere il segnale: la riga sarebbe fra le "righe che questa versione non
        // riconosce", che l'anteprima mostra in testa
        final StringBuilder persi = new StringBuilder();
        if (!chiesto.giorno.isEmpty() && giorno.isEmpty()
                && MorsegramBackup.nomeTemaPlausibile(chiesto.giorno)) {
            persi.append(chiesto.giorno);
        }
        if (!chiesto.notte.isEmpty() && notte.isEmpty() && !chiesto.notte.equals(chiesto.giorno)
                && MorsegramBackup.nomeTemaPlausibile(chiesto.notte)) {
            if (persi.length() > 0) {
                persi.append(", ");
            }
            persi.append(chiesto.notte);
        }
        piano.temaNonTrovato = persi.toString();
    }

    /* ======================================================================================= ripristino */

    /** Esito del ripristino, per la riga che l'utente legge dopo. */
    public static final class Fatto {
        /** quante preferenze sono state scritte, -1 se un file non si e' potuto scrivere. */
        public final int preferenze;
        public final int widget;
        public final boolean tema;
        /** l'assistente e' acceso su un fornitore di rete di cui manca la chiave: va detto, non intuito. */
        public final int fornitoreSenzaChiave;

        Fatto(int preferenze, int widget, boolean tema, int fornitoreSenzaChiave) {
            this.preferenze = preferenze;
            this.widget = widget;
            this.tema = tema;
            this.fornitoreSenzaChiave = fornitoreSenzaChiave;
        }

        public boolean riuscito() {
            return preferenze >= 0;
        }
    }

    /**
     * Le preferenze scritte, e i conti su cui lo sono state: il passaggio di consegne fra la prima metà del
     * ripristino (thread di fondo) e la seconda ({@link #completa}, thread UI).
     */
    public static final class Scritte {
        /** quante preferenze sono state scritte, -1 se un file non si e' potuto scrivere. */
        public final int preferenze;
        private final Map<Long, MorsegramBackup.Conto> conti;

        Scritte(int preferenze, Map<Long, MorsegramBackup.Conto> conti) {
            this.preferenze = preferenze;
            this.conti = conti;
        }

        public boolean riuscito() {
            return preferenze >= 0;
        }
    }

    /**
     * Prima metà del ripristino: le scritture. Va chiamata da un <b>thread di fondo</b>, perche' il
     * {@code commit()} delle SharedPreferences e' sincrono e arriva al disco, una volta per file (fino a 1+4
     * file); ed e' atomico per file, cosi' un ripristino non resta a metà dentro lo stesso file. Qui non c'e'
     * niente che abbia bisogno della UI.
     *
     * <p>Niente di quello che arriva qui e' stato indovinato: tutto e' passato dall'elenco consentito del
     * lettore e dal confronto con lo stato attuale.
     */
    public static Scritte applicaPreferenze(MorsegramBackup.Piano piano) {
        if (piano == null) {
            return new Scritte(0, new HashMap<>());
        }
        final Map<Long, MorsegramBackup.Conto> conti = conti();
        return new Scritte(MorsegramBackup.applica(piano, new Prefs(MorsegramPrefs.global()), conti), conti);
    }

    /**
     * Seconda metà del ripristino: <b>thread UI</b>. Qui ci sta solo quello che la vuole davvero —
     * {@code Theme.applyTheme}, {@code LocalPins.ricarica} e {@code MorsegramAlerts.ricarica} riallineano la
     * lista chat e la UI — nell'ordine: invalidazione delle cache, modelli dei widget, temi.
     */
    public static Fatto completa(MorsegramBackup.Piano piano, Scritte scritte) {
        if (piano == null || scritte == null) {
            return new Fatto(0, 0, false, -1);
        }
        // ANCHE quando una scrittura e' fallita: MorsegramBackup.applica salva un file per volta e si ferma al
        // primo commit che non riesce, quindi i file precedenti (tipicamente quello globale) sono gia' su
        // disco. Senza questa invalidazione la UI restava sui valori vecchi fino al riavvio dell'app, cioe' il
        // messaggio di errore mandava a controllare le impostazioni e la verifica dava il risultato sbagliato;
        // e al riavvio successivo il ripristino "fallito" prendeva effetto da solo
        invalidaCache(piano, scritte.conti);
        if (!scritte.riuscito()) {
            return new Fatto(-1, 0, false, -1);
        }
        // niente widget nel file, niente da scrivere: salvaModelli cancella i vecchi modelli e scrive tpl.n=0,
        // quindi chiamarla a vuoto spazzerebbe i modelli ancora in attesa del ripristino precedente (un file
        // che cambia una sola preferenza azzererebbe la riga "Modelli di widget in attesa")
        final int widget = piano.widget.isEmpty() ? 0 : BackupWidgets.salvaModelli(piano.widget);
        boolean tema = false;
        if (piano.tema != null) {
            try {
                tema = MorsegramTheme.applicaNomi(piano.tema.giorno, piano.tema.notte);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return new Fatto(scritte.preferenze, widget, tema, fornitoreSenzaChiave());
    }

    /**
     * Le cache volatili che leggono le preferenze una volta sola: senza questo passaggio la UI continuerebbe a
     * mostrare i valori di prima fino al riavvio dell'app, e il backup sembrerebbe non aver funzionato.
     */
    private static void invalidaCache(MorsegramBackup.Piano piano, Map<Long, MorsegramBackup.Conto> conti) {
        boolean fold = false;
        boolean voce = false;
        boolean ai = false;
        boolean destinatarioAi = false;
        boolean avvisiGenerali = false;
        for (MorsegramBackup.Cambio c : piano.cambi) {
            if (c.ambito == MorsegramBackup.A_GLOBALE) {
                if (c.chiave.startsWith("fold.")) {
                    fold = true;
                } else if (c.chiave.startsWith("voice.")) {
                    voce = true;
                } else if (c.chiave.startsWith("ai.")) {
                    ai = true;
                    // cambiare fornitore cambia il DESTINATARIO del testo: l'anteprima lo nomina, quindi un
                    // consenso dato per un altro destinatario non vale piu' (AiSettings.setPrimary fa lo stesso)
                    if (c.chiave.equals("ai.primary") || c.chiave.equals("ai.fallback")) {
                        destinatarioAi = true;
                    }
                } else if (c.chiave.equals("alerts.mentions")) {
                    avvisiGenerali = true;
                }
                continue;
            }
            final MorsegramBackup.Conto conto = conti.get(c.userId);
            if (conto == null) {
                continue;
            }
            if (c.chiave.equals("pins.order")) {
                LocalPins.ricarica(conto.slot);
            } else if (c.chiave.equals("alerts.chats")) {
                MorsegramAlerts.ricarica(conto.slot);
            } else if (c.chiave.equals("ai.chats")) {
                ai = true;
            }
        }
        if (fold) {
            FoldSettings.ricarica();
        }
        if (voce) {
            VoiceSettings.ricarica();
        }
        if (avvisiGenerali) {
            MorsegramAlerts.ricaricaGenerale();
        }
        if (destinatarioAi) {
            AiGate.forgetPreviews();
        }
        if (ai) {
            AiGate.notifyChanged();
        }
    }

    /**
     * L'id del fornitore principale se e' di rete, l'assistente e' acceso e la chiave non c'e'; altrimenti -1.
     * E' la mitigazione dell'unica cosa che il backup non riporta: il file ricorda QUALE fornitore e quale
     * modello, quindi dopo il ripristino manca solo l'incollatura, e lo si dice invece di lasciare una funzione
     * accesa che non puo' lavorare.
     */
    public static int fornitoreSenzaChiave() {
        try {
            if (!it.iotatec.morsegram.ai.AiSettings.enabled()) {
                return -1;
            }
            final int primario = it.iotatec.morsegram.ai.AiSettings.primary();
            if (it.iotatec.morsegram.ai.AiProvider.isLocal(primario)) {
                return -1;
            }
            return it.iotatec.morsegram.ai.AiKeys.hasKey(primario) ? -1 : primario;
        } catch (Throwable e) {
            FileLog.e(e);
            return -1;
        }
    }

    /* ============================================================================================ varie */

    /** Vero se c'e' un contesto: senza, {@link MorsegramPrefs} restituisce lo stub e non si salva niente. */
    public static boolean pronto() {
        return ApplicationLoader.applicationContext != null;
    }

    /** Gli slot loggati, per dire nell'anteprima di quale account sono le liste di chat. */
    public static List<MorsegramBackup.Conto> contiAttivi() {
        return new ArrayList<>(conti().values());
    }
}

/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.backup;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.iotatec.morsegram.MorsegramEdition;
import it.iotatec.morsegram.ai.AiProvider;
import it.iotatec.morsegram.notify.MorsegramAlerts;
import it.iotatec.morsegram.unlock.LocalUnlocks;
import it.iotatec.morsegram.voice.VoiceSettings;

/**
 * Pagina <b>Salvataggio impostazioni</b> (Impostazioni → Morsegram → Salvataggio impostazioni): esporta le
 * impostazioni di Morsegram in un file, le rilegge da un file, e dice in chiaro cosa c'e' dentro e cosa no.
 *
 * <p><b>Un file scelto dall'utente, e zero righe upstream.</b> Si usa il selettore di documenti di Android
 * ({@code ACTION_CREATE_DOCUMENT} / {@code ACTION_OPEN_DOCUMENT}): il file lo mette l'utente dove vuole, non
 * parte nessun messaggio a suo nome, non si tocca nessuna API di Telegram e il risultato torna da
 * {@code BaseFragment.onActivityResultFragment}, che {@code LaunchActivity} chiama da sola. Dove finisce il
 * file lo decide il selettore, che propone anche fornitori remoti: il file non e' cifrato e contiene degli id
 * di chat, quindi prima di esportare si dice cosa esce e si chiede ({@link #scegliDoveSalvare()}).
 *
 * <p><b>Prima si vede, poi si applica.</b> Il ripristino mostra un'anteprima voce per voce di cosa
 * sovrascriverebbe (cosa c'e' adesso, cosa ci sarebbe dopo) e applica solo dopo un secondo tocco. Se il file e'
 * di una versione futura, troncato, manomesso o di un'altra app, non si applica niente: un ripristino a meta' e'
 * peggio di un ripristino fallito.
 */
public class BackupSettingsFragment extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_SETTINGS = 1;
    private static final int TYPE_INFO = 2;

    /** Codici di richiesta del selettore di documenti: arbitrari, basta che non si pestino fra loro. */
    private static final int REQ_ESPORTA = 9201;
    private static final int REQ_RIPRISTINA = 9202;

    private RecyclerListView listView;
    private ListAdapter listAdapter;

    private int rowCount;
    private int fileHeaderRow;
    private int esportaRow;
    private int ripristinaRow;
    private int fileInfoRow;
    private int contenutoHeaderRow;
    private int contenutoInfoRow;

    /** true mentre si legge o si scrive: due tocchi non devono far partire due giri. */
    private boolean occupato;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();
        updateRows();
        return true;
    }

    private void updateRows() {
        rowCount = 0;
        fileHeaderRow = rowCount++;
        esportaRow = rowCount++;
        ripristinaRow = rowCount++;
        fileInfoRow = rowCount++;
        contenutoHeaderRow = rowCount++;
        contenutoInfoRow = rowCount++;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString("MorsegramBackup"));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);
        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        final FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position) -> {
            if (occupato || getParentActivity() == null) {
                return;
            }
            if (position == esportaRow) {
                scegliDoveSalvare();
            } else if (position == ripristinaRow) {
                scegliCosaLeggere();
            }
        });
        return fragmentView;
    }

    /* ======================================================================================= esportazione */

    /**
     * Prima di esportare si dice cosa esce, e si chiede. Non e' una conferma di cortesia: il file non e'
     * cifrato, il selettore di sistema propone anche fornitori cloud, e l'unica difesa degli id di chat che
     * contiene e' che l'utente scelga la destinazione sapendo cosa ci sta mettendo. Le righe che lo spiegano
     * stanno nella seconda sezione della pagina, sotto i pulsanti, quindi su uno schermo di telefono si
     * arriverebbe a "Esporta" senza averle lette.
     *
     * <p>Per lo stesso motivo il dialogo dice anche il FATTO tecnico su cui si decide — il file e' un documento
     * di testo leggibile e non e' cifrato ({@code MorsegramBackupPlainFile}) — e non solo cosa contiene: la
     * frase completa vive in {@code MorsegramBackupHow}, cioe' nella riga informativa della pagina, che da qui
     * non si vede.
     */
    private void scegliDoveSalvare() {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString("MorsegramBackupExport"));
        // il nome si dice QUI, non come valore della riga: in release TextSettingsCell da' al valore meta'
        // della larghezza disponibile e lo troncherebbe a "morsegram-impostazion..."
        builder.setMessage(cosaEntra() + "\n\n"
                + LocaleController.getString("MorsegramBackupPlainFile") + "\n\n"
                + LocaleController.formatString("MorsegramBackupProposedName", 0, MorsegramBackup.NOME_FILE));
        builder.setPositiveButton(LocaleController.getString("MorsegramBackupExport"),
                (d, which) -> apriSelettoreScrittura());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    private void apriSelettoreScrittura() {
        final Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(MorsegramBackup.MIME);
        intent.putExtra(Intent.EXTRA_TITLE, MorsegramBackup.NOME_FILE);
        lancia(intent, REQ_ESPORTA);
    }

    private void scegliCosaLeggere() {
        final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // alcuni fornitori di documenti non mostrano i .txt filtrando su text/plain: si apre tutto e si
        // suggerisce il tipo, poi e' il lettore a dire se il file e' nostro
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{MorsegramBackup.MIME, "application/octet-stream"});
        lancia(intent, REQ_RIPRISTINA);
    }

    private void lancia(Intent intent, int codice) {
        try {
            startActivityForResult(intent, codice);
        } catch (Throwable e) {
            FileLog.e(e);
            avviso(LocaleController.getString("MorsegramBackupNoPicker"));
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQ_ESPORTA) {
            scrivi(data.getData());
        } else if (requestCode == REQ_RIPRISTINA) {
            leggi(data.getData());
        }
    }

    /** Scrittura su thread di fondo: passa dal ContentResolver, che puo' essere un fornitore remoto. */
    private void scrivi(Uri uri) {
        final Activity activity = getParentActivity();
        if (activity == null || !BackupStore.pronto()) {
            return;
        }
        occupato = true;
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            int voci = 0;
            try {
                final byte[] bytes = BackupStore.esporta();
                voci = righe(bytes);
                final OutputStream out = activity.getContentResolver().openOutputStream(uri, "wt");
                if (out != null) {
                    try {
                        out.write(bytes);
                        out.flush();
                        ok = true;
                    } finally {
                        out.close();
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final boolean riuscito = ok;
            final int quante = voci;
            AndroidUtilities.runOnUIThread(() -> {
                occupato = false;
                if (riuscito) {
                    successo(LocaleController.formatString("MorsegramBackupSaved", 0, quante));
                } else {
                    avviso(LocaleController.getString("MorsegramBackupSaveFailed"));
                }
            });
        });
    }

    /** Quante righe di impostazioni (non la testata): e' il numero che si dice all'utente. */
    private static int righe(byte[] bytes) {
        int n = 0;
        for (byte b : bytes) {
            if (b == '\n') {
                n++;
            }
        }
        return Math.max(0, n - 2);
    }

    /* ========================================================================================= ripristino */

    private void leggi(Uri uri) {
        final Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        occupato = true;
        Utilities.globalQueue.postRunnable(() -> {
            byte[] bytes = null;
            boolean letturaFallita = false;
            try {
                final InputStream in = activity.getContentResolver().openInputStream(uri);
                if (in == null) {
                    letturaFallita = true;
                } else {
                    try {
                        final ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
                        final byte[] pezzo = new byte[4096];
                        int quanti;
                        // il tetto si controlla mentre si legge: un file enorme non si tira dentro per
                        // scoprire solo dopo che era enorme
                        while ((quanti = in.read(pezzo)) > 0 && buffer.size() <= MorsegramBackup.MAX_BYTES) {
                            buffer.write(pezzo, 0, quanti);
                        }
                        bytes = buffer.toByteArray();
                    } finally {
                        in.close();
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
                letturaFallita = true;
            }
            // sullo STESSO thread di fondo: il parsing (un file di impostazioni arriva fino a 256 KB, cioe'
            // migliaia di split e validazioni) e il confronto con quello che c'e' adesso, che apre le
            // SharedPreferences globali e quelle di ogni account, cioe' I/O sincrono al primo accesso. Sul
            // thread UI resta solo il dialogo
            MorsegramBackup.Esito esitoLetto = null;
            MorsegramBackup.Piano pianificato = null;
            if (!letturaFallita) {
                try {
                    esitoLetto = BackupStore.leggi(bytes);
                    if (esitoLetto.ok()) {
                        pianificato = BackupStore.pianifica(esitoLetto.letto);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                    letturaFallita = true;
                }
            }
            final boolean fallita = letturaFallita;
            final MorsegramBackup.Esito esito = esitoLetto;
            final MorsegramBackup.Piano piano = pianificato;
            AndroidUtilities.runOnUIThread(() -> {
                occupato = false;
                if (fallita) {
                    avviso(LocaleController.getString("MorsegramBackupReadFailed"));
                    return;
                }
                if (piano == null) {
                    avviso(messaggioErrore(esito));
                    return;
                }
                anteprima(piano);
            });
        });
    }

    /** Un errore diverso per ogni motivo: "versione piu' nuova" non e' "file non valido". */
    private static String messaggioErrore(MorsegramBackup.Esito esito) {
        switch (esito.codice) {
            // i tre errori "hai scelto il file sbagliato" nominano il file da cercare: il selettore si apre
            // volutamente su tutti i file (vedi scegliCosaLeggere), quindi il tocco sbagliato e' l'esito
            // atteso, non un caso limite
            case MorsegramBackup.ERR_VUOTO:
                return LocaleController.formatString("MorsegramBackupErrEmpty", 0, MorsegramBackup.NOME_FILE);
            case MorsegramBackup.ERR_TROPPO_GRANDE:
                return LocaleController.formatString("MorsegramBackupErrBig", 0, MorsegramBackup.NOME_FILE);
            case MorsegramBackup.ERR_FORMATO_FUTURO:
                return LocaleController.getString("MorsegramBackupErrFuture");
            case MorsegramBackup.ERR_NIENTE_DA_FARE: {
                // e' il file piu' interessante da raccontare: ci si arriva anche quando TUTTE le righe
                // riconoscibili sono state buttate via, e allora il referto (quante, e con quali chiavi) e'
                // l'unica cosa che ci sia da dire. Senza questa coda si perdeva, perche' la via che lo mostra
                // era solo quella dell'anteprima, e proprio il file che portava hidden.pin.hash non la prende
                final MorsegramBackup.Letto letto = esito.letto;
                return LocaleController.getString("MorsegramBackupErrNothing")
                        + (letto == null ? "" : scartate(letto.righeIgnote, letto.chiaviIgnote,
                        letto.righeRifiutate, letto.chiaviRifiutate, "\n"));
            }
            default:
                return LocaleController.formatString("MorsegramBackupErrNotOurs", 0, MorsegramBackup.NOME_FILE);
        }
    }

    /**
     * L'anteprima: cosa verra' cambiato, PRIMA di cambiarlo. Qui non si e' ancora scritto niente da nessuna
     * parte, e il tasto per annullare c'e'.
     */
    private void anteprima(MorsegramBackup.Piano piano) {
        if (getParentActivity() == null) {
            return;
        }
        if (piano.vuoto()) {
            // "le stesse impostazioni che hai adesso" si puo' dire solo se il file non portava ANCHE blocchi
            // di account che qui non sono collegati o un tema che qui non esiste: sono situazioni diversissime
            // (il file di un altro telefono con altri account non ha "niente di nuovo" solo perche' le sue
            // righe non si possono applicare) e dicevano tutte la stessa cosa
            final boolean saltato = !piano.accountMancanti.isEmpty() || !piano.temaNonTrovato.isEmpty();
            final StringBuilder sb = new StringBuilder(256);
            sb.append(LocaleController.getString(saltato
                    ? "MorsegramBackupNothingToChange" : "MorsegramBackupNothingToDo"));
            if (!piano.accountMancanti.isEmpty()) {
                sb.append("\n\n").append(LocaleController.formatString("MorsegramBackupMissingAccounts", 0,
                        piano.accountMancanti.size()));
            }
            if (!piano.temaNonTrovato.isEmpty()) {
                sb.append("\n\n").append(LocaleController.formatString("MorsegramBackupThemeMissing", 0,
                        piano.temaNonTrovato));
            }
            avviso(sb.append(scartate(piano, "\n\n")).toString());
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString("MorsegramBackupPreviewTitle"));
        builder.setMessage(testoAnteprima(piano));
        builder.setPositiveButton(LocaleController.getString("MorsegramBackupRestore"), (d, which) -> applica(piano));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        showDialog(builder.create());
    }

    /**
     * Il ripristino, in due tempi: le scritture su thread di fondo (un {@code commit()} per file, sincrono e
     * fino a cinque file), e sul thread UI solo quello che riallinea la UI. In mezzo non c'e' niente da
     * decidere: il piano e' esattamente quello che l'anteprima ha mostrato.
     */
    private void applica(MorsegramBackup.Piano piano) {
        if (occupato) {
            return;
        }
        occupato = true;
        Utilities.globalQueue.postRunnable(() -> {
            BackupStore.Scritte scritte = null;
            try {
                scritte = BackupStore.applicaPreferenze(piano);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final BackupStore.Scritte fatte = scritte;
            AndroidUtilities.runOnUIThread(() -> {
                occupato = false;
                if (fatte == null || !fatte.riuscito()) {
                    // le preferenze si scrivono con un commit() per file, che e' atomico: dentro un file non
                    // resta niente a meta'. Se un file non si e' potuto scrivere lo si dice, invece di far
                    // credere a un ripristino fatto
                    avviso(LocaleController.getString("MorsegramBackupApplyFailed"));
                    return;
                }
                riferisci(piano, BackupStore.completa(piano, fatte));
            });
        });
    }

    /**
     * La riga che si legge dopo: cosa e' stato scritto, e cosa non si e' potuto fare. Il bulletin verde sta
     * su DUE righe con i puntini di sospensione in fondo (circa settantacinque caratteri su uno schermo
     * stretto), e la sola frase base in italiano ne occupa trentacinque: appena c'e' una coda da dire -- i
     * widget in attesa, il tema non applicato, la chiave del fornitore che manca -- il taglio cadrebbe
     * proprio sull'unica istruzione da seguire. Quindi: bulletin per il caso nudo, dialogo (che scorre e si
     * chiude con OK) quando c'e' qualcosa in piu'.
     */
    private void riferisci(MorsegramBackup.Piano piano, BackupStore.Fatto fatto) {
        final StringBuilder sb = new StringBuilder();
        boolean coda = false;
        sb.append(LocaleController.formatString("MorsegramBackupDone", 0, fatto.preferenze));
        if (fatto.widget > 0) {
            coda = true;
            sb.append(' ').append(LocaleController.formatString("MorsegramBackupWidgetPending", 0, fatto.widget));
        }
        if (piano.tema != null && !piano.tema.vuoto() && !fatto.tema) {
            coda = true;
            // il piano porta solo nomi di tema che su questo telefono esistono (BackupStore.pianifica li
            // filtra prima dell'anteprima), quindi qui ci si arriva solo se qualcosa e' cambiato dopo: va
            // detto, perche' l'anteprima aveva nominato quel tema e l'utente resterebbe sul suo senza sapere
            // perche'
            sb.append(' ').append(LocaleController.getString("MorsegramBackupThemeNotApplied"));
        }
        if (fatto.fornitoreSenzaChiave != -1) {
            coda = true;
            sb.append(' ').append(LocaleController.formatString("MorsegramBackupNoKey", 0,
                    AiProvider.labelOf(fatto.fornitoreSenzaChiave)));
        }
        if (coda) {
            avviso(sb.toString());
        } else {
            successo(sb.toString());
        }
    }

    /* ========================================================================================= anteprima */

    /**
     * Il testo dell'anteprima: una riga per voce, con il valore di adesso e quello che arriverebbe.
     *
     * <p>L'ordine e' quello in cui si decide: prima cosa e' stato <b>buttato via</b> (e perche'), poi cosa
     * verrebbe cambiato, poi quello che il ripristino fa senza che il file lo porti (il consenso
     * dell'assistente) e cosa viene saltato.
     */
    private String testoAnteprima(MorsegramBackup.Piano piano) {
        final StringBuilder sb = new StringBuilder(512);
        sb.append(LocaleController.formatString("MorsegramBackupFrom", 0,
                piano.versioneApp.isEmpty() ? "?" : piano.versioneApp, dataLeggibile(piano.creato)));
        sb.append("\n");
        // in TESTA, non in coda: le righe buttate via sono l'unica cosa allarmante che l'anteprima sappia dire
        // (il file nominava hidden.pin.hash, oppure key.groq), un ripristino pieno produce oltre venticinque
        // righe con il pallino e il dialogo ne mostra una dozzina, ma il pulsante Ripristina sta FUORI dallo
        // scorrimento ed e' cliccabile subito: in fondo si decideva senza averle lette
        final String scartate = scartate(piano, "\n");
        if (!scartate.isEmpty()) {
            sb.append(scartate).append("\n");
        }

        final List<String> generali = new ArrayList<>();
        final Map<Long, List<String>> perAccount = new LinkedHashMap<>();
        int sblocchi = 0;
        for (MorsegramBackup.Cambio c : piano.cambi) {
            if (c.ambito == MorsegramBackup.A_GLOBALE) {
                if (c.chiave.startsWith("unlock.")) {
                    sblocchi++;     // mai la singola chiave: si dice quante voci, non quali preferenze su disco
                    continue;
                }
                generali.add(voce(c));
                continue;
            }
            List<String> l = perAccount.get(c.userId);
            if (l == null) {
                l = new ArrayList<>();
                perAccount.put(c.userId, l);
            }
            l.add(voce(c));
        }
        if (sblocchi > 0 && LocalUnlocks.enabled()) {
            generali.add(LocaleController.getString("MorsegramSettingsUnlocks") + ": " + sblocchi);
        }
        if (!generali.isEmpty()) {
            sb.append("\n").append(LocaleController.getString("MorsegramBackupGeneral")).append("\n");
            for (String s : generali) {
                sb.append("• ").append(s).append("\n");
            }
        }
        for (Map.Entry<Long, List<String>> e : perAccount.entrySet()) {
            sb.append("\n").append(LocaleController.formatString("MorsegramBackupAccount", 0,
                    nomeAccount(e.getKey()))).append("\n");
            for (String s : e.getValue()) {
                sb.append("• ").append(s).append("\n");
            }
        }
        // UNA RIGA PER NOME: il ripristino scrive entrambi i temi (MorsegramTheme.applicaNomi scrive
        // lastDayTheme E lastDarkTheme), e un file di un telefono configurato ne porta due. Mostrarne uno solo
        // voleva dire cambiare il tema notturno in silenzio, oppure dire "Tema: X" di un nome notturno a chi e'
        // in modalita' chiara, che tocca Ripristina e non vede cambiare niente
        if (piano.tema != null && !piano.tema.vuoto()) {
            sb.append("\n");
            if (!piano.tema.giorno.isEmpty()) {
                sb.append(LocaleController.formatString("MorsegramBackupTheme", 0,
                        piano.tema.giorno)).append("\n");
            }
            if (!piano.tema.notte.isEmpty()) {
                sb.append(LocaleController.formatString("MorsegramBackupThemeNight", 0,
                        piano.tema.notte)).append("\n");
            }
        }
        if (!piano.temaNonTrovato.isEmpty()) {
            sb.append("\n").append(LocaleController.formatString("MorsegramBackupThemeMissing", 0,
                    piano.temaNonTrovato)).append("\n");
        }
        if (!piano.widget.isEmpty()) {
            sb.append("\n").append(LocaleController.formatString("MorsegramBackupWidgets", 0,
                    piano.widget.size())).append("\n");
            // i modelli SOSTITUISCONO quelli di prima (BackupWidgets.salvaModelli), quindi quelli ancora in
            // attesa di un piazzamento vengono buttati via: la pagina li conta nella riga "Modelli di widget
            // in attesa", che dopo il ripristino scenderebbe senza spiegazione, e la pagina promette che il
            // file non cambia niente oltre a quello che l'anteprima ha elencato
            final int attesa = BackupWidgets.inAttesa();
            if (attesa > 0) {
                sb.append(LocaleController.formatString("MorsegramBackupWidgetsReplace", 0, attesa))
                        .append("\n");
            }
        }
        if (cambiaDestinatarioAi(piano)) {
            sb.append("\n").append(LocaleController.getString("MorsegramBackupAiConsentAgain")).append("\n");
        }
        if (!piano.accountMancanti.isEmpty()) {
            sb.append("\n").append(LocaleController.formatString("MorsegramBackupMissingAccounts", 0,
                    piano.accountMancanti.size())).append("\n");
        }
        // in coda si parla del RIPRISTINO, non del file: questo file l'ha scelto l'utente, puo' venire da
        // chiunque e puo' contenere qualunque cosa. Dire "nel file non ci sono il PIN, le chat nascoste e le
        // chiavi dei fornitori" di un file di fonte ignota sarebbe affermare il falso proprio nella schermata
        // dove si decide, e il referto in testa ne ha appena mostrato la prova contraria
        sb.append("\n").append(LocaleController.getString("MorsegramBackupRestoreNotKeys"));
        return sb.toString();
    }

    /**
     * Vero se il piano cambia il fornitore dell'assistente (principale o di riserva). Il ripristino allora
     * azzera il consenso all'anteprima su OGNI account loggato
     * ({@code BackupStore.invalidaCache} → {@code AiGate.forgetPreviews}), perche' un consenso dato per un
     * altro destinatario non vale piu'.
     *
     * <p>Va detto qui: {@code ai.previewed} e' fra le chiavi VIETATE, cioe' un dato che il file non contiene
     * mai, ed e' l'unica cosa che il ripristino cancella senza che il file la nomini. Senza questa riga
     * l'anteprima non lo diceva da nessuna parte, e la pagina promette che il file non cambia niente oltre a
     * quello che l'anteprima ha elencato.
     */
    private static boolean cambiaDestinatarioAi(MorsegramBackup.Piano piano) {
        for (MorsegramBackup.Cambio c : piano.cambi) {
            if (c.ambito == MorsegramBackup.A_GLOBALE
                    && ("ai.primary".equals(c.chiave) || "ai.fallback".equals(c.chiave))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Le righe buttate via, con il motivo GIUSTO e i nomi delle chiavi: "non riconosciute" (file di un'altra
     * versione, ritoccato a mano) e "rifiutate" (il file nominava il PIN, le chat nascoste o una chiave di
     * fornitore) sono due cose diverse, e l'utente decide su questo.
     */
    private static String scartate(MorsegramBackup.Piano piano, String separatore) {
        return scartate(piano.righeIgnote, piano.chiaviIgnote,
                piano.righeRifiutate, piano.chiaviRifiutate, separatore);
    }

    /** Lo stesso referto, dai conteggi grezzi: serve anche quando non c'e' nessun piano da mostrare. */
    private static String scartate(int ignote, List<String> chiaviIgnote,
                                   int rifiutate, List<String> chiaviRifiutate, String separatore) {
        final StringBuilder sb = new StringBuilder(96);
        if (ignote > 0) {
            sb.append(separatore).append(LocaleController.formatString("MorsegramBackupSkippedUnknown", 0,
                    ignote)).append(chiavi(chiaviIgnote));
        }
        if (rifiutate > 0) {
            sb.append(separatore).append(LocaleController.formatString("MorsegramBackupRefused", 0,
                    rifiutate)).append(chiavi(chiaviRifiutate));
        }
        return sb.toString();
    }

    /** I nomi fra parentesi: se il file portava il PIN o una chiave, l'utente lo vede nero su bianco. */
    private static String chiavi(List<String> elenco) {
        if (elenco.isEmpty()) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(64);
        sb.append(" (");
        for (int i = 0; i < elenco.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(elenco.get(i));
        }
        return sb.append(')').toString();
    }

    /**
     * Una voce dell'anteprima: etichetta, valore di adesso (se c'e'), valore nuovo. Per l'unica preferenza
     * che e' un opt-in verso un terzo si dice anche PERCHE' lo e': vedi {@link #codaOptIn(MorsegramBackup.Cambio)}.
     */
    private static String voce(MorsegramBackup.Cambio c) {
        final String etichetta = etichetta(c.chiave);
        final String nuovo = valore(c.chiave, c.nuovo);
        final String riga = c.attuale.isEmpty()
                ? LocaleController.formatString("MorsegramBackupSet", 0, etichetta, nuovo)
                : LocaleController.formatString("MorsegramBackupChange", 0, etichetta,
                        valore(c.chiave, c.attuale), nuovo);
        return riga + codaOptIn(c);
    }

    /**
     * La coda di una riga dell'anteprima, oggi solo per {@code voice.allowSystem} quando il file la ACCENDE.
     * Quella preferenza non e' una comodita' come le altre: consente al riconoscitore di sistema di prendere
     * l'audio dei vocali quando manca quello on-device, e {@code EXTRA_PREFER_OFFLINE} e' una preferenza, non
     * una garanzia, quindi l'audio puo' finire al fornitore del telefono. Nella pagina dei Vocali
     * l'interruttore lo dice ({@code MorsegramVoiceAllowSystemInfo}); qui, dove si decide su un file che puo'
     * venire da chiunque, "da non attivo a attivo" non lo direbbe, e questa e' l'ultima schermata prima della
     * scrittura. Lo spegnimento non ha coda: nessuno ha bisogno di essere avvertito che un opt-in si chiude.
     */
    private static String codaOptIn(MorsegramBackup.Cambio c) {
        if (c.ambito == MorsegramBackup.A_GLOBALE
                && "voice.allowSystem".equals(c.chiave) && "true".equals(c.nuovo)) {
            return ". " + LocaleController.getString("MorsegramBackupAllowSystemWarn");
        }
        return "";
    }

    /**
     * L'etichetta della preferenza, riusando le stringhe delle righe che la governano: l'utente riconosce la
     * voce con il nome che ha visto nelle impostazioni, non con la chiave su disco.
     */
    private static String etichetta(String chiave) {
        switch (chiave) {
            case "fold.twoPanes":
                return LocaleController.getString("MorsegramFoldTwoPanes");
            case "fold.hingeSplit":
                return LocaleController.getString("MorsegramFoldHingeSplit");
            case "alerts.mentions":
                return LocaleController.getString("MorsegramAlertsMentions");
            case "alerts.chats":
                return LocaleController.getString("MorsegramAlertsChats");
            case "voice.mode":
                return LocaleController.getString("MorsegramVoiceMode");
            case "voice.lang":
                return LocaleController.getString("MorsegramVoiceLanguage");
            case "voice.allowSystem":
                return LocaleController.getString("MorsegramVoiceAllowSystem");
            case "voice.lockMode":
                return LocaleController.getString("MorsegramLockVoiceMode");
            case "hand.sendLeft":
                return LocaleController.getString("MorsegramHandSendLeft");
            case "ai.enabled":
                return LocaleController.getString("MorsegramAiEnabled");
            case "ai.primary":
                return LocaleController.getString("MorsegramAiPrimary");
            case "ai.fallback":
                return LocaleController.getString("MorsegramAiFallback");
            case "ai.chats":
                return LocaleController.getString("MorsegramAiActiveChats");
            case "pins.order":
                return LocaleController.getString("MorsegramLocalPins");
            case "update.source":
                return LocaleController.getString("MorsegramUpdateSource");
            case "update.auto":
                return LocaleController.getString("MorsegramUpdateAuto");
            default:
                if (chiave.startsWith("ai.model.")) {
                    final int id = (int) numero(chiave.substring("ai.model.".length()));
                    return LocaleController.formatString("MorsegramBackupModel", 0, AiProvider.labelOf(id));
                }
                return chiave;
        }
    }

    /** Il valore in parole: le etichette che la pagina mostra, non il numero su disco. */
    private static String valore(String chiave, String grezzo) {
        if ("true".equals(grezzo) || "false".equals(grezzo)) {
            return LocaleController.getString("true".equals(grezzo) ? "MorsegramBackupOn" : "MorsegramBackupOff");
        }
        try {
            switch (chiave) {
                case "alerts.mentions":
                    return MorsegramAlerts.modeLabel((int) numero(grezzo));
                case "voice.mode":
                    return VoiceSettings.modeLabel((int) numero(grezzo));
                case "voice.lang":
                    // il vuoto qui e' un valore vero ("lingua dell'app"), non un interruttore spento: la prima
                    // etichetta del selettore dei Vocali e' esattamente quella, con la lingua fra parentesi
                    return grezzo.isEmpty() ? VoiceSettings.languageChoiceLabels()[0]
                            : VoiceSettings.displayName(grezzo);
                case "ai.primary":
                case "ai.fallback": {
                    final String etichetta = AiProvider.labelOf((int) numero(grezzo));
                    return etichetta == null || etichetta.isEmpty()
                            ? LocaleController.getString("MorsegramAiFallbackNone") : etichetta;
                }
                case "pins.order":
                case "ai.chats":
                case "alerts.chats":
                    return LocaleController.formatString("MorsegramBackupChats", 0, quante(grezzo));
                default:
                    return grezzo;
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return grezzo;
        }
    }

    private static int quante(String csv) {
        if (csv == null || csv.isEmpty()) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < csv.length(); i++) {
            if (csv.charAt(i) == ',') {
                n++;
            }
        }
        return n;
    }

    private static long numero(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable ignore) {
            return -1;
        }
    }

    /** Il nome dell'utente a cui appartiene un blocco di preferenze, o il suo id se non si trova. */
    private static String nomeAccount(long userId) {
        for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; slot++) {
            try {
                final UserConfig config = UserConfig.getInstance(slot);
                if (config.clientUserId != userId) {
                    continue;
                }
                final TLRPC.User user = config.getCurrentUser();
                if (user != null) {
                    return UserObject.getUserName(user);
                }
            } catch (Throwable ignore) {
            }
        }
        return String.valueOf(userId);
    }

    private static String dataLeggibile(long millis) {
        if (millis <= 0) {
            return "?";
        }
        try {
            return LocaleController.getInstance().getFormatterStats().format(millis);
        } catch (Throwable ignore) {
            return String.valueOf(millis);
        }
    }

    /* ============================================================================================== righe */

    private void successo(String testo) {
        try {
            if (BulletinFactory.canShowBulletin(this)) {
                BulletinFactory.of(this).createSuccessBulletin(testo).show();
                return;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        avviso(testo);
    }

    private void avviso(String testo) {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString("MorsegramBackup"));
        builder.setMessage(testo);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(builder.create());
    }

    /**
     * Cosa entra nel file: la riga che l'utente legge prima di esportare. Dice anche, per nome, quali DATI
     * PERSONALI escono (gli id delle chat appuntate, di quelle con avvisi propri, di quelle con l'assistente
     * acceso, la chat di ogni widget, il canale degli aggiornamenti, il proprio user id): e' un file che
     * l'utente e' invitato a mettere dove vuole, quindi deve sapere cosa ci sta mettendo. Non basta
     * {@code MorsegramBackupNotKeys}, che dice cosa NON c'e'.
     */
    private static String cosaEntra() {
        final StringBuilder sb = new StringBuilder(512);
        sb.append(LocaleController.getString("MorsegramBackupInside"));
        if (MorsegramEdition.sideloadOnly()) {
            sb.append(' ').append(LocaleController.getString("MorsegramBackupInsideSideload"));
        }
        sb.append("\n\n").append(LocaleController.getString("MorsegramBackupInsidePersonal"));
        return sb.toString();
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context mContext;

        ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int position = holder.getAdapterPosition();
            return position == esportaRow || position == ripristinaRow;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            final View view;
            switch (viewType) {
                case TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                default:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case TYPE_HEADER: {
                    final HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == fileHeaderRow) {
                        cell.setText(LocaleController.getString("MorsegramBackupFile"));
                    } else {
                        cell.setText(LocaleController.getString("MorsegramBackupContent"));
                    }
                    break;
                }
                case TYPE_SETTINGS: {
                    // il valore di un TextSettingsCell e' CORTO per costruzione: in release betterLayout e'
                    // false e onMeasure gli da' meta' della larghezza disponibile (circa 163dp su uno schermo
                    // da 360dp), con una riga sola e i puntini di sospensione. "morsegram-impostazioni.txt" e
                    // "Modelli di widget in attesa: 3" ci si tagliavano entrambi, e sulla riga del ripristino
                    // la parte che spariva era proprio il numero. Il nome del file si dice nel dialogo
                    // dell'esportazione, la frase dei modelli nella riga informativa qui sotto
                    final TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == esportaRow) {
                        cell.setTextAndValue(LocaleController.getString("MorsegramBackupExport"), "", true);
                    } else {
                        final int attesa = BackupWidgets.inAttesa();
                        cell.setTextAndValue(LocaleController.getString("MorsegramBackupImport"),
                                attesa > 0 ? String.valueOf(attesa) : "", false);
                    }
                    break;
                }
                default: {
                    final TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    final boolean ultima = position == contenutoInfoRow;
                    cell.setBackground(Theme.getThemedDrawableByKey(mContext,
                            ultima ? R.drawable.greydivider_bottom : R.drawable.greydivider,
                            Theme.key_windowBackgroundGrayShadow));
                    if (position == fileInfoRow) {
                        // qui si parla del file che scrive Morsegram, quindi "non ci sono il PIN, le chat
                        // nascoste e le chiavi" e' vero: nell'anteprima del ripristino non lo sarebbe (e il
                        // soggetto sta nel testo, non solo in questo commento: la riga si legge anche sotto
                        // "Ripristina da un file", e un file scelto dall'utente puo' contenere qualunque cosa)
                        final StringBuilder info = new StringBuilder(512);
                        info.append(LocaleController.getString("MorsegramBackupHow")).append("\n\n")
                                .append(LocaleController.getString("MorsegramBackupNotKeys"));
                        // a piena larghezza e su piu' righe, cosi' il numero non si tronca
                        final int attesa = BackupWidgets.inAttesa();
                        if (attesa > 0) {
                            info.append("\n\n").append(LocaleController.formatString(
                                    "MorsegramBackupWidgetPending", 0, attesa));
                        }
                        cell.setText(info.toString());
                    } else {
                        // tre cose, in chiaro: cosa entra, cosa NON entra (e perche'), e che Telegram non si tocca
                        cell.setText(cosaEntra() + "\n\n"
                                + LocaleController.getString("MorsegramBackupOutside") + "\n\n"
                                + LocaleController.getString("MorsegramBackupNotTelegram"));
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == fileHeaderRow || position == contenutoHeaderRow) {
                return TYPE_HEADER;
            }
            if (position == fileInfoRow || position == contenutoInfoRow) {
                return TYPE_INFO;
            }
            return TYPE_SETTINGS;
        }
    }
}

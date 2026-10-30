/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.backup;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.List;

import it.iotatec.morsegram.MorsegramPrefs;
import it.iotatec.morsegram.widget.MorsegramWidgets;
import it.iotatec.morsegram.widget.WidgetConfig;

/**
 * I widget nel salvataggio delle impostazioni, e l'unica cosa che qui NON si trasporta: l'{@code appWidgetId}.
 *
 * <p>Gli id li assegna il launcher quando si piazza un widget, quindi dopo una reinstallazione quelli del
 * backup non corrispondono a niente. Si salva allora un <b>modello</b> per widget (tipo, chat, tema, angoli,
 * trasparenza, senza id) e lo si applica a un widget dello stesso tipo che viene piazzato: la schermata di
 * configurazione si apre comunque (i nostri widget hanno {@code android:configure}), quindi l'utente vede i
 * valori ripristinati e li cambia se non li vuole.
 *
 * <p><b>Un modello si consuma.</b> Quello applicato si segna come usato, cosi' il secondo modello dello stesso
 * tipo arriva al secondo widget (prima tutti i widget nuovi di quel tipo ricevevano la chat del primo, anche
 * mesi dopo) e la riga "Modelli di widget in attesa" scende fino a spegnersi da sola.
 *
 * <p><b>La chat si ricontrolla al momento del piazzamento</b>, non solo quando si scrive il file: una chat
 * nascosta DOPO il backup non deve riaprirsi dalla schermata iniziale. E la chat appartiene a un ACCOUNT, che
 * viaggia per user id e non per slot: lo slot lo decide l'ordine di login su questo telefono. Un modello la cui
 * chat non si puo' rimettere — l'account non e' collegato qui, oppure la chat e' nascosta adesso — si salta
 * senza consumarlo, e al widget non arriva niente: meglio un widget da configurare che un widget con l'aspetto
 * giusto e la chat di un altro account.
 *
 * <p>Mai nel file: la cache di contenuto dei widget (nome della chat, anteprima dell'ultimo messaggio, ora, non
 * letti). E' contenuto, non configurazione, ed e' lo stesso dato che {@code WidgetConfig.wipeContent} cancella
 * appena la verifica sulle chat nascoste non e' possibile.
 */
public final class BackupWidgets {

    /** Quanti modelli riletti. */
    private static final String KEY_N = "tpl.n";
    private static final String KEY_FATTO = "tpl.fatto.";
    /** Segno sul MODELLO (non sul widget): un modello applicato non si riusa. */
    private static final String CAMPO_USATO = "usato";

    private BackupWidgets() {
    }

    private static SharedPreferences prefs() {
        return MorsegramPrefs.file(WidgetConfig.FILE);
    }

    /* ======================================================================================= in uscita */

    /**
     * I widget piazzati adesso, come modelli senza id. Un widget la cui chat e' nascosta (o non verificabile)
     * non entra affatto: il suo dialog id uscirebbe da qui dopo essere stato escluso da {@code hidden.ids}.
     */
    static List<MorsegramBackup.Widget> piazzati(MorsegramBackup.Nascoste nascoste) {
        final List<MorsegramBackup.Widget> out = new ArrayList<>();
        final Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return out;
        }
        for (int tipo = MorsegramWidgets.TYPE_AIRPLANE; tipo <= MorsegramWidgets.TYPE_CHAT; tipo++) {
            final int[] ids;
            try {
                ids = MorsegramWidgets.ids(ctx, tipo);
            } catch (Throwable e) {
                FileLog.e(e);
                continue;
            }
            for (int id : ids) {
                final long dialogId = WidgetConfig.dialogId(id);
                final int slot = WidgetConfig.storedAccount(id);
                if (dialogId != 0 && slot < 0) {
                    // slot salvato non valido su un widget che MOSTRA una chat: il controllo delle nascoste
                    // cadrebbe sulla lista di un altro account (e' lo stesso -1 per cui WidgetDialog.read
                    // cancella la cache e non disegna niente). Questo metodo esiste per tenere fuori gli id
                    // nascosti, quindi nel dubbio il widget non entra affatto, come fa BackupStore.nascoste
                    continue;
                }
                if (dialogId != 0 && nascoste != null && nascoste.nascosta(slot, dialogId)) {
                    continue;
                }
                // Math.max solo per COSTRUIRE il modello: un widget senza chat (aereo, non letti) non ha uno
                // slot salvato e il suo slot non significa niente. Quello che conta e' lo user id: lo slot non
                // e' portabile, e un widget senza chat non ne ha nessuno (0)
                final MorsegramBackup.Widget w = new MorsegramBackup.Widget(tipo, Math.max(0, slot), dialogId,
                        WidgetConfig.theme(id), WidgetConfig.corners(id), WidgetConfig.opacity(id),
                        dialogId == 0 ? 0 : MorsegramPrefs.userId(slot));
                if (w.valido() && !out.contains(w)) {
                    out.add(w);     // due widget identici sono un modello solo
                }
            }
        }
        return out;
    }

    /* ======================================================================================= in entrata */

    /**
     * Scrive i modelli ripristinati, <b>sostituendo</b> quelli di prima: anche quelli ancora IN ATTESA di un
     * piazzamento, che vengono buttati via. Per questo l'anteprima lo dice prima
     * ({@code MorsegramBackupWidgetsReplace}, quando {@link #inAttesa()} e' maggiore di zero) e
     * {@code BackupStore.completa} non la chiama a vuoto quando il file non porta widget: il numero della riga
     * "Modelli di widget in attesa" non deve scendere senza che nessuno l'abbia annunciato.
     *
     * @return quanti modelli sono stati salvati
     */
    static int salvaModelli(List<MorsegramBackup.Widget> modelli) {
        final SharedPreferences p = prefs();
        final SharedPreferences.Editor e = p.edit();
        final int vecchi = p.getInt(KEY_N, 0);
        for (int i = 0; i < vecchi; i++) {
            e.remove(campo(i, "tipo")).remove(campo(i, "slot")).remove(campo(i, "dialog"))
                    .remove(campo(i, "tema")).remove(campo(i, "angoli")).remove(campo(i, "opacita"))
                    .remove(campo(i, "user")).remove(campo(i, CAMPO_USATO));
        }
        int n = 0;
        if (modelli != null) {
            for (MorsegramBackup.Widget w : modelli) {
                if (w == null || !w.valido() || n >= MorsegramBackup.MAX_WIDGET) {
                    continue;
                }
                e.putInt(campo(n, "tipo"), w.tipo)
                        .putInt(campo(n, "slot"), w.slot)
                        .putLong(campo(n, "dialog"), w.dialogId)
                        .putInt(campo(n, "tema"), w.tema)
                        .putInt(campo(n, "angoli"), w.angoli)
                        .putInt(campo(n, "opacita"), w.opacita)
                        .putLong(campo(n, "user"), w.userId);
                n++;
            }
        }
        e.putInt(KEY_N, n);
        e.apply();
        return n;
    }

    private static String campo(int i, String nome) {
        return "tpl." + i + "." + nome;
    }

    /**
     * Chiamata quando si apre la configurazione di un widget appena piazzato: se c'e' un modello del suo tipo e
     * questo widget non e' ancora stato configurato, lo applica. Si segna di averlo fatto, cosi' il modello non
     * torna a sovrascrivere le scelte dell'utente ogni volta che riapre la pagina.
     *
     * <p><b>Mai a un widget che l'utente ha gia' configurato.</b> Tutti e cinque i nostri
     * {@code appwidget-provider} dichiarano {@code reconfigurable}, quindi il launcher apre questa pagina anche
     * per un widget VIVO quando l'utente tocca la matita: per un widget piazzato prima del ripristino il segno
     * {@code tpl.fatto} non c'e', e senza questo controllo il modello gli cambierebbe tema, angoli, opacita' e
     * perfino la chat che l'utente aveva scelto — giorni dopo e senza anteprima.
     *
     * <p>Il modello applicato si <b>consuma</b> ({@link #CAMPO_USATO}), quindi al widget successivo dello
     * stesso tipo tocca il modello successivo e non di nuovo il primo.
     *
     * @return true se qualcosa e' stato applicato (la pagina si aprira' con i valori ripristinati)
     */
    public static boolean proponi(int tipo, int widgetId) {
        final SharedPreferences p = prefs();
        if (widgetId == 0 || p.getBoolean(KEY_FATTO + widgetId, false)) {
            return false;
        }
        p.edit().putBoolean(KEY_FATTO + widgetId, true).apply();
        if (WidgetConfig.isConfigured(widgetId)) {
            return false;   // il segno e' gia' scritto: su questo widget non si riprova piu'
        }
        final int n = p.getInt(KEY_N, 0);
        for (int i = 0; i < n; i++) {
            if (p.getInt(campo(i, "tipo"), 0) != tipo || p.getBoolean(campo(i, CAMPO_USATO), false)) {
                continue;
            }
            final long dialogId = p.getLong(campo(i, "dialog"), 0);
            int slot = -1;
            if (dialogId != 0) {
                // lo slot si ricava ADESSO dallo user id: quello salvato nel file e' lo slot che l'account
                // aveva sull'altro telefono, e con gli account in ordine diverso il widget punterebbe a un
                // altro account — e il controllo delle nascoste cadrebbe sulla lista di quell'altro account,
                // cioe' una chat nascosta tornerebbe leggibile dalla schermata iniziale
                slot = slotDi(p.getLong(campo(i, "user"), 0));
                // la chat si rimette solo se e' ancora leggibile E non nascosta ADESSO: il controllo al
                // momento del piazzamento e' il punto, non quello fatto quando il file e' stato scritto
                if (slot < 0 || BackupStore.nascoste().nascosta(slot, dialogId)) {
                    continue;   // modello non applicabile: si lascia in attesa e si prova il prossimo
                }
            }
            WidgetConfig.setTheme(widgetId, p.getInt(campo(i, "tema"), WidgetConfig.THEME_AUTO));
            WidgetConfig.setCorners(widgetId, p.getInt(campo(i, "angoli"), WidgetConfig.CORNERS_ROUND));
            WidgetConfig.setOpacity(widgetId, p.getInt(campo(i, "opacita"), 100));
            if (dialogId != 0) {
                WidgetConfig.setAccount(widgetId, slot);
                WidgetConfig.setDialogId(widgetId, dialogId);
            }
            p.edit().putBoolean(campo(i, CAMPO_USATO), true).apply();
            try {
                MorsegramWidgets.refresh(tipo);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            return true;
        }
        return false;
    }

    /**
     * Lo slot in cui quell'utente e' collegato ADESSO, -1 se su questo telefono non c'e'. Come
     * {@code BackupStore.nascoste}, prima si inizializza l'applicazione: la pagina di configurazione di un
     * widget puo' aprirsi su un processo appena nato, e senza init {@code UserConfig} non sa ancora niente.
     */
    private static int slotDi(long userId) {
        if (userId == 0) {
            return -1;      // widget senza chat, o file scritto prima che lo user id si trasportasse
        }
        try {
            ApplicationLoader.postInitApplication();
            for (int slot = 0; slot < UserConfig.MAX_ACCOUNT_COUNT; slot++) {
                if (MorsegramPrefs.userId(slot) == userId) {
                    return slot;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return -1;
    }

    /**
     * Quanti modelli sono ancora da proporre: e' il numero che la pagina mostra. I modelli gia' applicati non
     * si contano, altrimenti la riga mostrerebbe lo stesso numero per sempre.
     */
    public static int inAttesa() {
        try {
            final SharedPreferences p = prefs();
            final int n = p.getInt(KEY_N, 0);
            int attesa = 0;
            for (int i = 0; i < n; i++) {
                if (!p.getBoolean(campo(i, CAMPO_USATO), false)) {
                    attesa++;
                }
            }
            return attesa;
        } catch (Throwable ignore) {
            return 0;
        }
    }
}

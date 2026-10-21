/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.hidden;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

import it.iotatec.morsegram.MorsegramApp;
import it.iotatec.morsegram.ui.MorsegramSettingsActivity;

/**
 * Sezione 'Chat nascoste' (S6-UI): sottoclasse di {@link DialogsActivity} con {@code folderId = }{@link HiddenChats#FOLDER_HIDDEN}
 * (modello Archivio, 0 righe upstream): eredita il gating 'sezione secondaria' (back, titolo, niente tab cartelle,
 * una sola pagina, niente FAB, niente hint/contatti/permessi) e le DialogCell complete (typing, online, bozze,
 * contatori, anteprima long-press con 'Mostra chat', action mode con 'Mostra chat' via MorsegramMenu).
 * <ul>
 * <li>{@link #getDialogsArray} restituisce la lista viva di {@link HiddenChats#hiddenDialogs} (ramo frozen via super).</li>
 * <li>Nessuna request per il folder fittizio: offset di caricamento = MAX_VALUE e pinned 'già caricati' PRIMA di
 *     {@code super.onFragmentCreate()} (che chiama loadPinnedDialogs(folderId)); la query cache su folder 7777 è vuota →
 *     end-reached, niente riga shimmer e niente messages.getDialogs/getPinnedDialogs sul wire.</li>
 * <li>Stato vuoto gestito qui: si chiude quando l'ultima chat viene mostrata (dialogsNeedReload) o quando la sezione
 *     è bloccata (onResume); l'apertura passa SEMPRE da {@link #open} (PIN/biometria se bloccata). {@link HiddenLock#lock()}
 *     la chiude in background (marker {@link HiddenLock.HiddenFragment}).</li>
 * <li>Menu '⋯' dell'action bar riusato (come l'Archivio): Blocca ora, Impostazioni Morsegram, Come funziona.</li>
 * </ul>
 * Residui noti (richiederebbero righe upstream in DialogsActivity, lasciati e documentati): lo swipe sinistro di una
 * riga esegue l'azione upstream (addDialogToFolder verso folder 0 = 'togli dall'archivio', no-op visivo per le altre);
 * la riga storie e il campo di ricerca restano quelli della lista principale (la ricerca non trova le chat nascoste:
 * hook H9/H10); 'Elimina' dall'action mode chiude la sezione via folderBecomeEmpty(7777).
 */
public class HiddenChatsActivity extends DialogsActivity implements HiddenLock.HiddenFragment {

    private static Bundle args() {
        final Bundle b = new Bundle();
        b.putInt("folderId", HiddenChats.FOLDER_HIDDEN);
        b.putInt("dialogsType", DIALOGS_TYPE_DEFAULT);
        return b;
    }

    public HiddenChatsActivity() {
        super(args());
    }

    /* ========================================================================================= apertura */

    /** Voce '⋯' della lista chat (MorsegramApp.addItemOptions). */
    public static void open() {
        open(null);
    }

    /**
     * Apre la sezione: se un PIN è impostato e la sessione è bloccata mostra prima il lucchetto
     * ({@link HiddenLock#authenticate} → HiddenLockSheet); senza chat nascoste spiega come nasconderne una (dopo lo
     * sblocco, così il conteggio non è visibile senza PIN). Senza PIN la sezione non è protetta e, se delle chat
     * nascoste esistono, {@link HiddenLock#openUnprotected} lo dice prima di aprirla (una volta, con 'Imposta un PIN').
     *
     * @param from fragment chiamante (pagina impostazioni) o null (LaunchActivity).
     */
    public static void open(BaseFragment from) {
        if (from instanceof HiddenChatsActivity) {
            return;
        }
        final LaunchActivity launch = LaunchActivity.instance;
        final Activity activity = from != null && from.getParentActivity() != null ? from.getParentActivity() : launch;
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final int account = from != null ? from.getCurrentAccount() : UserConfig.selectedAccount;
        final Runnable present = () -> {
            if (HiddenChats.count(account) == 0) {
                showNoHiddenChats(activity);
                return;
            }
            final HiddenChatsActivity fragment = new HiddenChatsActivity();
            fragment.setCurrentAccount(account);
            if (from != null && from.getParentLayout() != null) {
                from.presentFragment(fragment);
            } else if (launch != null && !launch.isFinishing()) {
                launch.presentFragment(fragment);
            }
        };
        if (HiddenLock.hasPin()) {
            HiddenLock.authenticate(activity, present, null);
        } else {
            // senza PIN la sezione non è protetta: la prima volta l'utente lo scopre da un avviso, non dal fatto
            // che la sezione si apre senza chiedere nulla (può impostare il PIN da lì o aprire comunque)
            HiddenLock.openUnprotected(activity, account, present);
        }
    }

    private static void showNoHiddenChats(Context context) {
        try {
            final AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle(LocaleController.getString("MorsegramHiddenChats"));
            builder.setMessage(LocaleController.getString("MorsegramNoHiddenChats"));
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            builder.show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /* ======================================================================================== lifecycle */

    @Override
    public boolean onFragmentCreate() {
        try {
            final UserConfig userConfig = getUserConfig();
            if (userConfig.getDialogLoadOffsets(HiddenChats.FOLDER_HIDDEN)[UserConfig.i_dialogsLoadOffsetId] != Integer.MAX_VALUE) {
                // loadDialogs(7777, …, fromCache=false) ritorna subito con end-reached: mai messages.getDialogs folder_id=7777
                userConfig.setDialogsLoadOffset(HiddenChats.FOLDER_HIDDEN, Integer.MAX_VALUE, 0, 0, 0, 0, 0);
            }
            if (!userConfig.isPinnedDialogsLoaded(HiddenChats.FOLDER_HIDDEN)) {
                // super.onFragmentCreate chiama loadPinnedDialogs(folderId): con il flag già a true non parte messages.getPinnedDialogs
                userConfig.setPinnedDialogsLoaded(HiddenChats.FOLDER_HIDDEN, true);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (!super.onFragmentCreate()) {
            return false;
        }
        final MessagesController mc = getMessagesController();
        if (!mc.isDialogsEndReached(HiddenChats.FOLDER_HIDDEN)) {
            // query cache su folder_id=7777 (vuota) → processLoadedDialogs: offset MAX_VALUE → dialogsEndReached, niente shimmer
            mc.loadDialogs(HiddenChats.FOLDER_HIDDEN, -1, 100, true);
        }
        HiddenLock.setSecure(true);
        return true;
    }

    @Override
    public View createView(Context context) {
        final View view = super.createView(context);
        try {
            actionBar.setTitle(LocaleController.getString("MorsegramHiddenChats"));
            final ActionBarMenuItem options = actionBar.createMenu().getItem(4);
            if (options != null) {
                options.setOnClickListener(v -> showOptions(options));
                options.setOnLongClickListener(v -> {
                    showOptions(options);
                    return true;
                });
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return view;
    }

    /** Menu '⋯' della sezione (sostituisce quello della lista principale, come fa l'Archivio). */
    private void showOptions(View anchor) {
        if (getParentActivity() == null) {
            return;
        }
        final ItemOptions io = ItemOptions.makeOptions(this, anchor);
        io.setColors(getThemedColor(Theme.key_actionBarDefaultTitle), getThemedColor(Theme.key_actionBarDefaultTitle));
        io.setDimAlpha(0x08);
        final int lockRes = MorsegramApp.lockIconRes();
        io.addIf(HiddenLock.hasPin(), lockRes != 0 ? lockRes : R.drawable.outline_header_lock_24, LocaleController.getString("MorsegramLockNow"), () -> {
            // chiude questa sezione e ogni chat nascosta aperta (HiddenLock.lock), come il rientro dal background
            AndroidUtilities.runOnUIThread(HiddenLock::lock);
        });
        io.add(R.drawable.msg_customize, LocaleController.getString("MorsegramSettings"), () -> presentFragment(new MorsegramSettingsActivity()));
        io.add(R.drawable.msg_help, LocaleController.getString(R.string.HowDoesItWork), this::showHelp);
        io.show();
        io.setTranslationY(-AndroidUtilities.dp(64));
    }

    private void showHelp() {
        if (getParentActivity() == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(LocaleController.getString("MorsegramHiddenChats"));
        builder.setMessage(LocaleController.getString("MorsegramHiddenChatsInfo") + "\n\n" + LocaleController.getString("MorsegramHiddenPrivacyInfo"));
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(builder.create());
    }

    @Override
    public void onResume() {
        super.onResume();
        if (HiddenLock.isLocked()) {
            // HiddenLock.lock() (ActivityLifecycleCallbacks) normalmente ci ha già rimossi; rete di sicurezza
            AndroidUtilities.runOnUIThread(this::closeSection);
            return;
        }
        HiddenLock.setSecure(true);
        checkEmpty();
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        if (!HiddenLock.isLocked()) {
            HiddenLock.setSecure(true);
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        // FLAG_SECURE resta solo se un'altra pagina di chat nascosta è ancora aperta (es. pannello destro del Fold)
        if (!HiddenLock.anyHiddenFragmentOpen(this)) {
            HiddenLock.setSecure(false);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        super.didReceivedNotification(id, account, args);
        if (id == NotificationCenter.dialogsNeedReload && account == currentAccount) {
            checkEmpty();
        }
    }

    /** L'ultima chat nascosta è stata mostrata (menu anteprima / action mode): la sezione non ha più nulla da elencare. */
    private void checkEmpty() {
        if (finishing || parentLayout == null) {
            return;
        }
        if (HiddenChats.count(currentAccount) == 0) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("morsegram hidden section: empty, close");
            }
            AndroidUtilities.runOnUIThread(this::closeSection);
        }
    }

    private void closeSection() {
        if (finishing || parentLayout == null) {
            return;
        }
        try {
            finishFragment();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /* ============================================================================================ dati */

    /**
     * Unico punto in cui DialogsActivity/DialogsAdapter leggono la lista: la lista viva delle chat nascoste caricate
     * (ordine del dialogComparator, pin locali inclusi). Ramo frozen (swipe/rimozione animata) via super: la copia
     * congelata è privata; se è vuota si torna alla lista viva (innocuo).
     */
    @NonNull
    @Override
    public ArrayList<TLRPC.Dialog> getDialogsArray(int currentAccount, int dialogsType, int folderId, boolean frozen) {
        if (frozen) {
            final ArrayList<TLRPC.Dialog> snapshot = super.getDialogsArray(currentAccount, dialogsType, folderId, true);
            if (snapshot != null && !snapshot.isEmpty()) {
                return snapshot;
            }
        }
        return HiddenChats.hiddenDialogs(currentAccount);
    }

    @Override
    public INavigationLayout.BackButtonState getBackButtonState() {
        return INavigationLayout.BackButtonState.BACK;
    }
}

/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.collection.LongSparseArray;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Pin locali illimitati (MORSEGRAM.md mission 2): chat fissate in alto SOLO su questo dispositivo, oltre il limite
 * dei pin server di Telegram.
 * <p>
 * Puro ordinamento client-side: nessuna richiesta messages.toggleDialogPin/reorderPinnedDialogs (restano ai pin
 * server upstream), nessuna spunta/online/typing toccati (ToS 1.4). Ordine finale della lista principale:
 * [riga Archivio] → [pin server per pinnedNum] → [PIN LOCALI, ultimo fissato in alto] → [resto per data].
 * Il blocco locale sta SOTTO i pin server e non si fonde con loro perché upstream assume che i pin server siano un
 * prefisso contiguo di getDialogs(folderId) e fa {@code break} al primo {@code !dialog.pinned}
 * (MessagesController.pinDialog / reorderPinnedDialogs / loadPinnedDialogs, DialogsActivity.getPinnedCount /
 * performSelectedDialogsAction, SwipeController): un pin locale non è {@code pinned}, quindi chiude quei loop.
 * <p>
 * Un solo hook di 2 righe in {@code MessagesController.dialogComparator} (via {@link MorsegramDialogs#compare})
 * propaga l'ordine a tutta la UI, perché {@code sortDialogs()} ricostruisce dialogsByFolder/dialogsForward ecc.
 * copiando l'ordine di allDialogs. Archivio (folder_id != 0) e cartelle (dialogsType 7/8) ignorano i pin locali.
 * Un dialogo sia pin server sia pin locale: vince il server finché {@code dialog.pinned}.
 * <p>
 * Persistenza: {@link MorsegramPrefs#account(int)} chiave {@link MorsegramPrefs#KEY_PINS_ORDER} (CSV di dialog id,
 * ordine = ordine di visualizzazione, chiave per user id). Stato in memoria per slot account, copy-on-write a ogni
 * modifica: le letture del comparatore (n log n a ogni messaggio) sono lock-free, O(1) e senza allocazioni.
 */
public final class LocalPins {

    /** id voce overflow dell'action mode multi-selezione (upstream usa 100-111 in DialogsActivity). */
    public static final int ACTION_ID = 190;
    /** Ambra Morsegram: contorno vs pin pieno grigio del server, distinguibile in chiaro e scuro. */
    public static final int GLYPH_COLOR = 0xFFF5A623;

    /**
     * MessagesController.DIALOGS_LOAD_TYPE_UNKNOWN (campo privato non statico = 3): loadType con cui
     * loadUnknownDialog passa a processLoadedDialogs una chat fuori dalla finestra caricata.
     * Checklist rebase: {@code grep -n "DIALOGS_LOAD_TYPE_UNKNOWN = " MessagesController.java} deve restare 3.
     */
    private static final int DIALOGS_LOAD_TYPE_UNKNOWN = 3;
    private static final int NONE = Integer.MAX_VALUE;
    private static final long ENSURE_LOADED_DELAY_MS = 2000;

    /** Stato immutabile per slot account; AtomicReferenceArray per letture lock-free da qualunque thread. */
    private static final AtomicReferenceArray<State> states = new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);
    /** dialog id con una messages.getPeerDialogs in volo, per slot account (solo thread UI). */
    @SuppressWarnings("unchecked")
    private static final HashSet<Long>[] pendingLoads = new HashSet[UserConfig.MAX_ACCOUNT_COUNT];
    /** id drawable del modulo app (morsegram_pin_local) da MorsegramApp.onCreate; 0 = fallback msg_pin_mini ambra. */
    private static volatile int glyphRes;
    private static Drawable glyph;

    private static final class State {
        final long userId;
        /** ordine di visualizzazione: indice 0 = più in alto. */
        final long[] order;
        final LongSparseArray<Integer> rank;

        State(long userId, long[] order) {
            this.userId = userId;
            this.order = order;
            rank = new LongSparseArray<>(order.length);
            for (int i = 0; i < order.length; i++) {
                rank.put(order[i], i);
            }
        }

        boolean contains(long dialogId) {
            return rank.indexOfKey(dialogId) >= 0;
        }
    }

    private LocalPins() {
    }

    /* ======================================================================================= stato */

    public static void setGlyphRes(int res) {
        glyphRes = res;
        glyph = null;
    }

    /**
     * Stato dello slot, ricaricato se l'utente loggato è cambiato (logout / altro utente nello stesso slot).
     * Legge {@code UserConfig.clientUserId} (campo pubblico tenuto allineato a currentUser da setCurrentUser /
     * loadConfig / clearConfig) invece di getClientUserId(), che è synchronized: questa è la lettura calda.
     * userId 0 (slot vuoto o config non ancora caricata) → null, niente cache.
     */
    private static State state(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return null;
        }
        final long userId = UserConfig.getInstance(account).clientUserId;
        if (userId == 0) {
            return null;
        }
        State s = states.get(account);
        if (s == null || s.userId != userId) {
            s = new State(userId, parse(MorsegramPrefs.account(account).getString(MorsegramPrefs.KEY_PINS_ORDER, "")));
            states.set(account, s);
            // le chat fissate fuori dalla finestra caricata (es. molto vecchie) vanno recuperate dal server
            scheduleEnsureLoaded(account, ENSURE_LOADED_DELAY_MS);
        }
        return s;
    }

    private static long[] parse(String csv) {
        if (csv == null || csv.isEmpty()) {
            return new long[0];
        }
        final String[] parts = csv.split(",");
        final long[] tmp = new long[parts.length];
        int n = 0;
        for (String p : parts) {
            try {
                final long id = Long.parseLong(p.trim());
                boolean dup = false;
                for (int i = 0; i < n; i++) {
                    if (tmp[i] == id) {
                        dup = true;
                        break;
                    }
                }
                if (!dup && id != 0) {
                    tmp[n++] = id;
                }
            } catch (NumberFormatException ignore) {
            }
        }
        if (n == tmp.length) {
            return tmp;
        }
        final long[] out = new long[n];
        System.arraycopy(tmp, 0, out, 0, n);
        return out;
    }

    private static String csv(long[] order) {
        final StringBuilder sb = new StringBuilder(order.length * 12);
        for (long id : order) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /** Sostituisce lo stato (copy-on-write), persiste e riallinea la lista chat. Thread UI. */
    private static void commit(int account, State next, long unpinnedId) {
        states.set(account, next);
        MorsegramPrefs.account(account).edit().putString(MorsegramPrefs.KEY_PINS_ORDER, csv(next.order)).apply();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("morsegram local pins account " + account + " -> " + next.order.length);
        }
        final MessagesController mc = MessagesController.getInstance(account);
        mc.sortDialogs(null);
        if (unpinnedId != 0) {
            // come MessagesController.pinDialog(pin=false): una chat tornata all'ordine per data che finisce in coda
            // alla finestra caricata viene tolta dalla lista (ricompare scorrendo), finché la fine non è raggiunta
            final ArrayList<TLRPC.Dialog> dialogs = mc.getDialogs(0);
            final TLRPC.Dialog d = mc.dialogs_dict.get(unpinnedId);
            if (d != null && !dialogs.isEmpty() && dialogs.get(dialogs.size() - 1) == d && !mc.isDialogsEndReached(0)) {
                dialogs.remove(dialogs.size() - 1);
            }
        }
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        ensureLoaded(account);
    }

    /* ==================================================================================== letture */

    private static int rank(State s, TLRPC.Dialog d) {
        if (d == null || d.pinned || d.folder_id != 0 || d instanceof TLRPC.TL_dialogFolder) {
            return NONE;
        }
        final Integer r = s.rank.get(d.id);
        return r == null ? NONE : r;
    }

    /**
     * Hook MessagesController.dialogComparator (via MorsegramDialogs.compare), chiamato quando entrambi i dialoghi
     * sono non-folder e non pin server: locale prima di non-locale, tra due locali per rank; 0 se nessuno dei due è
     * locale così upstream prosegue con il confronto per data.
     */
    public static int compare(int account, TLRPC.Dialog d1, TLRPC.Dialog d2) {
        final State s = state(account);
        if (s == null || s.order.length == 0) {
            return 0;
        }
        final int r1 = rank(s, d1);
        final int r2 = rank(s, d2);
        if (r1 == r2) {
            return 0;
        }
        return r1 < r2 ? -1 : 1;
    }

    /** true se il dialogo è fissato localmente (anche se è pure pin server o archiviato). */
    public static boolean isPinned(int account, long dialogId) {
        final State s = state(account);
        return s != null && s.contains(dialogId);
    }

    /** Glifo locale: fissato localmente, non pin server (vince il server), non archiviato, non riga Archivio. */
    public static boolean isLocalGlyph(int account, TLRPC.Dialog d) {
        if (d == null || d.pinned || d.folder_id != 0 || d instanceof TLRPC.TL_dialogFolder) {
            return false;
        }
        final State s = state(account);
        return s != null && s.contains(d.id);
    }

    /** Variante per DialogCell.onDraw: nelle cartelle (dialogsType 7/8) i pin sono quelli del filtro, mai locali. */
    public static boolean isLocalGlyph(int account, long dialogId, int dialogsType) {
        if (dialogsType == 7 || dialogsType == 8) {
            return false;
        }
        final State s = state(account);
        if (s == null || !s.contains(dialogId)) {
            return false;
        }
        return isLocalGlyph(account, MessagesController.getInstance(account).dialogs_dict.get(dialogId));
    }

    /** Numero di pin locali dell'utente dello slot (pagina Morsegram). */
    public static int count(int account) {
        final State s = state(account);
        return s == null ? 0 : s.order.length;
    }

    /** Idoneo al pin locale: esiste, non è la riga Archivio, non è pin server, è nella lista principale. */
    private static boolean eligible(TLRPC.Dialog d) {
        return d != null && !(d instanceof TLRPC.TL_dialogFolder) && !d.pinned && d.folder_id == 0 && !DialogObject.isFolderDialogId(d.id);
    }

    /* =================================================================================== modifiche */

    /** Fissa localmente (in testa al blocco locale). Thread UI. @return false se già fissato o slot senza utente. */
    public static boolean pin(int account, long dialogId) {
        final ArrayList<Long> ids = new ArrayList<>(1);
        ids.add(dialogId);
        return pinAll(account, ids) > 0;
    }

    /** Rimuove il pin locale. Thread UI. @return false se non era fissato. */
    public static boolean unpin(int account, long dialogId) {
        final ArrayList<Long> ids = new ArrayList<>(1);
        ids.add(dialogId);
        return unpinAll(account, ids) > 0;
    }

    /** @return il nuovo stato (true = ora fissato). */
    public static boolean toggle(int account, long dialogId) {
        if (isPinned(account, dialogId)) {
            unpin(account, dialogId);
            return false;
        }
        pin(account, dialogId);
        return true;
    }

    /** Un solo commit (sortDialogs + dialogsNeedReload) per tutto il batch; i nuovi vanno in testa, in ordine. */
    private static int pinAll(int account, ArrayList<Long> dialogIds) {
        final State s = state(account);
        if (s == null || dialogIds == null || dialogIds.isEmpty()) {
            return 0;
        }
        final ArrayList<Long> fresh = new ArrayList<>(dialogIds.size());
        for (Long id : dialogIds) {
            if (id != null && id != 0 && !s.contains(id) && !fresh.contains(id)) {
                fresh.add(id);
            }
        }
        if (fresh.isEmpty()) {
            return 0;
        }
        final long[] order = new long[s.order.length + fresh.size()];
        for (int i = 0; i < fresh.size(); i++) {
            order[i] = fresh.get(i);
        }
        System.arraycopy(s.order, 0, order, fresh.size(), s.order.length);
        commit(account, new State(s.userId, order), 0);
        return fresh.size();
    }

    private static int unpinAll(int account, ArrayList<Long> dialogIds) {
        final State s = state(account);
        if (s == null || dialogIds == null || dialogIds.isEmpty()) {
            return 0;
        }
        final long[] tmp = new long[s.order.length];
        int n = 0;
        int removed = 0;
        long lastRemoved = 0;
        for (long id : s.order) {
            if (dialogIds.contains(id)) {
                removed++;
                lastRemoved = id;
            } else {
                tmp[n++] = id;
            }
        }
        if (removed == 0) {
            return 0;
        }
        final long[] order = new long[n];
        System.arraycopy(tmp, 0, order, 0, n);
        commit(account, new State(s.userId, order), removed == 1 ? lastRemoved : 0);
        return removed;
    }

    /* ============================================================================= entry point UX */

    /**
     * Hook DialogsActivity.performSelectedDialogsAction: raggiunto il limite dei pin server (unico punto in cui
     * upstream lo "surfaces", con LimitReachedBottomSheet.TYPE_PIN_DIALOGS) fissa localmente le chat selezionate
     * invece di mostrare il paywall Premium. Copre pinItem, pin2Item e swipe-to-pin. Se le idonee sono TUTTE già
     * fissate localmente le sfissa (stessa semantica toggle di {@link #onActionModeItem}: la cella mostra 'Unpin'
     * nello swipe perché getIsPinned() è vero anche per i pin locali).
     *
     * @return true se gestito (il chiamante fa hideActionMode(false); return), false → upstream mostra il sheet.
     */
    public static boolean pinSelected(BaseFragment fragment, ArrayList<Long> selectedDialogs) {
        return applyToSelection(fragment, selectedDialogs);
    }

    /**
     * Semantica comune di pinSelected / onActionModeItem: fissa le idonee non ancora locali; se non ce ne sono ma
     * alcune sono già locali, le sfissa. Un solo commit per batch, bulletin con conteggio.
     *
     * @return false se nessuna chat selezionata è idonea (riga Archivio, pin server, archiviate, sconosciute).
     */
    private static boolean applyToSelection(BaseFragment fragment, ArrayList<Long> selectedDialogs) {
        if (fragment == null || selectedDialogs == null) {
            return false;
        }
        final int account = fragment.getCurrentAccount();
        final MessagesController mc = MessagesController.getInstance(account);
        final ArrayList<Long> toPin = new ArrayList<>();
        final ArrayList<Long> local = new ArrayList<>();
        for (Long id : selectedDialogs) {
            if (id == null || !eligible(mc.dialogs_dict.get(id))) {
                continue;
            }
            if (isPinned(account, id)) {
                local.add(id);
            } else {
                toPin.add(id);
            }
        }
        if (toPin.isEmpty() && local.isEmpty()) {
            return false;
        }
        if (toPin.isEmpty()) {
            showBulletin(fragment, false, unpinAll(account, local));
        } else {
            showBulletin(fragment, true, pinAll(account, toPin) + local.size());
        }
        return true;
    }

    /**
     * Hook DialogsActivity.showChatPreview (menu long-press sull'avatar, solo !AndroidUtilities.isTablet()): voce
     * 'Fissa in alto (locale)' / 'Rimuovi pin locale'. Compare anche quando upstream nasconde la propria voce Pin
     * per limite raggiunto. null = nessuna voce (cartelle, archivio, riga Archivio, pin server).
     */
    public static View previewMenuItem(BaseFragment fragment, long dialogId, int dialogsType) {
        if (fragment == null || fragment.getParentActivity() == null || dialogsType == 7 || dialogsType == 8) {
            return null;
        }
        final int account = fragment.getCurrentAccount();
        if (!eligible(MessagesController.getInstance(account).dialogs_dict.get(dialogId))) {
            return null;
        }
        final boolean pinned = isPinned(account, dialogId);
        final ActionBarMenuSubItem item = new ActionBarMenuSubItem(fragment.getParentActivity(), false, false);
        item.setTextAndIcon(LocaleController.getString(pinned ? "MorsegramUnpinLocal" : "MorsegramPinLocal"), pinned ? R.drawable.msg_unpin : R.drawable.msg_pin);
        item.setMinimumWidth(160);
        item.setOnClickListener(v -> {
            fragment.finishPreviewFragment();
            // stesso ritardo della voce Pin upstream: la lista è 'frozen' durante la chiusura dell'anteprima
            AndroidUtilities.runOnUIThread(() -> {
                final boolean now = toggle(account, dialogId);
                showBulletin(fragment, now, 1);
            }, 100);
        });
        return item;
    }

    /**
     * Hook DialogsActivity.createActionMode: voce nell'overflow '⋯' dell'action mode multi-selezione, necessaria su
     * tablet / Fold aperto dove isTablet() disabilita il menu anteprima. Visibilità e testo decisi all'apertura del
     * sottomenu via setSubMenuDelegate (upstream non ne imposta uno su otherItem: grep in checklist rebase).
     */
    public static ActionBarMenuSubItem actionModeItem(ActionBarMenuItem otherItem, BaseFragment fragment, Utilities.Callback0Return<ArrayList<Long>> selected, Utilities.Callback0Return<Integer> dialogsType) {
        if (otherItem == null || fragment == null) {
            return null;
        }
        final ActionBarMenuSubItem item = otherItem.addSubItem(ACTION_ID, R.drawable.msg_pin, LocaleController.getString("MorsegramPinLocal"));
        otherItem.setSubMenuDelegate(new ActionBarMenuItem.ActionBarSubMenuItemDelegate() {
            @Override
            public void onShowSubMenu() {
                final Integer type = dialogsType != null ? dialogsType.run() : null;
                final ArrayList<Long> ids = selected != null ? selected.run() : null;
                boolean show = ids != null && (type == null || type != 7 && type != 8);
                boolean allLocal = true;
                int eligibleCount = 0;
                if (show) {
                    final int account = fragment.getCurrentAccount();
                    final MessagesController mc = MessagesController.getInstance(account);
                    for (Long id : ids) {
                        if (id == null || !eligible(mc.dialogs_dict.get(id))) {
                            continue;
                        }
                        eligibleCount++;
                        if (!isPinned(account, id)) {
                            allLocal = false;
                        }
                    }
                    show = eligibleCount > 0;
                }
                item.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) {
                    item.setTextAndIcon(LocaleController.getString(allLocal ? "MorsegramUnpinLocal" : "MorsegramPinLocal"), allLocal ? R.drawable.msg_unpin : R.drawable.msg_pin);
                }
            }

            @Override
            public void onHideSubMenu() {
            }
        });
        return item;
    }

    /**
     * Hook DialogsActivity onItemClick(id == ACTION_ID): se tutte le idonee sono già locali le sfissa, altrimenti
     * fissa le idonee. Il chiamante passa una COPIA di selectedDialogs e poi fa hideActionMode(false).
     */
    public static void onActionModeItem(BaseFragment fragment, ArrayList<Long> selectedDialogs) {
        applyToSelection(fragment, selectedDialogs);
    }

    private static void showBulletin(BaseFragment fragment, boolean pinned, int count) {
        if (count <= 0 || fragment == null || !BulletinFactory.canShowBulletin(fragment)) {
            return;
        }
        try {
            final String text;
            if (count == 1) {
                text = LocaleController.getString(pinned ? "MorsegramPinnedLocally" : "MorsegramUnpinnedLocally");
            } else {
                text = LocaleController.formatString(pinned ? "MorsegramLocalPinsCount" : "MorsegramLocalPinsRemovedCount", 0, count);
            }
            BulletinFactory.of(fragment).createSimpleBulletin(pinned ? R.raw.ic_pin : R.raw.ic_unpin, text).show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /* ======================================================================================= glifo */

    /**
     * Hook DialogCell.onDraw: contorno push_pin ambra (drawable del modulo app, id da MorsegramApp.onCreate;
     * fallback msg_pin_mini tinto). Lazy, condiviso, solo thread UI; il pill resta quello upstream.
     */
    public static Drawable glyph() {
        Drawable d = glyph;
        if (d == null) {
            final Context ctx = ApplicationLoader.applicationContext;
            final int res = glyphRes;
            if (res != 0) {
                try {
                    d = ctx.getDrawable(res);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            if (d == null) {
                d = ctx.getDrawable(R.drawable.msg_pin_mini);
            }
            d = d.mutate();
            d.setColorFilter(new PorterDuffColorFilter(GLYPH_COLOR, PorterDuff.Mode.SRC_IN));
            glyph = d;
        }
        return d;
    }

    /* ============================================================================== ensureLoaded */

    /** MorsegramApp.onResume: ritenta il recupero delle chat fissate ancora assenti (es. era offline). */
    public static void onAppResume() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (states.get(a) != null) {
                scheduleEnsureLoaded(a, ENSURE_LOADED_DELAY_MS);
            }
        }
    }

    private static void scheduleEnsureLoaded(int account, long delay) {
        AndroidUtilities.runOnUIThread(() -> ensureLoaded(account), delay);
    }

    /**
     * Chat fissate localmente ma fuori dalla finestra caricata (es. vecchie oltre le prime pagine): per ciascun id
     * assente da dialogs_dict replica MessagesController.loadUnknownDialog (protected): risolve il peer (memoria,
     * poi DB su globalQueue: getUserSync/getChatSync bloccano e NON vanno chiamati dal thread UI né dalla storage
     * queue), messages.getPeerDialogs (lettura standard già usata da upstream) e processLoadedDialogs con loadType
     * UNKNOWN, che abbassa dialogsLoadedTillDate alla data della chat così supera il filtro di sortDialogs.
     * Offline: fallisce in silenzio, riprova al prossimo toggle / resume. Thread UI.
     */
    public static void ensureLoaded(int account) {
        final State s = state(account);
        if (s == null || s.order.length == 0 || !UserConfig.getInstance(account).isClientActivated()) {
            return;
        }
        final MessagesController mc = MessagesController.getInstance(account);
        for (long id : s.order) {
            if (DialogObject.isEncryptedDialog(id) || DialogObject.isFolderDialogId(id) || mc.dialogs_dict.get(id) != null) {
                continue;
            }
            final HashSet<Long> pending = pending(account);
            if (pending.contains(id)) {
                continue;
            }
            pending.add(id);
            loadUnknown(account, id);
        }
    }

    private static HashSet<Long> pending(int account) {
        HashSet<Long> set = pendingLoads[account];
        if (set == null) {
            pendingLoads[account] = set = new HashSet<>();
        }
        return set;
    }

    private static void loadUnknown(int account, long dialogId) {
        final MessagesController mc = MessagesController.getInstance(account);
        Utilities.globalQueue.postRunnable(() -> {
            TLRPC.User user = null;
            TLRPC.Chat chat = null;
            boolean fromDb = false;
            try {
                if (dialogId > 0) {
                    user = mc.getUser(dialogId);
                    if (user == null) {
                        user = MessagesStorage.getInstance(account).getUserSync(dialogId);
                        fromDb = user != null;
                    }
                } else {
                    chat = mc.getChat(-dialogId);
                    if (chat == null) {
                        chat = MessagesStorage.getInstance(account).getChatSync(-dialogId);
                        fromDb = chat != null;
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            final TLRPC.User u = user;
            final TLRPC.Chat c = chat;
            final boolean db = fromDb;
            AndroidUtilities.runOnUIThread(() -> {
                if (u == null && c == null || c != null && ChatObject.isCommunity(c)) {
                    // peer sconosciuto (niente access_hash) o community (riga costruita da sortDialogs, non da getPeerDialogs)
                    pending(account).remove(dialogId);
                    return;
                }
                if (db) {
                    if (u != null) {
                        final ArrayList<TLRPC.User> users = new ArrayList<>(1);
                        users.add(u);
                        mc.putUsers(users, true);
                    } else {
                        final ArrayList<TLRPC.Chat> chats = new ArrayList<>(1);
                        chats.add(c);
                        mc.putChats(chats, true);
                    }
                }
                final TLRPC.InputPeer peer = mc.getInputPeer(dialogId);
                if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
                    pending(account).remove(dialogId);
                    return;
                }
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("morsegram local pin: load unknown dialog " + dialogId);
                }
                final TLRPC.TL_messages_getPeerDialogs req = new TLRPC.TL_messages_getPeerDialogs();
                final TLRPC.TL_inputDialogPeer inputDialogPeer = new TLRPC.TL_inputDialogPeer();
                inputDialogPeer.peer = peer;
                req.peers.add(inputDialogPeer);
                ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                    try {
                        if (response instanceof TLRPC.TL_messages_peerDialogs) {
                            final TLRPC.TL_messages_peerDialogs res = (TLRPC.TL_messages_peerDialogs) response;
                            if (!res.dialogs.isEmpty()) {
                                final TLRPC.TL_messages_dialogs dialogs = new TLRPC.TL_messages_dialogs();
                                dialogs.dialogs.addAll(res.dialogs);
                                dialogs.messages.addAll(res.messages);
                                dialogs.users.addAll(res.users);
                                dialogs.chats.addAll(res.chats);
                                mc.processLoadedDialogs(dialogs, null, null, res.dialogs.get(0).folder_id, 0, 1, DIALOGS_LOAD_TYPE_UNKNOWN, false, false, false);
                            }
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                    AndroidUtilities.runOnUIThread(() -> pending(account).remove(dialogId));
                });
            });
        });
    }
}

/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.hidden;

import androidx.core.content.pm.ShortcutManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

import it.iotatec.morsegram.MorsegramPrefs;

/**
 * Chat nascoste (MORSEGRAM.md mission S6): insieme per account di dialog id che NON compaiono in nessuna lista
 * dell'app (lista chat, archivio, cartelle, community, foglio di inoltro, picker, ricerca, contatori, notifiche in
 * chiaro) finché la sezione protetta da {@link HiddenLock} non viene sbloccata.
 * <p>
 * Puro filtro UI locale (ToS 1.4): le chat restano in {@code allDialogs}/{@code dialogs_dict} e ricevono update,
 * letture, bozze, typing e online esattamente come prima; nessuna request evitata o alterata, nessun filtro di update.
 * Il filtro è a monte di tutte le liste derivate: 1 riga in {@code MessagesController.sortDialogs} (via
 * {@link it.iotatec.morsegram.MorsegramDialogs#hide}) toglie il dialogo da dialogsByFolder/community/forward/
 * serverOnly/users/groups/channels e da unreadUnmutedDialogs, accumulandolo nella lista {@link #hiddenDialogs} di
 * questa classe (MAI in dialogsByFolder: ChatPullingDownDrawable itera le sue chiavi, LEAK L2).
 * <p>
 * Persistenza: {@link MorsegramPrefs#account(int)} chiave {@link MorsegramPrefs#KEY_HIDDEN_IDS} (CSV, chiave per user
 * id). Stato in memoria per slot: array ordinato immutabile, copy-on-write a ogni modifica → {@link #isHidden} è
 * lock-free e O(log n) senza allocazioni da qualunque thread (sortDialogs sull'UI, calcUnreadCounters sulla
 * storageQueue, le notifiche sulla notificationsQueue).
 */
public final class HiddenChats {

    /** folderId fittizio della sezione protetta (HiddenChatsActivity): mai inserito in dialogsByFolder. */
    public static final int FOLDER_HIDDEN = 7777;

    private static final class State {
        final long userId;
        /** ordinato crescente, per Arrays.binarySearch. */
        final long[] ids;

        State(long userId, long[] ids) {
            this.userId = userId;
            this.ids = ids;
        }

        boolean contains(long dialogId) {
            return ids.length > 0 && Arrays.binarySearch(ids, dialogId) >= 0;
        }
    }

    /**
     * Copia filtrata di allDialogs per {@code getAllDialogs()}: {@code add()} viene inoltrato alla lista viva
     * (DialogsSearchAdapter aggiunge un dialogo segnaposto e poi chiama sortDialogs(null)).
     */
    static final class VisibleList extends ArrayList<TLRPC.Dialog> {
        final ArrayList<TLRPC.Dialog> live;
        final int generation;
        int liveSize;

        VisibleList(ArrayList<TLRPC.Dialog> live, int generation) {
            super(live.size());
            this.live = live;
            this.generation = generation;
        }

        @Override
        public boolean add(TLRPC.Dialog dialog) {
            live.add(dialog);
            liveSize = live.size();
            return super.add(dialog);
        }

        /** inserimento durante la costruzione: solo nella copia. */
        void plainAdd(TLRPC.Dialog dialog) {
            super.add(dialog);
        }

        boolean valid(ArrayList<TLRPC.Dialog> all, int gen) {
            return live == all && generation == gen && liveSize == all.size();
        }
    }

    private static final AtomicReferenceArray<State> states = new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);
    private static final AtomicReferenceArray<VisibleList> visibleCache = new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);
    /** incrementata a ogni pass di sortDialogs (indice 0) e a ogni modifica: invalida la copia filtrata. */
    private static final AtomicIntegerArray generation = new AtomicIntegerArray(UserConfig.MAX_ACCOUNT_COUNT);
    /** accumulatore per slot, ricostruito da ogni pass di sortDialogs (solo thread UI). */
    @SuppressWarnings("unchecked")
    private static final ArrayList<TLRPC.Dialog>[] hidden = new ArrayList[UserConfig.MAX_ACCOUNT_COUNT];
    private static final NotificationCenter.NotificationCenterDelegate[] hintObservers = new NotificationCenter.NotificationCenterDelegate[UserConfig.MAX_ACCOUNT_COUNT];

    private HiddenChats() {
    }

    /* ======================================================================================= stato */

    /**
     * Stato dello slot, ricaricato se l'utente loggato è cambiato (come LocalPins: legge il campo pubblico
     * {@code UserConfig.clientUserId}, non il getter synchronized). userId 0 → null, niente cache.
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
            final boolean switched = s != null; // altro utente nello stesso slot: l'accumulatore del vecchio utente non deve sopravvivere
            s = new State(userId, parse(MorsegramPrefs.account(account).getString(MorsegramPrefs.KEY_HIDDEN_IDS, "")));
            states.set(account, s);
            if (switched || s.ids.length > 0) {
                final State fresh = s;
                AndroidUtilities.runOnUIThread(() -> {
                    if (switched) {
                        final ArrayList<TLRPC.Dialog> stale = hidden[account];
                        if (stale != null) {
                            stale.clear();
                        }
                        generation.incrementAndGet(account);
                    }
                    if (fresh.ids.length > 0) {
                        ensureHintObserver(account);
                    }
                });
            }
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
                if (id != 0) {
                    tmp[n++] = id;
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return normalize(tmp, n);
    }

    /** ordina e deduplica i primi n elementi. */
    private static long[] normalize(long[] tmp, int n) {
        final long[] sorted = Arrays.copyOf(tmp, n);
        Arrays.sort(sorted);
        int m = 0;
        for (int i = 0; i < sorted.length; i++) {
            if (i == 0 || sorted[i] != sorted[i - 1]) {
                sorted[m++] = sorted[i];
            }
        }
        return m == sorted.length ? sorted : Arrays.copyOf(sorted, m);
    }

    private static String csv(long[] ids) {
        final StringBuilder sb = new StringBuilder(ids.length * 12);
        for (long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /* ==================================================================================== letture */

    /** Lock-free, da qualunque thread. */
    public static boolean isHidden(int account, long dialogId) {
        final State s = state(account);
        return s != null && s.contains(dialogId);
    }

    public static boolean hasHidden(int account) {
        final State s = state(account);
        return s != null && s.ids.length > 0;
    }

    /** Numero di chat nascoste dell'utente dello slot (anche non caricate). */
    public static int count(int account) {
        final State s = state(account);
        return s == null ? 0 : s.ids.length;
    }

    /** Copia degli id nascosti (per la pagina impostazioni / HiddenChatsActivity). */
    public static long[] ids(int account) {
        final State s = state(account);
        return s == null ? new long[0] : s.ids.clone();
    }

    /**
     * Lista viva delle chat nascoste caricate, nell'ordine del dialogComparator (pin locali inclusi), ricostruita a
     * ogni pass di sortDialogs. Solo thread UI; HiddenChatsActivity la restituisce da getDialogsArray.
     */
    public static ArrayList<TLRPC.Dialog> hiddenDialogs(int account) {
        ArrayList<TLRPC.Dialog> list = hidden[account];
        if (list == null) {
            hidden[account] = list = new ArrayList<>();
        }
        return list;
    }

    /**
     * Hook MessagesController.sortDialogs (loop principale, via MorsegramDialogs.hide): con {@code index == 0}
     * azzera l'accumulatore (unico 'inizio pass' disponibile senza righe aggiuntive: il loop parte sempre da 0 e le
     * rimozioni {@code remove(a); a--} avvengono dopo la nostra riga). Riga Archivio e community non sono mai
     * nascoste. true → upstream fa {@code continue}: il dialogo resta in allDialogs ma fuori da tutte le liste.
     */
    public static boolean collect(int account, TLRPC.Dialog d, int index) {
        if (index == 0) {
            hiddenDialogs(account).clear();
            generation.incrementAndGet(account);
        }
        if (d == null || d instanceof TLRPC.TL_dialogFolder || d instanceof TLRPC.TL_dialogCommunity) {
            return false;
        }
        final State s = state(account);
        if (s == null || !s.contains(d.id)) {
            return false;
        }
        hiddenDialogs(account).add(d);
        return true;
    }

    /**
     * Hook MessagesController.getAllDialogs (via MorsegramDialogs.visible): copia filtrata con cache per pass
     * (invalidata da sortDialogs e dai toggle), {@code add()} inoltrato alla lista viva. Chiamata solo se hasHidden.
     */
    public static ArrayList<TLRPC.Dialog> visible(int account, ArrayList<TLRPC.Dialog> all) {
        final State s = state(account);
        if (s == null || s.ids.length == 0 || all == null) {
            return all;
        }
        final int gen = generation.get(account);
        VisibleList cached = visibleCache.get(account);
        if (cached != null && cached.valid(all, gen)) {
            return cached;
        }
        final VisibleList copy = new VisibleList(all, gen);
        for (int i = 0, n = all.size(); i < n; i++) {
            final TLRPC.Dialog d = all.get(i);
            if (d == null || d instanceof TLRPC.TL_dialogFolder || d instanceof TLRPC.TL_dialogCommunity || !s.contains(d.id)) {
                copy.plainAdd(d);
            }
        }
        copy.liveSize = all.size();
        visibleCache.set(account, copy);
        return copy;
    }

    /**
     * Hook MessagesController.reorderPinnedDialogs (ramo {@code order == null}) e loadPinnedDialogs (loop di
     * riallineamento), via MorsegramDialogs.pinnedSource: la lista da cui upstream legge il prefisso dei pin server.
     * {@code getDialogs(folderId)} non contiene le chat nascoste, e {@code messages.reorderPinnedDialogs} parte con
     * {@code force=true}: senza questo hook il server toglierebbe il pin a una chat nascosta pinnata ogni volta che
     * l'utente pinna/spinna/riordina un'altra chat (effetto sul wire, ToS 1.4), e loadPinnedDialogs non azzererebbe
     * mai un pin nascosto tolto da un'altra sessione. Senza chat nascoste pinnate in quel folder restituisce la lista
     * viva upstream (costo zero); altrimenti una copia ordinata dal comparatore upstream ({@code sortDialogsList}) con
     * le nascoste pinnate al loro posto (pinnedNum). Solo thread UI (entrambi i chiamanti lo sono).
     */
    public static ArrayList<TLRPC.Dialog> withHiddenPinned(int account, MessagesController mc, int folderId) {
        final ArrayList<TLRPC.Dialog> live = mc.getDialogs(folderId);
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return live;
        }
        final ArrayList<TLRPC.Dialog> list = hidden[account];
        if (list == null || list.isEmpty()) {
            return live;
        }
        ArrayList<TLRPC.Dialog> copy = null;
        for (int i = 0, n = list.size(); i < n; i++) {
            final TLRPC.Dialog d = list.get(i);
            if (d != null && d.pinned && d.folder_id == folderId) {
                if (copy == null) {
                    copy = new ArrayList<>(live.size() + n);
                    copy.addAll(live);
                }
                copy.add(d);
            }
        }
        if (copy == null) {
            return live;
        }
        mc.sortDialogsList(copy);
        return copy;
    }

    /** Peer di una riga di ricerca locale (MessagesStorage.localSearch): User/Chat/EncryptedChat/Dialog. */
    public static boolean isHiddenPeer(int account, Object object) {
        if (object == null || !hasHidden(account)) {
            return false;
        }
        if (object instanceof TLRPC.User) {
            return isHidden(account, ((TLRPC.User) object).id);
        } else if (object instanceof TLRPC.Chat) {
            return isHidden(account, -((TLRPC.Chat) object).id);
        } else if (object instanceof TLRPC.EncryptedChat) {
            return isHidden(account, DialogObject.makeEncryptedDialogId(((TLRPC.EncryptedChat) object).id));
        } else if (object instanceof TLRPC.Dialog) {
            return isHidden(account, ((TLRPC.Dialog) object).id);
        }
        return false;
    }

    /**
     * Idonea al nascondimento: esiste in dialogs_dict, non è la riga Archivio né una community (righe sintetiche di
     * sortDialogs), non è la self-chat (sortDialogs la reinserirebbe in dialogsForward come dialogo sintetico).
     */
    public static boolean canHide(int account, long dialogId) {
        if (dialogId == 0 || DialogObject.isFolderDialogId(dialogId) || account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return false;
        }
        if (dialogId == UserConfig.getInstance(account).clientUserId) {
            return false;
        }
        final TLRPC.Dialog d = MessagesController.getInstance(account).dialogs_dict.get(dialogId);
        return d != null && !(d instanceof TLRPC.TL_dialogFolder) && !(d instanceof TLRPC.TL_dialogCommunity);
    }

    /* =================================================================================== modifiche */

    /** Thread UI. @return false se già nascosta, non idonea o slot senza utente. */
    public static boolean hide(int account, long dialogId) {
        final ArrayList<Long> ids = new ArrayList<>(1);
        ids.add(dialogId);
        return hideAll(account, ids) > 0;
    }

    /** Thread UI. @return false se non era nascosta. */
    public static boolean unhide(int account, long dialogId) {
        final ArrayList<Long> ids = new ArrayList<>(1);
        ids.add(dialogId);
        return unhideAll(account, ids) > 0;
    }

    /** @return il nuovo stato (true = ora nascosta). */
    public static boolean toggle(int account, long dialogId) {
        if (isHidden(account, dialogId)) {
            unhide(account, dialogId);
            return false;
        }
        hide(account, dialogId);
        return true;
    }

    /** Un solo commit (sortDialogs + dialogsNeedReload + contatori + notifiche) per tutto il batch. @return nascoste ora. */
    public static int hideAll(int account, Collection<Long> dialogIds) {
        final State s = state(account);
        if (s == null || dialogIds == null || dialogIds.isEmpty()) {
            return 0;
        }
        final long[] tmp = new long[s.ids.length + dialogIds.size()];
        System.arraycopy(s.ids, 0, tmp, 0, s.ids.length);
        int n = s.ids.length;
        final ArrayList<Long> fresh = new ArrayList<>(dialogIds.size());
        for (Long id : dialogIds) {
            if (id == null || s.contains(id) || fresh.contains(id) || !canHide(account, id)) {
                continue;
            }
            fresh.add(id);
            tmp[n++] = id;
        }
        if (fresh.isEmpty()) {
            return 0;
        }
        commit(account, new State(s.userId, normalize(tmp, n)), fresh);
        return fresh.size();
    }

    /** @return mostrate ora. */
    public static int unhideAll(int account, Collection<Long> dialogIds) {
        final State s = state(account);
        if (s == null || dialogIds == null || dialogIds.isEmpty()) {
            return 0;
        }
        final long[] tmp = new long[s.ids.length];
        int n = 0;
        int removed = 0;
        for (long id : s.ids) {
            if (dialogIds.contains(id)) {
                removed++;
            } else {
                tmp[n++] = id;
            }
        }
        if (removed == 0) {
            return 0;
        }
        commit(account, new State(s.userId, Arrays.copyOf(tmp, n)), null);
        return removed;
    }

    /**
     * Sostituisce lo stato (copy-on-write), persiste e riallinea tutto ciò che deriva dalle liste: ordinamento
     * (sortDialogs + dialogsNeedReload), contatori locali (resetAllUnreadCounters sulla storageQueue), notifiche
     * (ri-emesse mascherate/in chiaro + badge), hint/direct share, shortcut launcher, ricerche recenti, canale
     * notifiche per-chat. Tutto locale: nessuna request. Thread UI.
     */
    private static void commit(int account, State next, ArrayList<Long> newlyHidden) {
        states.set(account, next);
        generation.incrementAndGet(account);
        MorsegramPrefs.account(account).edit().putString(MorsegramPrefs.KEY_HIDDEN_IDS, csv(next.ids)).apply();
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("morsegram hidden chats account " + account + " -> " + next.ids.length);
        }
        final MessagesController mc = MessagesController.getInstance(account);
        mc.sortDialogs(null);
        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.dialogsNeedReload);
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        storage.getStorageQueue().postRunnable(() -> {
            try {
                storage.resetAllUnreadCounters(false);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
        final NotificationsController nc = NotificationsController.getInstance(account);
        if (newlyHidden != null) {
            final ArrayList<String> shortcutIds = new ArrayList<>(newlyHidden.size() * 2);
            for (Long id : newlyHidden) {
                shortcutIds.add("sdid_" + id);
                shortcutIds.add("ndid_" + id);
                try {
                    nc.deleteNotificationChannel(id, 0);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                storage.getStorageQueue().postRunnable(() -> {
                    try {
                        storage.getDatabase().executeFast("DELETE FROM search_recent WHERE did = " + id).stepThis().dispose();
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                });
            }
            try {
                ShortcutManagerCompat.removeDynamicShortcuts(ApplicationLoader.applicationContext, shortcutIds);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            ensureHintObserver(account);
            pruneHints(account);
        }
        nc.showNotifications();
        nc.updateBadge();
    }

    /* ======================================================================================= hints */

    /**
     * Observer reloadHints per account: i suggerimenti (hints/direct share) vengono ricaricati da cache/rete e
     * rifatti da increasePeerRaiting, quindi vanno potati a ogni reload. Solo in memoria: MAI removePeer /
     * contacts.resetTopPeerRating (ToS). Thread UI.
     */
    private static void ensureHintObserver(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || hintObservers[account] != null) {
            return;
        }
        final NotificationCenter.NotificationCenterDelegate delegate = (id, acc, args) -> {
            if (id == NotificationCenter.reloadHints) {
                pruneHints(account);
            }
        };
        hintObservers[account] = delegate;
        NotificationCenter.getInstance(account).addObserver(delegate, NotificationCenter.reloadHints);
        pruneHints(account);
    }

    private static void pruneHints(int account) {
        final State s = state(account);
        if (s == null || s.ids.length == 0) {
            return;
        }
        try {
            final MediaDataController mdc = MediaDataController.getInstance(account);
            final ArrayList<TLRPC.TL_topPeer> hints = mdc.hints;
            boolean changed = false;
            for (int i = hints.size() - 1; i >= 0; i--) {
                final TLRPC.TL_topPeer peer = hints.get(i);
                if (peer != null && peer.peer != null && s.contains(DialogObject.getPeerDialogId(peer.peer))) {
                    hints.remove(i);
                    changed = true;
                }
            }
            if (changed) {
                mdc.buildShortcuts();
                // ri-notifica DOPO la potatura (gli observer upstream possono aver già disegnato la lista piena)
                AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.reloadHints));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}

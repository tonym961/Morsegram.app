/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

import it.iotatec.morsegram.hidden.HiddenChats;

/**
 * Superficie unica per ordinamento/filtro della lista chat, così i file upstream (MessagesController, DialogCell,
 * DialogsAdapter, MessagesStorage, DialogsSearchAdapter, FilteredSearchView) hanno un solo punto di contatto.
 * Delega a {@link LocalPins} (ordine) e {@link HiddenChats} (filtro). Letture lock-free O(1)/O(log n) senza
 * allocazioni: il comparatore gira n log n a ogni messaggio, sortDialogs a ogni update, calcUnreadCounters sulla
 * storageQueue, le notifiche sulla notificationsQueue.
 */
public final class MorsegramDialogs {

    /** folderId fittizio della sezione 'Chat nascoste' (HiddenChatsActivity): mai in dialogsByFolder. */
    public static final int FOLDER_HIDDEN = HiddenChats.FOLDER_HIDDEN;

    private MorsegramDialogs() {
    }

    /* ============================================================================ pin locali (S1) */

    /**
     * Hook MessagesController.dialogComparator, tra il blocco dei pin server e il confronto per data: a quel punto
     * entrambi i dialoghi sono non-folder e non pin server. != 0 → ordine deciso qui; 0 → upstream prosegue.
     */
    public static int compare(int account, TLRPC.Dialog a, TLRPC.Dialog b) {
        return LocalPins.compare(account, a, b);
    }

    /** Hook DialogsAdapter.ItemInternal (ribind DiffUtil) e DialogCell.setDialog (drawPin). */
    public static boolean isLocalPinGlyph(int account, TLRPC.Dialog dialog) {
        return LocalPins.isLocalGlyph(account, dialog);
    }

    /** Hook DialogCell.onDraw: scelta del drawable del pin (glifo locale ambra vs pin server). */
    public static boolean isLocalPinGlyph(int account, long dialogId, int dialogsType) {
        return LocalPins.isLocalGlyph(account, dialogId, dialogsType);
    }

    /* ========================================================================= chat nascoste (S6) */

    /**
     * Hook MessagesController.sortDialogs, loop principale subito dopo {@code TLRPC.Dialog d = allDialogs.get(a);}:
     * true → {@code continue} (fuori da dialogsByFolder/community/forward/serverOnly/users/groups/channels e da
     * unreadUnmutedDialogs); il dialogo viene accumulato nella lista nostra ({@link #hiddenDialogs}), MAI in
     * dialogsByFolder. {@code index == 0} = inizio pass (azzera l'accumulatore).
     */
    public static boolean hide(int account, MessagesController controller, TLRPC.Dialog d, int index) {
        return HiddenChats.collect(account, d, index);
    }

    /** Hook DialogFilter.includesDialog (cartelle), MessagesStorage.calcUnreadCounters, DialogsSearchAdapter, FilteredSearchView, NotificationsController. Lock-free. */
    public static boolean isHidden(int account, long dialogId) {
        return HiddenChats.isHidden(account, dialogId);
    }

    /** Hook MessagesController.getAllDialogs: true → restituire {@link #visible} invece della lista viva. */
    public static boolean hasHidden(int account) {
        return HiddenChats.hasHidden(account);
    }

    /** Copia filtrata di allDialogs (cache per pass di sortDialogs; add() inoltrato alla lista viva). */
    public static ArrayList<TLRPC.Dialog> visible(int account, ArrayList<TLRPC.Dialog> allDialogs) {
        return HiddenChats.visible(account, allDialogs);
    }

    /** Hook MessagesStorage.localSearch: peer (User/Chat/EncryptedChat/Dialog) di una chat nascosta. */
    public static boolean isHiddenPeer(int account, Object object) {
        return HiddenChats.isHiddenPeer(account, object);
    }

    /**
     * Hook MessagesController.reorderPinnedDialogs (ramo {@code order == null}) e loadPinnedDialogs (loop di
     * riallineamento) al posto di {@code getDialogs(folderId)}: stessa lista viva se nessuna chat nascosta è pinnata
     * in quel folder, altrimenti una copia ordinata con le nascoste pinnate al loro posto, così
     * {@code messages.reorderPinnedDialogs} ({@code force=true}) non spinna sul server le chat nascoste. Thread UI.
     */
    public static ArrayList<TLRPC.Dialog> pinnedSource(int account, MessagesController controller, int folderId) {
        return HiddenChats.withHiddenPinned(account, controller, folderId);
    }

    /** Lista viva delle chat nascoste caricate, ordinata come allDialogs; per HiddenChatsActivity.getDialogsArray. */
    public static ArrayList<TLRPC.Dialog> hiddenDialogs(int account) {
        return HiddenChats.hiddenDialogs(account);
    }
}

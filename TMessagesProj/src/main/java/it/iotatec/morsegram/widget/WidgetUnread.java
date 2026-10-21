/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

import it.iotatec.morsegram.MorsegramDialogs;

/**
 * Conteggio dei messaggi non letti del widget NON LETTI, con cache in {@link WidgetConfig}.
 * <p>
 * Fonte: {@code MessagesController.getDialogs(0)} (lista chat) e {@code getDialogs(1)} (archivio), cioè
 * {@code dialogsByFolder}, da cui l'hook di 1 riga in {@code MessagesController.sortDialogs} ha già tolto le
 * chat nascoste. Nessuna query SQL, nessun thread: solo strutture in memoria, dal thread UI.
 * <p>
 * Il valore viene scritto in cache SOLO quando {@code dialogsLoaded} è vero: a processo freddo le liste sono
 * vuote e azzerare il contatore mostrerebbe "0 non letti" a chi ne ha cento.
 */
final class WidgetUnread {

    private WidgetUnread() {
    }

    static int cachedTotal(int account) {
        return WidgetConfig.unreadTotal(account);
    }

    static int cachedChats(int account) {
        return WidgetConfig.unreadChats(account);
    }

    /** Ricalcola e memorizza, se possibile. Thread UI. */
    static void refreshCache(int account) {
        if (!MorsegramWidgets.canReadDialogs(account)) {
            return;
        }
        try {
            final MessagesController controller = MessagesController.getInstance(account);
            if (!controller.dialogsLoaded) {
                return;
            }
            int total = 0;
            int chats = 0;
            for (int folderId = 0; folderId <= 1; folderId++) {
                final ArrayList<TLRPC.Dialog> dialogs = controller.getDialogs(folderId);
                if (dialogs == null) {
                    continue;
                }
                for (int i = 0, n = dialogs.size(); i < n; i++) {
                    final TLRPC.Dialog dialog = dialogs.get(i);
                    if (dialog == null || DialogObject.isFolderDialogId(dialog.id)) {
                        continue;
                    }
                    if (MorsegramDialogs.isHidden(account, dialog.id)) {
                        continue;
                    }
                    final int count = controller.getDialogUnreadCount(dialog);
                    if (count > 0) {
                        total += count;
                        chats++;
                    }
                }
            }
            if (total != WidgetConfig.unreadTotal(account) || chats != WidgetConfig.unreadChats(account)) {
                WidgetConfig.putUnread(account, total, chats);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}

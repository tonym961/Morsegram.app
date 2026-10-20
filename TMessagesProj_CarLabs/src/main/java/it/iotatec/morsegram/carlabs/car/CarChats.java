/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.carlabs.car;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

import it.iotatec.morsegram.auto.MorsegramAutoCommon;

/**
 * Lista chat per l'head unit: la lista reale del telefono, filtrata e ridotta a quel poco che serve a una riga.
 *
 * <p>Sorgente: {@code MessagesController.getDialogs(0)} (cartella principale, quindi mai archiviate). L'ordine è
 * quello del telefono — pin del server, pin locali Morsegram, poi data decrescente — perché è l'unico che il
 * guidatore riconosce "a memoria"; i messaggi appena arrivati finiscono comunque in testa.</p>
 *
 * <p>Esclusi: la pseudo-chat della cartella Archivio, le chat segrete, "Risposte", gli account cancellati e le
 * <b>chat nascoste</b> ({@link MorsegramAutoCommon#allowed}). Le nascoste in realtà sono già fuori da
 * {@code dialogsByFolder} (hook in {@code sortDialogs}): il controllo qui è una seconda rete, perché una chat
 * nascosta sull'head unit sarebbe una fuga di dati verso il passeggero.</p>
 *
 * <p>Tutti i metodi vanno chiamati dal thread UI: leggono lo stato vivo di MessagesController senza I/O.</p>
 */
final class CarChats {

    /** Dati di una riga: titolo, metadati (mai testo del messaggio), badge, avatar. */
    static final class Entry {
        final long dialogId;
        final String title;
        /** seconda riga: tipo dell'ultimo messaggio + data. MAI il testo (regola Android Auto: legge l'host). */
        final String meta;
        final int unread;
        final boolean group;
        @Nullable
        final TLRPC.User user;
        @Nullable
        final TLRPC.Chat chat;

        Entry(long dialogId, String title, String meta, int unread, boolean group,
              @Nullable TLRPC.User user, @Nullable TLRPC.Chat chat) {
            this.dialogId = dialogId;
            this.title = title;
            this.meta = meta;
            this.unread = unread;
            this.group = group;
            this.user = user;
            this.chat = chat;
        }
    }

    private CarChats() {
    }

    /** true quando la lista è utilizzabile: dialoghi caricati oppure almeno una chat che ha notificato. */
    static boolean isReady(int account) {
        MessagesController mc = MessagesController.getInstance(account);
        return mc.dialogsLoaded || !mc.getDialogs(0).isEmpty();
    }

    /**
     * Avvia il caricamento dalla cache locale se il processo è freddo (l'host può lanciarci senza che l'app sia
     * mai stata aperta). Idempotente: {@code loadDialogs} esce subito se è già in corso.
     */
    static void kickLoad(int account) {
        MessagesController mc = MessagesController.getInstance(account);
        if (!mc.dialogsLoaded) {
            mc.loadDialogs(0, 0, 100, true);
        }
    }

    /** Le prime {@code max} chat visibili, nell'ordine del telefono. */
    @NonNull
    static ArrayList<Entry> list(int account, int max) {
        return collect(account, null, max);
    }

    /** Ricerca locale per titolo (nessuna rete): il guidatore dice/scrive un nome e tocca una riga. */
    @NonNull
    static ArrayList<Entry> search(int account, @Nullable String query, int max) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.getDefault());
        return collect(account, q.isEmpty() ? null : q, max);
    }

    @NonNull
    private static ArrayList<Entry> collect(int account, @Nullable String lowerQuery, int max) {
        MessagesController mc = MessagesController.getInstance(account);
        ArrayList<Entry> out = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();

        ArrayList<TLRPC.Dialog> dialogs = mc.getDialogs(0);
        for (int i = 0; i < dialogs.size() && out.size() < max; i++) {
            TLRPC.Dialog d = dialogs.get(i);
            if (d == null || !seen.add(d.id)) {
                continue;
            }
            Entry e = entry(account, mc, d.id, mc.getDialogUnreadCount(d));
            if (e != null && matches(e, lowerQuery)) {
                out.add(e);
            }
        }
        if (out.size() < max) {
            // processo freddo: i dialoghi non ci sono ancora ma le notifiche di questo viaggio sì, e quelle chat
            // sono proprio quelle che il guidatore cerca. Coda, non testa: l'ordine del telefono resta primo.
            for (MessageObject mo : NotificationsController.getInstance(account).getPushMessagesSnapshot()) {
                if (out.size() >= max) {
                    break;
                }
                if (mo == null || mo.messageOwner == null) {
                    continue;
                }
                long dialogId = mo.getDialogId();
                if (!seen.add(dialogId)) {
                    continue;
                }
                Entry e = entry(account, mc, dialogId, 0);
                if (e != null && matches(e, lowerQuery)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    private static boolean matches(Entry e, @Nullable String lowerQuery) {
        return lowerQuery == null || e.title.toLowerCase(Locale.getDefault()).contains(lowerQuery);
    }

    @Nullable
    private static Entry entry(int account, MessagesController mc, long dialogId, int unread) {
        if (DialogObject.isFolderDialogId(dialogId)) {
            return null;                                   // pseudo-chat della cartella Archivio
        }
        if (UserObject.isReplyUser(dialogId)) {
            return null;                                   // "Risposte": non è una chat a cui rispondere
        }
        if (!MorsegramAutoCommon.allowed(account, dialogId)) {
            return null;                                   // id vuoti, chat segrete, chat nascoste: filtro unico
        }

        TLRPC.User user = null;
        TLRPC.Chat chat = null;
        String title;
        boolean group = false;
        if (DialogObject.isUserDialog(dialogId)) {
            user = mc.getUser(dialogId);
            if (user == null || UserObject.isDeleted(user)) {
                return null;
            }
            title = UserObject.isUserSelf(user)
                    ? LocaleController.getString(R.string.SavedMessages)
                    : UserObject.getUserName(user);
        } else {
            chat = mc.getChat(-dialogId);
            if (chat == null) {
                return null;
            }
            title = chat.title;
            group = !ChatObject.isChannel(chat) || ChatObject.isMegagroup(chat);
        }
        if (TextUtils.isEmpty(title)) {
            return null;
        }
        return new Entry(dialogId, title, meta(account, mc, dialogId), Math.max(0, unread), group, user, chat);
    }

    /**
     * Seconda riga della riga chat: <i>tipo</i> dell'ultimo messaggio + data breve, nel formato della lista chat
     * del telefono. Il testo del messaggio non compare mai: su Android Auto lo legge l'host (ConversationItem).
     */
    private static String meta(int account, MessagesController mc, long dialogId) {
        ArrayList<MessageObject> top = mc.dialogMessage.get(dialogId);
        MessageObject mo = top != null && !top.isEmpty() ? top.get(0) : null;
        if (mo == null || mo.messageOwner == null) {
            return "";
        }
        String kind = kind(mo);
        String when = LocaleController.stringForMessageListDate(mo.messageOwner.date);
        if (TextUtils.isEmpty(kind)) {
            return when;
        }
        if (mo.isOut() && mo.getDialogId() != UserConfig.getInstance(account).getClientUserId()) {
            kind = LocaleController.getString(R.string.FromYou) + ": " + kind;
        }
        return kind + " · " + when;
    }

    private static String kind(MessageObject mo) {
        if (mo.isVoice()) {
            return LocaleController.getString(R.string.AttachAudio);
        }
        if (mo.isRoundVideo()) {
            return LocaleController.getString(R.string.AttachRound);
        }
        if (mo.isMusic()) {
            return LocaleController.getString(R.string.AttachMusic);
        }
        if (mo.isPhoto()) {
            return LocaleController.getString(R.string.AttachPhoto);
        }
        if (mo.isVideo()) {
            return LocaleController.getString(R.string.AttachVideo);
        }
        if (mo.isGif()) {
            return LocaleController.getString(R.string.AttachGif);
        }
        if (mo.isSticker() || mo.isAnimatedSticker()) {
            return LocaleController.getString(R.string.AttachSticker);
        }
        if (mo.isDocument()) {
            return LocaleController.getString(R.string.AttachDocument);
        }
        if (mo.messageOwner.media instanceof TLRPC.TL_messageMediaGeo
                || mo.messageOwner.media instanceof TLRPC.TL_messageMediaGeoLive) {
            return LocaleController.getString(R.string.AttachLocation);
        }
        if (mo.messageOwner.media instanceof TLRPC.TL_messageMediaContact) {
            return LocaleController.getString(R.string.AttachContact);
        }
        return LocaleController.getString(R.string.Message);
    }
}

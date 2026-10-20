/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.carlabs.car;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.messaging.model.CarMessage;
import androidx.car.app.messaging.model.ConversationCallback;
import androidx.car.app.messaging.model.ConversationItem;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.CarText;
import androidx.core.app.Person;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Da {@link MessageObject} a {@link ConversationItem}/{@link CarMessage}: il corpo del messaggio viene consegnato
 * all'host, che lo <b>legge ad alta voce</b>. Nessuna schermata nostra stampa il testo di un messaggio: è la
 * regola Android Auto per le app di messaggistica, ed è anche il motivo per cui esiste ConversationItem.
 *
 * <p>Il testo passa da {@code NotificationsController.getShortStringForMessage}, lo stesso della notifica sul
 * telefono: così l'anteprima rispetta passcode e impostazioni per-chat esattamente come il telefono (con il
 * passcode attivo l'host legge "messaggio nascosto", non il contenuto).</p>
 *
 * <p><b>Vincolo 1.7.0</b>: ogni {@link Person} dato all'host deve avere nome e {@code key} non nulli, altrimenti
 * la validazione di ConversationItem/CarMessage lancia. Chiavi usate qui: {@code "d"+dialogId} per la chat,
 * {@code "u"+userId} per un utente, {@code "c"+chatId} per un gruppo/canale, {@code "self"+clientUserId} per noi.</p>
 */
final class CarConversations {

    /** ListTemplate ne tiene al massimo 10 per conversazione; 5 è già più di quanto un guidatore ascolti. */
    static final int MAX_MESSAGES = 5;

    /**
     * Punto di innesto per il percorso vocale (altra missione): chiamato su ogni {@link CarMessage.Builder}
     * prima di {@code build()}, può aggiungere {@code setMultimediaMimeType}/{@code setMultimediaUri} per far
     * riprodurre un vocale dall'host. Deve essere veloce (watchdog) e non lanciare.
     */
    interface MessageDecorator {
        void decorate(@NonNull CarMessage.Builder builder, int account, @NonNull MessageObject message);
    }

    /**
     * Punto di innesto per un'azione sulla conversazione (es. "Riproduci vocale"), per chi costruisce un
     * ConversationItem senza passare dalle schermate di questo package.
     * <b>Una sola azione</b>: ACTIONS_CONSTRAINTS_CONVERSATION_ITEM ha maxActions = 1, la seconda fa lanciare
     * la validazione, quindi di tutte le azioni offerte dal provider viene presa solo la prima. Lo slot è uno
     * per tutta l'app: vince l'ultimo {@link #setActionProvider}.
     */
    interface ActionProvider {
        @NonNull
        List<Action> actions(@NonNull CarContext carContext, int account, long dialogId);
    }

    @Nullable
    private static volatile MessageDecorator decorator;
    @Nullable
    private static volatile ActionProvider actionProvider;

    private CarConversations() {
    }

    static void setMessageDecorator(@Nullable MessageDecorator d) {
        decorator = d;
    }

    static void setActionProvider(@Nullable ActionProvider p) {
        actionProvider = p;
    }

    /**
     * Gli ultimi messaggi di una chat come li vede l'head unit: quelli caricati dalla cache più quelli arrivati
     * durante questo viaggio (notifiche), deduplicati per id, dal più vecchio al più recente, massimo
     * {@link #MAX_MESSAGES}. Zero rete: tutto già in memoria o in cache locale.
     */
    @NonNull
    static ArrayList<MessageObject> merge(int account, long dialogId, @Nullable List<MessageObject> loaded) {
        ArrayList<MessageObject> all = new ArrayList<>();
        HashSet<Integer> ids = new HashSet<>();
        if (loaded != null) {
            for (MessageObject mo : loaded) {
                if (mo != null && mo.messageOwner != null && ids.add(mo.getId())) {
                    all.add(mo);
                }
            }
        }
        for (MessageObject mo : NotificationsController.getInstance(account).getPushMessagesSnapshot()) {
            if (mo != null && mo.messageOwner != null && mo.getDialogId() == dialogId && ids.add(mo.getId())) {
                all.add(mo);
            }
        }
        // minSdk 23: niente Comparator.comparingInt (API 24) senza core library desugaring
        Collections.sort(all, (a, b) -> Integer.compare(a.messageOwner.date, b.messageOwner.date));
        while (all.size() > MAX_MESSAGES) {
            all.remove(0);
        }
        return all;
    }

    /**
     * @param onAvatar invalidate() della schermata: l'avatar arriva dopo, decodificato fuori dal thread UI
     * @return null se non c'è nulla da far leggere all'host (ConversationItem vieta la lista vuota)
     */
    @Nullable
    static ConversationItem item(@NonNull CarContext carContext, int account, long dialogId, @NonNull String title,
                                 boolean group, @Nullable TLRPC.User user, @Nullable TLRPC.Chat chat,
                                 @NonNull List<MessageObject> messages, @Nullable Runnable onAvatar) {
        List<CarMessage> carMessages = new ArrayList<>();
        for (MessageObject mo : messages) {
            CarMessage cm = message(account, dialogId, user, chat, mo);
            if (cm != null) {
                carMessages.add(cm);
            }
        }
        if (carMessages.isEmpty()) {
            return null;
        }

        ConversationItem.Builder b = new ConversationItem.Builder(
                "d" + dialogId,
                CarText.create(title),
                self(account),
                carMessages,
                new Callback(account, dialogId))
                .setGroupConversation(group);

        CarIcon icon = CarAvatars.of(account, user, chat, onAvatar);
        if (icon != null) {
            b.setIcon(icon);
        }
        Action action = null;
        ActionProvider provider = actionProvider;
        if (provider != null) {
            try {
                for (Action candidate : provider.actions(carContext, account, dialogId)) {
                    if (candidate != null) {
                        action = candidate;
                        break;                     // maxActions = 1: la seconda farebbe lanciare la validazione
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (action != null) {
            b.addAction(action);
        }
        return b.build();
    }

    @Nullable
    private static CarMessage message(int account, long dialogId, @Nullable TLRPC.User user,
                                      @Nullable TLRPC.Chat chat, @Nullable MessageObject mo) {
        if (mo == null || mo.messageOwner == null) {
            return null;
        }
        String[] senderName = new String[1];
        String body = null;
        try {
            body = NotificationsController.getInstance(account)
                    .getShortStringForMessage(mo, senderName, new boolean[1]);
        } catch (Throwable ignored) {
        }
        if (body == null && mo.messageText != null) {
            body = mo.messageText.toString();
        }
        if (body == null) {
            return null;
        }

        CarMessage.Builder b = new CarMessage.Builder()
                .setBody(CarText.create(body))
                .setReceivedTimeEpochMillis(((long) mo.messageOwner.date) * 1000L)
                .setSender(sender(account, dialogId, user, chat, mo, senderName[0]))
                .setRead(!mo.isUnread());
        MessageDecorator d = decorator;
        if (d != null) {
            try {
                d.decorate(b, account, mo);
            } catch (Throwable ignored) {
            }
        }
        return b.build();
    }

    @NonNull
    private static Person sender(int account, long dialogId, @Nullable TLRPC.User user, @Nullable TLRPC.Chat chat,
                                 @NonNull MessageObject mo, @Nullable String fallbackName) {
        if (mo.isOut()) {
            return self(account);
        }
        long senderId = mo.getSenderId();
        if (senderId != 0) {
            TLRPC.User from = MessagesController.getInstance(account).getUser(senderId);
            if (from != null) {
                return person(UserObject.getUserName(from), "u" + senderId,
                        CarAvatars.of(account, from, null, null));
            }
        }
        if (fallbackName != null && !fallbackName.isEmpty()) {
            return person(fallbackName, senderId != 0 ? "u" + senderId : "d" + dialogId, null);
        }
        if (user != null) {
            return person(UserObject.getUserName(user), "u" + user.id, null);
        }
        if (chat != null) {
            return person(chat.title != null ? chat.title : " ", "c" + chat.id, null);
        }
        return person(" ", "d" + dialogId, null);
    }

    @NonNull
    static Person self(int account) {
        UserConfig uc = UserConfig.getInstance(account);
        TLRPC.User me = uc.getCurrentUser();
        return person(LocaleController.getString(R.string.FromYou), "self" + uc.getClientUserId(),
                me != null ? CarAvatars.of(account, me, null, null) : null);
    }

    @NonNull
    static Person person(@Nullable String name, @NonNull String key, @Nullable CarIcon icon) {
        // 1.7.0: PersonsEqualityHelper legge name e key di ogni Person -> entrambi non nulli, sempre.
        Person.Builder b = new Person.Builder()
                .setName(name == null || name.isEmpty() ? " " : name)
                .setKey(key);
        if (icon != null) {
            b.setIcon(icon.getIcon());
        }
        return b.build();
    }

    /** true se la chat è un gruppo/supergruppo (l'host premette il nome di chi parla quando legge). */
    static boolean isGroup(int account, long dialogId) {
        if (DialogObject.isUserDialog(dialogId)) {
            return false;
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        return chat != null && (!ChatObject.isChannel(chat) || ChatObject.isMegagroup(chat));
    }

    /**
     * Callback dell'host per una chat: risposta vocale/testuale e "segna come letto".
     *
     * <p>Entrambe fanno esattamente quello che fa il telefono — nessuna soppressione di ricevute di lettura, di
     * stato online o di "sta scrivendo" (termini API Telegram). Il lavoro vero va sul thread UI via
     * {@code runOnUIThread}: l'host ci chiama dal suo thread di binder e ha un watchdog.</p>
     */
    private static final class Callback implements ConversationCallback {
        private final int account;
        private final long dialogId;

        Callback(int account, long dialogId) {
            this.account = account;
            this.dialogId = dialogId;
        }

        @Override
        public void onMarkAsRead() {
            AndroidUtilities.runOnUIThread(() -> markRead(account, dialogId));
        }

        @Override
        public void onTextReply(@NonNull String text) {
            if (text.trim().isEmpty()) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                AccountInstance.getInstance(account).getSendMessagesHelper()
                        .sendMessage(SendMessagesHelper.SendMessageParams.of(text, dialogId));
                markRead(account, dialogId);
            });
        }
    }

    /** Thread UI. Legge il top_message fresco: la ricevuta deve coprire tutto quello che l'host ha letto. */
    static void markRead(int account, long dialogId) {
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.Dialog d = mc.getDialog(dialogId);
        int maxId = d != null ? d.top_message : 0;
        int date = 0;
        ArrayList<MessageObject> top = mc.dialogMessage.get(dialogId);
        if (top != null && !top.isEmpty() && top.get(0).messageOwner != null) {
            maxId = Math.max(maxId, top.get(0).getId());
            date = top.get(0).messageOwner.date;
        }
        if (maxId == 0) {
            return;
        }
        mc.markDialogAsRead(dialogId, maxId, maxId, date, false, 0, 0, true, 0);
        mc.markReactionsAsRead(dialogId, 0);
    }
}

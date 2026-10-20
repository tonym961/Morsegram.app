/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.carlabs.car;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.messaging.model.ConversationItem;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Header;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.List;

import it.iotatec.morsegram.auto.MorsegramAutoCommon;

/**
 * Una chat sull'head unit: <b>una sola riga</b>, un {@link ConversationItem}, e l'host che legge ad alta voce.
 *
 * <p>Qui non si disegna nessun messaggio: il testo va dentro i {@code CarMessage} e l'host lo pronuncia (regola
 * Android Auto per la messaggistica). Le callback standard dell'host — risposta dettata e "segna come letto" —
 * arrivano a {@code SendMessagesHelper} e {@code MessagesController.markDialogAsRead} via
 * {@link CarConversations}, quindi le ricevute di lettura si comportano come sul telefono.</p>
 *
 * <p>I messaggi vengono presi dalla cache locale ({@code loadMessages(..., fromCache = true)}, zero rete) e uniti
 * a quelli arrivati durante il viaggio. Il caricamento è asincrono: la schermata mostra lo stato "loading"
 * dell'host e si ridisegna da {@code messagesDidLoad} con {@code invalidate()}.</p>
 */
public class ConversationScreen extends Screen
        implements DefaultLifecycleObserver, NotificationCenter.NotificationCenterDelegate {

    private final int account;
    private final Runnable onAvatarReady = this::invalidate;
    private final long dialogId;
    private final int classGuid = ConnectionsManager.generateClassGuid();

    @Nullable
    private ArrayList<MessageObject> loaded;
    private boolean loadKicked;
    @Nullable
    private NotificationCenter.ObserversGroup observers;

    public ConversationScreen(@NonNull CarContext carContext, int account, long dialogId) {
        super(carContext);
        this.account = account;
        this.dialogId = dialogId;
        getLifecycle().addObserver(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        unsubscribe();
        observers = NotificationCenter.getInstance(account).createObserversGroup(this)
                .add(NotificationCenter.messagesDidLoad)
                .add(NotificationCenter.didReceiveNewMessages)
                .add(NotificationCenter.messagesDeleted)
                .addGlobal(NotificationCenter.activeAccountChanged);
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        unsubscribe();
    }

    private void unsubscribe() {
        if (observers != null) {
            observers.removeAllObservers();
            observers = null;
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.activeAccountChanged) {
            if (UserConfig.selectedAccount != this.account) {
                getScreenManager().pop();          // l'account è cambiato: questa chat non è più "la" chat
            }
            return;
        }
        if (id == NotificationCenter.messagesDidLoad) {
            if (args.length > 10 && args[10] instanceof Integer && (Integer) args[10] == classGuid) {
                loaded = new ArrayList<>();
                if (args[2] instanceof List) {
                    // niente cast generico: l'argomento della notifica è una List "nuda"
                    for (Object o : (List<?>) args[2]) {
                        if (o instanceof MessageObject) {
                            loaded.add((MessageObject) o);
                        }
                    }
                }
                invalidate();
            }
            return;
        }
        if (id == NotificationCenter.didReceiveNewMessages || id == NotificationCenter.messagesDeleted) {
            // il merge rilegge sempre lo snapshot delle notifiche: basta ridisegnare
            invalidate();
        }
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        String title = title();
        Header header = new Header.Builder()
                .setTitle(title.isEmpty() ? " " : title)
                .setStartHeaderAction(Action.BACK)
                .build();

        if (AndroidUtilities.needShowPasscode() || !MorsegramAutoCommon.allowed(account, dialogId)) {
            return new MessageTemplate.Builder(LocaleController.getString("MorsegramCarLocked"))
                    .setHeader(header)
                    .build();
        }

        if (!loadKicked) {
            loadKicked = true;
            // MessagesController vuole il thread UI; onGetTemplate ci è già, ma il post tiene la callback corta
            AndroidUtilities.runOnUIThread(() -> MessagesController.getInstance(account).loadMessages(
                    dialogId, 0, false, CarConversations.MAX_MESSAGES, 0, 0, true, 0,
                    classGuid, 2, 0, 0, 0, 0, 0, false));
        }

        ArrayList<MessageObject> messages = CarConversations.merge(account, dialogId, loaded);
        if (messages.isEmpty()) {
            if (loaded == null) {
                return new ListTemplate.Builder().setHeader(header).setLoading(true).build();
            }
            return new MessageTemplate.Builder(LocaleController.getString("MorsegramCarNoMessages"))
                    .setHeader(header)
                    .build();
        }

        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.User user = DialogObject.isUserDialog(dialogId) ? mc.getUser(dialogId) : null;
        TLRPC.Chat chat = DialogObject.isUserDialog(dialogId) ? null : mc.getChat(-dialogId);
        ConversationItem item = CarConversations.item(getCarContext(), account, dialogId, title,
                CarConversations.isGroup(account, dialogId), user, chat, messages, onAvatarReady);
        if (item == null) {
            return new MessageTemplate.Builder(LocaleController.getString("MorsegramCarNoMessages"))
                    .setHeader(header)
                    .build();
        }
        ItemList.Builder items = new ItemList.Builder().addItem(item);
        if (CarVoiceActions.isAvailable(account, dialogId)) {
            items.addItem(CarVoiceActions.listenRow(this, account, dialogId, title, messages));   // vocali: ascolto
        }
        if (CarRecordActions.isAvailable(account, dialogId)) {
            items.addItem(CarRecordActions.recordRow(this, account, dialogId));                   // vocali: registra
        }
        return new ListTemplate.Builder()
                .setHeader(header)
                .setSingleList(items.build())
                .build();
    }

    private String title() {
        MessagesController mc = MessagesController.getInstance(account);
        if (DialogObject.isUserDialog(dialogId)) {
            TLRPC.User user = mc.getUser(dialogId);
            if (user == null) {
                return "";
            }
            return UserObject.isUserSelf(user)
                    ? LocaleController.getString(org.telegram.messenger.R.string.SavedMessages)
                    : UserObject.getUserName(user);
        }
        TLRPC.Chat chat = mc.getChat(-dialogId);
        return chat != null && chat.title != null ? chat.title : "";
    }
}

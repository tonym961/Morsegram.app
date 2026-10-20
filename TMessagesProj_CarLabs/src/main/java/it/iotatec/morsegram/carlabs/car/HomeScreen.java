/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.carlabs.car;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Tab;
import androidx.car.app.model.TabContents;
import androidx.car.app.model.TabTemplate;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramMediaSession;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

import it.iotatec.morsegram.auto.MorsegramAutoCommon;

/**
 * Home Android Auto: {@link TabTemplate} con "Chat" (lista chat reale, ricerca, conversazione letta dall'host)
 * e "Musica" (chat con file musicali).
 *
 * <p>La scheda "Notifiche" di prima è stata <b>sostituita</b> dalla scheda "Chat": mostrava solo le chat che
 * avevano notificato durante il viaggio, cioè un sottoinsieme della lista chat, e quelle chat stanno comunque
 * in testa alla nuova lista (ordine del telefono: pin, poi data decrescente). Una scheda in meno nella striscia
 * è un tocco in meno da fare in marcia; la lettura ad alta voce e la risposta dettata restano, spostate dentro
 * {@link ConversationScreen}.</p>
 */
public class HomeScreen extends Screen
        implements DefaultLifecycleObserver, NotificationCenter.NotificationCenterDelegate {

    private static final String TAB_CHATS = "tab_chats";
    private static final String TAB_MUSIC = "tab_music";

    private static final int MAX_MUSIC_DIALOGS = 50;

    private int currentAccount;
    private final ChatsTab chatsTab = new ChatsTab(this);
    private final Runnable onAvatarReady = this::invalidate;

    private String activeTabId = TAB_CHATS;
    private boolean musicLoadKicked;
    @Nullable
    private NotificationCenter.ObserversGroup observers;

    public HomeScreen(@NonNull CarContext carContext) {
        super(carContext);
        currentAccount = UserConfig.selectedAccount;
        getLifecycle().addObserver(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        // upstream deprecated addObserver(delegate, id) in favour of observer groups
        unsubscribe();
        observers = NotificationCenter.getInstance(currentAccount).createObserversGroup(this)
                .add(NotificationCenter.dialogsNeedReload)
                .add(NotificationCenter.didReceiveNewMessages)
                .add(NotificationCenter.messagesRead)
                .addGlobal(NotificationCenter.pushMessagesUpdated)
                .addGlobal(NotificationCenter.notificationsCountUpdated)
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
            currentAccount = UserConfig.selectedAccount;
            musicLoadKicked = false;
            chatsTab.reset();
            CarAvatars.clear();
            onResume(this);                     // le notifiche per-account vanno riagganciate al nuovo account
            invalidate();
            return;
        }
        invalidate();
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        TabTemplate.Builder builder = new TabTemplate.Builder(new TabTemplate.TabCallback() {
            @Override
            public void onTabSelected(@NonNull String tabContentId) {
                activeTabId = tabContentId;
                invalidate();
            }
        });
        builder.setHeaderAction(Action.APP_ICON);

        builder.addTab(new Tab.Builder()
                .setContentId(TAB_CHATS)
                .setIcon(iconResource(R.drawable.msg_discussion))
                .setTitle(LocaleController.getString("MorsegramCarChats"))
                .build());
        builder.addTab(new Tab.Builder()
                .setContentId(TAB_MUSIC)
                .setIcon(iconResource(R.drawable.filled_widget_music))
                .setTitle(LocaleController.getString(R.string.Music))
                .build());

        builder.setActiveTabContentId(activeTabId);
        builder.setTabContents(new TabContents.Builder(buildTabContent(activeTabId)).build());
        return builder.build();
    }

    private Template buildTabContent(String tabId) {
        if (TAB_MUSIC.equals(tabId)) {
            return buildMusicTemplate();
        }
        return chatsTab.template(currentAccount);
    }

    // ===== Music tab =====

    private Template buildMusicTemplate() {
        TelegramMediaSession session = TelegramMediaSession.getInstance(getCarContext().getApplicationContext());
        if (!session.isChatsLoaded()) {
            if (!musicLoadKicked) {
                musicLoadKicked = true;
                session.ensureLoaded(this::invalidate);
            }
            return new ListTemplate.Builder().setLoading(true).build();
        }
        ArrayList<Long> dialogs = session.getMusicDialogsSortedByVisibleOrder();
        if (dialogs == null || dialogs.isEmpty()) {
            return new MessageTemplate.Builder(LocaleController.getString(R.string.NoCarMusic))
                    .build();
        }
        ItemList.Builder list = new ItemList.Builder();
        int added = 0;
        for (int i = 0; i < dialogs.size() && added < MAX_MUSIC_DIALOGS; i++) {
            long dialogId = dialogs.get(i);
            // la lista di upstream arriva da SQL e scarta solo le chat segrete: le chat nascoste si filtrano qui
            if (!MorsegramAutoCommon.allowed(currentAccount, dialogId)) continue;
            ArrayList<MessageObject> messages = session.getMusicMessages(dialogId);
            if (messages == null || messages.isEmpty()) continue;
            String title;
            TLRPC.User user = null;
            TLRPC.Chat chat = null;
            if (DialogObject.isUserDialog(dialogId)) {
                user = session.getMusicUser(dialogId);
                if (user == null) continue;
                if (UserObject.isUserSelf(user)) {
                    title = LocaleController.getString(R.string.SavedMessages);
                } else {
                    title = ContactsController.formatName(user.first_name, user.last_name);
                }
            } else {
                chat = session.getMusicChat(-dialogId);
                if (chat == null) continue;
                title = chat.title != null ? chat.title : "";
            }
            if (TextUtils.isEmpty(title)) continue;

            final String rowTitle = title;
            Row.Builder row = new Row.Builder()
                    .setTitle(rowTitle)
                    .addText(LocaleController.formatPluralString("MusicFiles", messages.size()))
                    .setBrowsable(true)
                    .setOnClickListener(() -> getScreenManager().push(new MusicSongsScreen(getCarContext(), dialogId, rowTitle)));

            CarIcon icon = CarAvatars.of(currentAccount, user, chat, onAvatarReady);
            if (icon != null) {
                row.setImage(icon, Row.IMAGE_TYPE_LARGE);
            }
            list.addItem(row.build());
            added++;
        }
        return new ListTemplate.Builder()
                .setSingleList(list.build())
                .build();
    }

    private CarIcon iconResource(int resId) {
        return new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), resId)).build();
    }
}

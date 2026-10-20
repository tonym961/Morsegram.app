/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.carlabs.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * Scheda "Chat": la lista chat reale del telefono sull'head unit, a pagine.
 *
 * <p>Sostituisce la vecchia scheda "Notifiche", che mostrava solo le chat che avevano notificato durante il
 * viaggio: {@code getDialogs(0)} è un suo soprainsieme e ha in testa proprio quelle chat (ordine del telefono:
 * pin, poi data decrescente), quindi la scheda vecchia non aggiungeva niente e una scheda in meno è una
 * distrazione in meno.</p>
 *
 * <p>Impaginazione: il numero di righe per schermata non lo decidiamo noi ma l'host
 * ({@link ConstraintManager#CONTENT_LIMIT_TYPE_LIST}, 6 quando l'host non risponde). La prima riga è sempre
 * "Cerca chat", l'ultima "Altre chat"/"Prime chat": il resto sono chat. Niente scroll infinito, un tocco per
 * pagina, e la pagina avanza in-place (si resta nella scheda).</p>
 */
final class ChatsTab {

    private final Screen host;
    /** istanza stabile: CarAvatars coalizza le invalidate solo se è sempre lo stesso Runnable */
    private final Runnable onAvatarReady;
    private int page;
    private int listLimit = -1;

    ChatsTab(@NonNull Screen host) {
        this.host = host;
        this.onAvatarReady = host::invalidate;
    }

    /** Account cambiato o schermata ricreata: si riparte dalla prima pagina. */
    void reset() {
        page = 0;
    }

    @NonNull
    Template template(int account) {
        if (AndroidUtilities.needShowPasscode()) {
            // passcode attivo: niente titoli di chat sull'head unit finché non si sblocca sul telefono
            return new MessageTemplate.Builder(LocaleController.getString("MorsegramCarLocked")).build();
        }
        if (!CarChats.isReady(account)) {
            CarChats.kickLoad(account);
            return new ListTemplate.Builder().setLoading(true).build();
        }

        boolean multiAccount = UserConfig.getActivatedAccountsCount() > 1;
        int perPage = Math.max(3, limit() - (multiAccount ? 3 : 2));
        // una pagina in più per sapere se esiste "Altre chat" senza contare tutta la lista
        ArrayList<CarChats.Entry> all = CarChats.list(account, (page + 1) * perPage + 1);
        if (all.isEmpty()) {
            return new MessageTemplate.Builder(LocaleController.getString("MorsegramCarNoChats")).build();
        }
        int from = page * perPage;
        if (from >= all.size()) {
            page = 0;
            from = 0;
        }
        int to = Math.min(all.size(), from + perPage);

        ItemList.Builder list = new ItemList.Builder();
        if (multiAccount) {
            list.addItem(accountRow(account));
        }
        list.addItem(searchRow(account));
        for (int i = from; i < to; i++) {
            list.addItem(chatRow(account, all.get(i)));
        }
        if (all.size() > to) {
            list.addItem(new Row.Builder()
                    .setTitle(LocaleController.getString("MorsegramCarMoreChats"))
                    .setBrowsable(true)
                    .setOnClickListener(() -> {
                        page++;
                        host.invalidate();
                    })
                    .build());
        } else if (page > 0) {
            list.addItem(new Row.Builder()
                    .setTitle(LocaleController.getString("MorsegramCarFirstChats"))
                    .setBrowsable(true)
                    .setOnClickListener(() -> {
                        page = 0;
                        host.invalidate();
                    })
                    .build());
        }
        return new ListTemplate.Builder().setSingleList(list.build()).build();
    }

    @NonNull
    private Row chatRow(int account, @NonNull CarChats.Entry e) {
        Row.Builder row = new Row.Builder()
                .setTitle(e.title)
                .setBrowsable(true)
                .setOnClickListener(() -> host.getScreenManager()
                        .push(new ConversationScreen(host.getCarContext(), account, e.dialogId)));
        if (!e.meta.isEmpty()) {
            row.addText(e.meta);
        }
        if (e.unread > 0) {
            row.setNumericDecoration(e.unread);
        }
        CarIcon avatar = CarAvatars.of(account, e.user, e.chat, onAvatarReady);
        if (avatar != null) {
            row.setImage(avatar, Row.IMAGE_TYPE_SMALL);
        }
        return row.build();
    }

    @NonNull
    private Row searchRow(int account) {
        return new Row.Builder()
                .setTitle(LocaleController.getString("MorsegramCarSearch"))
                .setImage(icon(R.drawable.msg_search), Row.IMAGE_TYPE_ICON)
                .setBrowsable(true)
                .setOnClickListener(() -> host.getScreenManager()
                        .push(new ChatSearchScreen(host.getCarContext(), account)))
                .build();
    }

    @NonNull
    private Row accountRow(int account) {
        TLRPC.User me = UserConfig.getInstance(account).getCurrentUser();
        String name = me != null ? UserObject.getFirstName(me) : "";
        return new Row.Builder()
                .setTitle(LocaleController.getString("MorsegramCarAccount"))
                .addText(name)
                .setImage(icon(R.drawable.msg_openprofile), Row.IMAGE_TYPE_ICON)
                .setBrowsable(true)
                .setOnClickListener(() -> host.getScreenManager()
                        .push(new AccountsScreen(host.getCarContext())))
                .build();
    }

    private CarIcon icon(int resId) {
        return new CarIcon.Builder(IconCompat.createWithResource(host.getCarContext(), resId)).build();
    }

    /** Limite righe dell'host: una sola IPC per sessione, poi in cache (il valore non cambia a macchina ferma). */
    private int limit() {
        if (listLimit <= 0) {
            int value = 6;
            try {
                CarContext ctx = host.getCarContext();
                value = ctx.getCarService(ConstraintManager.class)
                        .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
            } catch (Throwable ignored) {
            }
            listLimit = value > 0 ? value : 6;
        }
        return listLimit;
    }
}

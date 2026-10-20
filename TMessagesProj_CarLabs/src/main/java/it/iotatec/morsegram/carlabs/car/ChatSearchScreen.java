/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.carlabs.car;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Row;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.model.Template;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;

import java.util.ArrayList;

/**
 * "Cerca chat": {@link SearchTemplate}, quindi il campo di ricerca e il <b>dettato vocale</b> sono dell'host —
 * il guidatore dice il nome della chat e tocca una riga sola.
 *
 * <p>La ricerca è locale sui titoli della lista chat già in memoria: nessuna rete, nessun ritardo, e le chat
 * nascoste restano fuori perché il filtro sta in {@link CarChats}. La tastiera non si apre da sola
 * ({@code setShowKeyboardByDefault(false)}): in marcia l'host la blocca comunque e la voce è la via giusta.</p>
 */
public class ChatSearchScreen extends Screen {

    private final int account;
    private final Runnable onAvatarReady = this::invalidate;
    private String query = "";
    private int listLimit = -1;

    public ChatSearchScreen(@NonNull CarContext carContext, int account) {
        super(carContext);
        this.account = account;
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        SearchTemplate.Builder b = new SearchTemplate.Builder(new SearchTemplate.SearchCallback() {
            @Override
            public void onSearchTextChanged(@NonNull String searchText) {
                query = searchText;
                invalidate();
            }

            @Override
            public void onSearchSubmitted(@NonNull String searchText) {
                query = searchText;
                invalidate();
            }
        })
                .setHeaderAction(Action.BACK)
                .setSearchHint(LocaleController.getString("MorsegramCarSearchHint"))
                .setShowKeyboardByDefault(false)
                .setInitialSearchText(query);

        if (AndroidUtilities.needShowPasscode()) {
            b.setItemList(message(LocaleController.getString("MorsegramCarLocked")));
            return b.build();
        }
        if (query.trim().isEmpty()) {
            b.setItemList(message(LocaleController.getString("MorsegramCarSearchHint")));
            return b.build();
        }

        ArrayList<CarChats.Entry> found = CarChats.search(account, query, limit());
        if (found.isEmpty()) {
            b.setItemList(message(LocaleController.getString("MorsegramCarNoResults")));
            return b.build();
        }
        ItemList.Builder list = new ItemList.Builder();
        for (CarChats.Entry e : found) {
            Row.Builder row = new Row.Builder()
                    .setTitle(e.title)
                    .setBrowsable(true)
                    .setOnClickListener(() -> getScreenManager()
                            .push(new ConversationScreen(getCarContext(), account, e.dialogId)));
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
            list.addItem(row.build());
        }
        b.setItemList(list.build());
        return b.build();
    }

    private static ItemList message(@Nullable String text) {
        return new ItemList.Builder().setNoItemsMessage(text == null ? " " : text).build();
    }

    private int limit() {
        if (listLimit <= 0) {
            int value = 6;
            try {
                value = getCarContext().getCarService(ConstraintManager.class)
                        .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST);
            } catch (Throwable ignored) {
            }
            listLimit = value > 0 ? value : 6;
        }
        return listLimit;
    }
}

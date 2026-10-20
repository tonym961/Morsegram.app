/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.carlabs.car;

import androidx.annotation.NonNull;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Header;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;

/**
 * Cambio account: visibile solo quando sul telefono ci sono più account attivi.
 *
 * <p>Scrive {@code UserConfig.selectedAccount} con {@code UserConfig.selectAccount}, lo stesso percorso del
 * telefono (posta {@code activeAccountChanged}), così lista chat, musica e notifiche seguono tutte insieme.</p>
 */
public class AccountsScreen extends Screen {

    private final Runnable onAvatarReady = this::invalidate;

    public AccountsScreen(@NonNull CarContext carContext) {
        super(carContext);
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        Header header = new Header.Builder()
                .setTitle(LocaleController.getString("MorsegramCarAccount"))
                .setStartHeaderAction(Action.BACK)
                .build();

        ItemList.Builder list = new ItemList.Builder();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig uc = UserConfig.getInstance(a);
            if (!uc.isClientActivated()) {
                continue;
            }
            TLRPC.User user = uc.getCurrentUser();
            if (user == null) {
                continue;
            }
            final int target = a;
            Row.Builder row = new Row.Builder()
                    .setTitle(UserObject.getUserName(user))
                    .setOnClickListener(() -> select(target));
            if (a == UserConfig.selectedAccount) {
                row.addText(LocaleController.getString("MorsegramCarAccountActive"));
            }
            CarIcon avatar = CarAvatars.of(a, user, null, onAvatarReady);
            if (avatar != null) {
                row.setImage(avatar, Row.IMAGE_TYPE_SMALL);
            }
            list.addItem(row.build());
        }
        return new ListTemplate.Builder()
                .setHeader(header)
                .setSingleList(list.build())
                .build();
    }

    private void select(int account) {
        if (account != UserConfig.selectedAccount) {
            AndroidUtilities.runOnUIThread(() -> {
                UserConfig.selectedAccount = account;
                UserConfig.getInstance(0).saveConfig(false);
                CarAvatars.clear();
                NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.activeAccountChanged, account);
            });
        }
        getScreenManager().pop();
    }
}

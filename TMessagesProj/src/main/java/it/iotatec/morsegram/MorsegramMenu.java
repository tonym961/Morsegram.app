/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import it.iotatec.morsegram.hidden.HiddenChats;
import it.iotatec.morsegram.hidden.HiddenLock;

/**
 * Costruttori generici delle voci Morsegram nei menu della lista chat, raggiunti dalle 3 righe S1 già presenti in
 * DialogsActivity (showChatPreview → {@link LocalPins#previewMenuItem}, createActionMode → {@link LocalPins#actionModeItem},
 * onItemClick(ACTION_ID) → {@link LocalPins#onActionModeItem}): 'Fissa in alto (locale)' + 'Nascondi chat' / 'Mostra
 * chat' senza nuove righe upstream. La voce 'Nascondi' dell'action mode gestisce il click da sé (la riga upstream
 * instrada solo {@link LocalPins#ACTION_ID}) e chiude l'action mode via il back dell'ActionBar (onItemClick(-1)).
 */
public final class MorsegramMenu {

    /** id voce overflow 'Nascondi/Mostra chat' dell'action mode (upstream usa 100-111, LocalPins 190). */
    public static final int HIDE_ACTION_ID = 191;

    private MorsegramMenu() {
    }

    /* ================================================================================ menu anteprima */

    /** Hook showChatPreview: una View (o null) con le voci Morsegram; più voci → colonna. */
    public static View previewMenuItems(BaseFragment fragment, long dialogId, int dialogsType) {
        final View pin = LocalPins.pinPreviewItem(fragment, dialogId, dialogsType);
        final View hide = hidePreviewItem(fragment, dialogId);
        if (pin == null) {
            return hide;
        }
        if (hide == null) {
            return pin;
        }
        final Context ctx = fragment.getParentActivity();
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(pin, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        box.addView(hide, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        return box;
    }

    /** 'Nascondi chat' / 'Mostra chat' nel menu long-press (null se non idonea: riga Archivio, community, self). */
    public static View hidePreviewItem(BaseFragment fragment, long dialogId) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return null;
        }
        final int account = fragment.getCurrentAccount();
        final boolean hidden = HiddenChats.isHidden(account, dialogId);
        if (!hidden && !HiddenChats.canHide(account, dialogId)) {
            return null;
        }
        final ActionBarMenuSubItem item = new ActionBarMenuSubItem(fragment.getParentActivity(), false, false);
        item.setTextAndIcon(LocaleController.getString(hidden ? "MorsegramUnhideChat" : "MorsegramHideChat"), hidden ? unhideIcon() : hideIcon());
        item.setMinimumWidth(160);
        item.setOnClickListener(v -> {
            fragment.finishPreviewFragment();
            final ArrayList<Long> ids = new ArrayList<>(1);
            ids.add(dialogId);
            // stesso ritardo della voce Pin upstream: la lista è 'frozen' durante la chiusura dell'anteprima
            AndroidUtilities.runOnUIThread(() -> applyHideToSelection(fragment, ids, null), 100);
        });
        return item;
    }

    /* ================================================================================== action mode */

    /**
     * Hook createActionMode: voci nell'overflow '⋯' dell'action mode multi-selezione (tablet / Fold aperto: niente
     * menu anteprima). Un solo setSubMenuDelegate su otherItem aggiorna entrambe all'apertura del sottomenu.
     *
     * @return la voce pin (compatibilità con la firma S1).
     */
    public static ActionBarMenuSubItem actionModeItems(ActionBarMenuItem otherItem, BaseFragment fragment, Utilities.Callback0Return<ArrayList<Long>> selected, Utilities.Callback0Return<Integer> dialogsType) {
        if (otherItem == null || fragment == null) {
            return null;
        }
        final ActionBarMenuSubItem pinItem = otherItem.addSubItem(LocalPins.ACTION_ID, R.drawable.msg_pin, LocaleController.getString("MorsegramPinLocal"));
        final ActionBarMenuSubItem hideItem = otherItem.addSubItem(HIDE_ACTION_ID, hideIcon(), LocaleController.getString("MorsegramHideChat"));
        hideItem.setOnClickListener(v -> {
            otherItem.closeSubMenu();
            final ArrayList<Long> ids = selected != null && selected.run() != null ? new ArrayList<>(selected.run()) : null;
            applyHideToSelection(fragment, ids, () -> exitActionMode(fragment));
        });
        otherItem.setSubMenuDelegate(new ActionBarMenuItem.ActionBarSubMenuItemDelegate() {
            @Override
            public void onShowSubMenu() {
                final Integer type = dialogsType != null ? dialogsType.run() : null;
                final ArrayList<Long> ids = selected != null ? selected.run() : null;
                LocalPins.updateActionModeItem(pinItem, fragment, ids, type);
                updateHideItem(hideItem, fragment, ids);
            }

            @Override
            public void onHideSubMenu() {
            }
        });
        return pinItem;
    }

    private static void updateHideItem(ActionBarMenuSubItem item, BaseFragment fragment, ArrayList<Long> ids) {
        boolean show = ids != null && !ids.isEmpty();
        boolean allHidden = true;
        if (show) {
            final int account = fragment.getCurrentAccount();
            int eligible = 0;
            for (Long id : ids) {
                if (id == null) {
                    continue;
                }
                if (HiddenChats.isHidden(account, id)) {
                    eligible++;
                } else if (HiddenChats.canHide(account, id)) {
                    eligible++;
                    allHidden = false;
                }
            }
            show = eligible > 0;
        }
        item.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            item.setTextAndIcon(LocaleController.getString(allHidden ? "MorsegramUnhideChat" : "MorsegramHideChat"), allHidden ? unhideIcon() : hideIcon());
        }
    }

    /** Equivale al tap sul back dell'action mode: DialogsActivity.hideActionMode(true) via onItemClick(-1). */
    private static void exitActionMode(BaseFragment fragment) {
        try {
            final ActionBar ab = fragment != null ? fragment.getActionBar() : null;
            if (ab != null && ab.isActionModeShowed() && ab.getActionBarMenuOnItemClick() != null) {
                ab.getActionBarMenuOnItemClick().onItemClick(-1);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /* =================================================================================== semantica */

    /**
     * Nasconde le idonee non ancora nascoste (prerequisito: PIN impostato, setup se manca; nascondere non richiede lo
     * sblocco, come archiviare); se sono TUTTE già nascoste le mostra (richiede lo sblocco: rivela). Un solo commit
     * per batch, bulletin con conteggio. {@code after} gira sempre (anche su annulla).
     */
    public static void applyHideToSelection(BaseFragment fragment, ArrayList<Long> dialogIds, Runnable after) {
        final Runnable done = after != null ? after : () -> { };
        if (fragment == null || dialogIds == null || dialogIds.isEmpty()) {
            done.run();
            return;
        }
        final int account = fragment.getCurrentAccount();
        final ArrayList<Long> toHide = new ArrayList<>();
        final ArrayList<Long> toShow = new ArrayList<>();
        for (Long id : dialogIds) {
            if (id == null) {
                continue;
            }
            if (HiddenChats.isHidden(account, id)) {
                toShow.add(id);
            } else if (HiddenChats.canHide(account, id)) {
                toHide.add(id);
            }
        }
        final Activity activity = fragment.getParentActivity();
        if (!toHide.isEmpty()) {
            HiddenLock.ensurePin(activity, () -> {
                showBulletin(fragment, true, HiddenChats.hideAll(account, toHide));
                done.run();
            }, done);
        } else if (!toShow.isEmpty()) {
            HiddenLock.authenticate(activity, () -> {
                showBulletin(fragment, false, HiddenChats.unhideAll(account, toShow));
                done.run();
            }, done);
        } else {
            done.run();
        }
    }

    private static void showBulletin(BaseFragment fragment, boolean hidden, int count) {
        if (count <= 0 || fragment == null || !BulletinFactory.canShowBulletin(fragment)) {
            return;
        }
        try {
            final String text;
            if (count == 1) {
                text = LocaleController.getString(hidden ? "MorsegramChatHidden" : "MorsegramChatUnhidden");
            } else {
                text = LocaleController.formatString(hidden ? "MorsegramChatsHiddenCount" : "MorsegramChatsUnhiddenCount", 0, count);
            }
            BulletinFactory.of(fragment).createSimpleBulletin(hidden ? R.raw.chats_hide : R.raw.chats_unhide, text).show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /* ======================================================================================= icone */

    private static int hideIcon() {
        final int res = MorsegramApp.hideIconRes();
        return res != 0 ? res : R.drawable.msg_archive;
    }

    private static int unhideIcon() {
        final int res = MorsegramApp.unhideIconRes();
        return res != 0 ? res : R.drawable.msg_unarchive;
    }
}

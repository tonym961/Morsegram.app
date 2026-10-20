/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.hidden;

import android.os.Bundle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;
import org.telegram.ui.TopicsFragment;

/**
 * Gate unico di apertura delle chat nascoste: 1 riga come PRIMA istruzione di {@code onFragmentCreate} in
 * ChatActivity, TopicsFragment (i forum si aprono lì, LEAK L1) e ProfileActivity. ActionBarLayout.presentFragment /
 * addFragmentToStack, ChatActivityContainer e RightSlidingDialogContainer abortiscono se onFragmentCreate ritorna
 * false, quindi copre lista, ricerca, profilo, link t.me, tap sulla notifica, bolla, anteprima, picker.
 * <p>
 * Se la chat è nascosta e la sezione è bloccata: nessun fragment (e nessun I/O: in TopicsFragment la riga precede
 * loadFullChat), si mostra il lucchetto ({@link HiddenLock#authenticate}) e, sbloccato, si ripresenta LO STESSO
 * fragment con una copia degli stessi argomenti → letture, typing, online, getFullChannel identici a una chat normale
 * (ToS 1.4). Mai abortire quando la sezione è sbloccata o la chat non è nascosta. ChatActivity con chatMode != 0
 * (contenitori embedded di SharedMediaLayout/HashtagActivity) non viene mai intercettata.
 */
public final class HiddenGate {

    private static boolean pending;

    private HiddenGate() {
    }

    /** dialog id dagli argomenti di ChatActivity/ProfileActivity (chat_id / user_id / enc_id). */
    public static long dialogIdOf(Bundle args) {
        if (args == null) {
            return 0;
        }
        final long chatId = args.getLong("chat_id", 0);
        if (chatId != 0) {
            return -chatId;
        }
        final long userId = args.getLong("user_id", 0);
        if (userId != 0) {
            return userId;
        }
        final int encId = args.getInt("enc_id", 0);
        if (encId != 0) {
            return DialogObject.makeEncryptedDialogId(encId);
        }
        return 0;
    }

    /** Hook ChatActivity.onFragmentCreate (prima istruzione). true → il chiamante ritorna false. */
    public static boolean intercept(ChatActivity fragment, Bundle args) {
        if (fragment == null || args == null || args.getInt("chatMode", 0) != 0) {
            return false;
        }
        return gate(fragment, dialogIdOf(args), () -> new ChatActivity(new Bundle(args)));
    }

    /** Hook TopicsFragment.onFragmentCreate (prima istruzione, prima di loadFullChat). */
    public static boolean interceptTopics(TopicsFragment fragment, Bundle args) {
        if (fragment == null || args == null) {
            return false;
        }
        final long chatId = args.getLong("chat_id", 0);
        if (chatId == 0) {
            return false;
        }
        return gate(fragment, -chatId, () -> TopicsFragment.getTopicsOrChat(fragment, new Bundle(args)));
    }

    /** Hook ProfileActivity.onFragmentCreate (opzionale): profilo di una chat nascosta dietro il lucchetto. */
    public static boolean interceptProfile(ProfileActivity fragment, Bundle args) {
        if (fragment == null || args == null) {
            return false;
        }
        return gate(fragment, dialogIdOf(args), () -> new ProfileActivity(new Bundle(args)));
    }

    private interface Factory {
        BaseFragment create();
    }

    /**
     * Non nascosta → false. Nascosta e sbloccata → FLAG_SECURE e false. Nascosta e bloccata → true e, fuori dal
     * ciclo di presentazione corrente, lucchetto e ripresentazione. In bolla (BubbleActivity, nessun LaunchActivity
     * come host) → true senza presentare nulla.
     */
    private static boolean gate(BaseFragment fragment, long dialogId, Factory again) {
        if (dialogId == 0) {
            return false;
        }
        final int account = fragment.getCurrentAccount();
        if (!HiddenChats.isHidden(account, dialogId)) {
            return false;
        }
        if (!HiddenLock.isLocked()) {
            HiddenLock.setSecure(true);
            return false;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("morsegram hidden gate: locked, abort " + fragment.getClass().getSimpleName());
        }
        if (fragment.isInBubbleMode() || LaunchActivity.instance == null) {
            return true;
        }
        if (pending) {
            return true;
        }
        pending = true;
        AndroidUtilities.runOnUIThread(() -> {
            final LaunchActivity activity = LaunchActivity.instance;
            if (activity == null || activity.isFinishing()) {
                pending = false;
                return;
            }
            HiddenLock.authenticate(activity, () -> {
                pending = false;
                try {
                    final BaseFragment next = again.create();
                    if (next != null) {
                        next.setCurrentAccount(account);
                        activity.presentFragment(next);
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }, () -> pending = false);
        });
        return true;
    }
}

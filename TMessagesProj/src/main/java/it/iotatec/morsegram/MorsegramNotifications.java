/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;

import androidx.collection.LongSparseArray;
import androidx.core.app.NotificationCompat;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;

import it.iotatec.morsegram.hidden.HiddenChats;

/**
 * Maschera locale delle notifiche delle chat nascoste (hook in NotificationsController, 4 righe: mask in
 * NotificationHolder.call(), maskSummary prima del build() del riepilogo, getStringForMessage, createNotificationShortcut).
 * <p>
 * Agisce SOLO su NotificationCompat.Builder locali dopo che la push è stata ricevuta e processata (processNewMessages
 * invariato): la notifica arriva sempre, nessun muting o notify_settings server (ToS 1.4). Il risultato è
 * "Morsegram / Nuovo messaggio": niente nome, avatar, MessagingStyle, persone (tel:), azioni (Rispondi/Segna
 * letto/bottoni inline), RemoteInput, shortcut/locus/bolla; restano gruppo, sortKey, when, deleteIntent, canale e il
 * contentIntent upstream (apre la chat → passa dal gate HiddenGate → lucchetto). Il bridge Wear resta (bridgeTag)
 * senza azioni: l'orologio vede lo stesso testo mascherato. Mai le stringhe NotificationHidden* / YouHaveNewMessage
 * upstream (dicono 'Telegram' e possono venire dal language pack): chiave Morsegram propria.
 */
public final class MorsegramNotifications {

    private static final String EXTRA_WEARABLE = "android.wearable.EXTENSIONS";

    private MorsegramNotifications() {
    }

    /** Testo neutro delle notifiche/righe mascherate. */
    public static String maskedText() {
        return LocaleController.getString("MorsegramNewMessage");
    }

    private static String appName() {
        return LocaleController.getString(R.string.AppName);
    }

    /** Hook getStringForMessage (prima istruzione): righe InboxStyle del riepilogo, testo Samsung, story push, popup. */
    public static boolean isHidden(int account, MessageObject messageObject) {
        return messageObject != null && HiddenChats.isHidden(account, messageObject.getDialogId());
    }

    /**
     * Hook NotificationHolder.call(), ultimo punto prima di notify(): se {@code hidden} (predicato calcolato nella
     * riga upstream: per le storie anyMatch sugli autori, altrimenti isHidden(dialogId)) spoglia il builder.
     */
    @SuppressLint("RestrictedApi")
    public static void mask(int account, NotificationCompat.Builder builder, boolean hidden) {
        if (!hidden || builder == null) {
            return;
        }
        try {
            final String text = maskedText();
            builder.setContentTitle(appName())
                    .setContentText(text)
                    .setTicker(text)
                    .setStyle(null)
                    .setLargeIcon((Bitmap) null)
                    .setSubText(null)
                    .setNumber(0)
                    .setShortcutId(null)
                    .setLocusId(null)
                    .setBubbleMetadata(null)
                    .setRemoteInputHistory(null);
            clearPeopleAndActions(builder);
            final Bundle extras = builder.getExtras();
            if (extras != null) {
                extras.remove(EXTRA_WEARABLE);
            }
            final long selfUserId = UserConfig.getInstance(account).getClientUserId();
            builder.extend(new NotificationCompat.WearableExtender().setBridgeTag("tgaccount" + selfUserId));
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Hook showExtraNotifications prima di {@code notificationBuilder.build()}: il riepilogo (emesso su SDK ≤ 27 o
     * con più chat) ha titolo = nome chat, avatar e addPerson tel: quando pushDialogs.size() == 1; le righe di testo
     * sono già mascherate da getStringForMessage.
     */
    @SuppressLint("RestrictedApi")
    public static void maskSummary(int account, LongSparseArray<Integer> pushDialogs, NotificationCompat.Builder builder) {
        if (builder == null || pushDialogs == null || !HiddenChats.hasHidden(account)) {
            return;
        }
        boolean hidden = false;
        for (int i = 0, n = pushDialogs.size(); i < n; i++) {
            if (HiddenChats.isHidden(account, pushDialogs.keyAt(i))) {
                hidden = true;
                break;
            }
        }
        if (!hidden) {
            return;
        }
        try {
            builder.setContentTitle(appName()).setLargeIcon((Bitmap) null);
            clearPeopleAndActions(builder);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Campi RestrictedApi di androidx.core (mActions/mPersonList/mPeople): guardati, con fallback sulle API pubbliche. */
    @SuppressLint("RestrictedApi")
    private static void clearPeopleAndActions(NotificationCompat.Builder builder) {
        try {
            builder.mActions.clear();
        } catch (Throwable e) {
            try {
                builder.clearActions();
            } catch (Throwable ignore) {
            }
        }
        try {
            builder.mPersonList.clear();
            builder.mPeople.clear();
        } catch (Throwable e) {
            try {
                builder.clearPeople();
            } catch (Throwable ignore) {
            }
        }
    }
}

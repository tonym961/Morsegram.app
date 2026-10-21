/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import android.text.TextUtils;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

import it.iotatec.morsegram.MorsegramDialogs;

/**
 * Contenuto di UNA chat per i widget VOCALE RAPIDO e CHAT SINGOLA, con la cache di {@link WidgetConfig}.
 * <p>
 * RISERVATEZZA, regola unica: se il filtro delle chat nascoste non è affidabile
 * ({@link MorsegramWidgets#canReadDialogs}), se lo slot su cui la chat era stata scelta non è piu' attivato
 * ({@link WidgetConfig#storedAccount}), oppure se la chat È nascosta, {@link #read} restituisce {@code null}
 * E CANCELLA la cache. Quindi una chat messa nel widget e nascosta DOPO sparisce dal widget al primo
 * aggiornamento, senza lasciare il nome in SharedPreferences. Il caso "chat scelta e poi nascosta" è lo stesso
 * scenario che lo studio B segnala come buco ALTO per la schermata di configurazione di upstream.
 * <p>
 * Nessuna lettura dal DB: solo {@code dialogs_dict} / {@code dialogMessage} di MessagesController (thread UI).
 * A processo freddo quelle strutture sono vuote e si usa la cache, che per costruzione contiene solo chat
 * verificate come NON nascoste.
 */
final class WidgetDialog {

    final String name;
    final String preview;
    final String time;
    final int unread;

    private WidgetDialog(String name, String preview, String time, int unread) {
        this.name = name;
        this.preview = preview;
        this.time = time;
        this.unread = unread;
    }

    /** null = non mostrare nulla di questa chat (nascosta, non verificabile, o non configurata). */
    static WidgetDialog read(int appWidgetId, int account, long dialogId) {
        if (dialogId == 0) {
            return null;
        }
        final int stored = WidgetConfig.storedAccount(appWidgetId);
        if (stored >= 0 && stored != account) {
            // lo slot su cui la chat era stata scelta non e' piu' attivato e WidgetConfig.account ha ripiegato su
            // un altro: il filtro delle nascoste di QUESTO account non dice nulla sulla chat di QUELLO. Stesso
            // trattamento del filtro non verificabile, perche' il rischio e' identico: senza il wipe, nome,
            // anteprima, ora e contatore dell'account da cui l'utente ha fatto logout resterebbero disegnati
            // sulla schermata iniziale, leggibili senza sbloccare il telefono, e nessun evento li cancellerebbe.
            WidgetConfig.wipeContent(appWidgetId);
            return null;
        }
        if (!MorsegramWidgets.canReadDialogs(account) || MorsegramDialogs.isHidden(account, dialogId)) {
            WidgetConfig.wipeContent(appWidgetId);
            return null;
        }
        String name = null;
        String preview = null;
        String time = null;
        int unread = 0;
        try {
            final MessagesController controller = MessagesController.getInstance(account);
            name = DialogObject.getName(account, dialogId);
            final TLRPC.Dialog dialog = controller.dialogs_dict.get(dialogId);
            if (dialog != null) {
                unread = Math.max(0, dialog.unread_count);
            }
            final ArrayList<MessageObject> messages = controller.dialogMessage.get(dialogId);
            final MessageObject message = messages == null || messages.isEmpty() ? null : messages.get(0);
            if (message != null) {
                preview = previewOf(message);
                if (message.messageOwner != null && message.messageOwner.date > 0) {
                    time = LocaleController.stringForMessageListDate(message.messageOwner.date);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (TextUtils.isEmpty(name)) {
            // dialoghi non ancora caricati: l'ultimo contenuto noto era già stato verificato non nascosto
            final String cachedName = WidgetConfig.cachedName(appWidgetId);
            if (TextUtils.isEmpty(cachedName)) {
                return null;
            }
            return new WidgetDialog(cachedName, WidgetConfig.cachedPreview(appWidgetId), WidgetConfig.cachedTime(appWidgetId), WidgetConfig.cachedUnread(appWidgetId));
        }
        if (TextUtils.isEmpty(preview)) {
            preview = WidgetConfig.cachedPreview(appWidgetId);
            if (time == null) {
                time = WidgetConfig.cachedTime(appWidgetId);
            }
        }
        WidgetConfig.putContent(appWidgetId, name, preview, time, unread);
        return new WidgetDialog(name, preview, time, unread);
    }

    /** Solo il nome, per il widget VOCALE RAPIDO (nessuna anteprima: lì il contenuto non serve). */
    static String nameOnly(int appWidgetId, int account, long dialogId) {
        final WidgetDialog dialog = read(appWidgetId, account, dialogId);
        return dialog == null ? null : dialog.name;
    }

    private static String previewOf(MessageObject message) {
        try {
            if (!TextUtils.isEmpty(message.caption)) {
                return message.caption.toString();
            }
            if (message.messageText != null) {
                return message.messageText.toString();
            }
            if (message.messageOwner != null && !TextUtils.isEmpty(message.messageOwner.message)) {
                return message.messageOwner.message;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return null;
    }
}

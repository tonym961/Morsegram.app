/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;

import it.iotatec.morsegram.theme.MorsegramPalette;

/**
 * La faccia dell'assistente verso l'upstream: TRE righe in {@code ChatActivity}, e nient'altro.
 *
 * <ol>
 * <li>{@link #attachChat} in {@code createView}, dove la barra della chat è già costruita. Una riga che fa due
 *     cose: aggiunge la voce 'Assistente' al menu '⋯' (come voce LAZY, {@code lazilyAddSubItem}: finché il menu non
 *     si apre non esiste nessuna vista, quindi l'apertura della chat non paga niente) e installa il SEGNO nella
 *     barra, che compare solo quando l'assistente è attivo su quella chat. Il click lo gestisce la voce stessa
 *     ({@code setOnClickListener}): non serve una seconda riga nel dispatcher dei menu.</li>
 * <li>{@link #fillMessageMenu} alla fine di {@code fillMessageMenu}, l'unico punto in cui upstream raccoglie le
 *     voci del menu di un messaggio nelle tre liste parallele: una riga aggiunge 'Traduci' e 'Bozza di risposta'
 *     per quel messaggio.</li>
 * <li>{@link #onMessageOption} in testa a {@code processSelectedOption}: i nostri due id non esistono nello
 *     switch di upstream, quindi serve una riga per intercettarli. Il lavoro è rinviato di 100 ms come fa già
 *     {@code MorsegramMenu}, così la finestra si apre dopo la chiusura del menu del messaggio.</li>
 * </ol>
 *
 * <p>Perché non meno di tre: le voci della lista chat passano da {@code ApplicationLoader.addItemOptions}
 * (extension point upstream, 0 righe) e da {@code MorsegramMenu}, ma quello è il menu della LISTA, non della chat
 * aperta; dentro la chat non esiste nessun extension point, né per la barra né per il menu di un messaggio. Le tre
 * righe sono il minimo per avere un ingresso di chat (riassunto, bozza, ricerca), un ingresso per messaggio
 * (traduzione) e il segno visibile richiesto quando l'assistente è attivo.</p>
 */
public final class AiEntry {

    /** id delle nostre voci nel menu di un messaggio: ben sopra l'intervallo upstream (max OPTION_* = 117). */
    public static final int OPTION_AI_TRANSLATE = 9101;
    public static final int OPTION_AI_DRAFT = 9102;

    private static final int SUBITEM_ID = 9103;
    private static final int MARK_ITEM_ID = 9104;

    private AiEntry() {
    }

    /* ============================================================================== barra della chat */

    /**
     * Voce 'Assistente' nel menu '⋯' della chat e segno di assistente attivo nella barra.
     *
     * @param menu       la barra dei menu della chat ({@code actionBar.createMenu()})
     * @param headerItem la voce '⋯', null nelle modalità che non ce l'hanno
     */
    public static void attachChat(ChatActivity chat, ActionBarMenu menu, ActionBarMenuItem headerItem) {
        if (chat == null || menu == null) {
            return;
        }
        try {
            if (chat.getChatMode() != 0 || chat.isInPreviewMode()) {
                // programmati, bozze rapide, messaggi di benvenuto: non sono una conversazione.
                // Anteprima della chat (pressione lunga nella lista): upstream porta a 0 l'alpha delle voci della
                // barra, una voce nostra opaca stonerebbe; e da un'anteprima non si usa un assistente.
                return;
            }
            final int account = chat.getCurrentAccount();
            final long dialogId = chat.getDialogId();
            if (!AiGate.canEnable(account, dialogId)) {
                return; // chat nascosta: nessuna voce, nessun segno, nessuna possibilità di attivare
            }
            if (headerItem != null) {
                headerItem.lazilyAddSubItem(SUBITEM_ID, R.drawable.input_ai, LocaleController.getString("MorsegramAiTitle"))
                        .setOnClickListener(v -> {
                            headerItem.closeSubMenu();
                            AiSheet.open(chat);
                        });
            }
            installMark(chat, menu, account, dialogId);
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    /**
     * Il segno richiesto: finché l'assistente è attivo su questa chat, nella barra c'è un'icona ambra che lo dice
     * (e che apre il menu dell'assistente). La vista viene creata solo la prima volta che serve davvero: su una
     * chat senza assistente non esiste, e l'apertura della chat non paga niente.
     */
    private static void installMark(ChatActivity chat, ActionBarMenu menu, int account, long dialogId) {
        final ActionBarMenuItem[] mark = new ActionBarMenuItem[1];
        final AiGate.Listener listener = () -> {
            final boolean on = AiGate.isEnabled(account, dialogId);
            if (on && mark[0] == null) {
                final ActionBarMenuItem item = menu.addItem(MARK_ITEM_ID, R.drawable.input_ai);
                item.setIconColor(MorsegramPalette.AMBER);
                item.setContentDescription(LocaleController.getString("MorsegramAiActiveHere"));
                item.setOnClickListener(v -> AiSheet.open(chat));
                mark[0] = item;
            }
            if (mark[0] != null) {
                mark[0].setVisibility(on ? View.VISIBLE : View.GONE);
            }
        };
        listener.onAiStateChanged();
        menu.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                AiGate.addListener(listener);
                listener.onAiStateChanged();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                AiGate.removeListener(listener);
            }
        });
        if (menu.isAttachedToWindow()) {
            AiGate.addListener(listener);
        }
    }

    /* ========================================================================== menu di un messaggio */

    /**
     * 'Traduci' e 'Bozza di risposta' per un messaggio, solo con assistente attivo su questa chat e solo se il
     * messaggio ha del testo (un adesivo non si traduce).
     */
    public static void fillMessageMenu(ChatActivity chat, MessageObject message, ArrayList<Integer> icons, ArrayList<CharSequence> items, ArrayList<Integer> options) {
        if (chat == null || message == null || message.messageOwner == null || icons == null || items == null || options == null) {
            return;
        }
        try {
            if (chat.getChatMode() != 0 || message.messageOwner.action != null) {
                return;
            }
            if (TextUtils.isEmpty(message.messageOwner.message)) {
                return;
            }
            if (!AiGate.isEnabled(chat.getCurrentAccount(), chat.getDialogId())) {
                return;
            }
            items.add(LocaleController.getString("MorsegramAiTranslate"));
            options.add(OPTION_AI_TRANSLATE);
            icons.add(R.drawable.outline_ai_translate2);
            if (!message.isOut()) {
                items.add(LocaleController.getString("MorsegramAiDraft"));
                options.add(OPTION_AI_DRAFT);
                icons.add(R.drawable.input_ai);
            }
        } catch (Throwable e) {
            org.telegram.messenger.FileLog.e(e);
        }
    }

    /**
     * Intercetta i nostri due id. Il lavoro è rinviato di 100 ms: la finestra deve aprirsi quando il menu del
     * messaggio ha finito di chiudersi (stesso accorgimento di {@code MorsegramMenu.hidePreviewItem}).
     *
     * @return true se l'id è nostro, quindi upstream non deve cercarlo nel suo switch
     */
    public static boolean onMessageOption(ChatActivity chat, int option, MessageObject message) {
        if (option != OPTION_AI_TRANSLATE && option != OPTION_AI_DRAFT) {
            return false;
        }
        if (chat == null || message == null || message.messageOwner == null) {
            return true;
        }
        final int account = chat.getCurrentAccount();
        final long dialogId = chat.getDialogId();
        if (!AiGate.isEnabled(account, dialogId)) {
            return true;
        }
        final String text = message.messageOwner.message;
        AndroidUtilities.runOnUIThread(() -> {
            if (option == OPTION_AI_TRANSLATE) {
                AiSheet.run(chat, account, dialogId, destination -> AiTasks.translate(text, destination), "MorsegramAiTranslate", false);
            } else {
                AiSheet.run(chat, account, dialogId, destination -> AiTasks.draft(account, dialogId, chat.messages, null, text, destination), "MorsegramAiDraft", true);
            }
        }, 100);
        return true;
    }
}

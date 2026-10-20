/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.fold;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Continuità della chat attraverso il rebuild di {@code LaunchActivity.invalidateTabletMode()} (piega/apertura del
 * dispositivo = passaggio telefono ↔ tablet). Upstream conserva le istanze di ChatActivity ma le ricostruisce con
 * clearViews()+createView() senza onPause(): la lista riparte dagli ultimi messaggi e il testo digitato dopo l'ultimo
 * saveDraft() andrebbe perso.
 * <ul>
 * <li>{@link #capture}: per ogni ChatActivity negli stack main/right/layers ricorda dialog id, topic id e il primo
 *     messaggio visibile con il suo offset (stessa semantica di ChatActivity.onPause → prefs 'diditem'), poi chiama
 *     {@code onPause()} (API pubblica, la stessa chiamata che upstream fa in checkLayout) così saveDraft() e le prefs
 *     di posizione vengono scritte: nessuna spunta soppressa, è la logica di pausa upstream. Salta la pausa se è in
 *     corso una registrazione vocale. La bringToFront() del rebuild chiama poi onResume(): coppia simmetrica.</li>
 * <li>{@link #restore}: sulla chatListView ricostruita riporta lo stesso messaggio alla stessa altezza
 *     (LinearLayoutManager.scrollToPositionWithOffset(pos, -offset - paddingBottom, true), come il restore upstream
 *     da 'diditemo'), fallback scrollToMessageId. Se la ChatActivity fosse sparita dagli stack (mai in upstream 13.0.2)
 *     riapre lo stesso dialogo con presentFragment, nel pannello giusto per la modalità corrente.</li>
 * </ul>
 * Tutto difensivo: null o eccezione → non fa nulla, upstream prosegue come prima.
 */
public final class FoldContinuity {

    private static final class Entry {
        final ChatActivity chat;
        final long dialogId;
        final long topicId;
        RecyclerListView oldList;
        int messageId;
        int offset;

        Entry(ChatActivity chat) {
            this.chat = chat;
            this.dialogId = chat.getDialogId();
            this.topicId = chat.getTopicId();
        }
    }

    private static final ArrayList<Entry> entries = new ArrayList<>();
    private static List<BaseFragment> mainStack, rightStack, layerStack;

    private FoldContinuity() {
    }

    /** Hook LaunchActivity.invalidateTabletMode, subito dopo {@code if (wasTablet != isTablet()) {}. */
    public static void capture(List<BaseFragment> main, List<BaseFragment> right, List<BaseFragment> layers) {
        entries.clear();
        mainStack = main;
        rightStack = right;
        layerStack = layers;
        try {
            scan(main);
            scan(right);
            scan(layers);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void scan(List<BaseFragment> stack) {
        if (stack == null) {
            return;
        }
        for (BaseFragment fragment : new ArrayList<>(stack)) {
            if (!(fragment instanceof ChatActivity)) {
                continue;
            }
            final ChatActivity chat = (ChatActivity) fragment;
            final Entry entry = new Entry(chat);
            try {
                final RecyclerListView list = chat.getChatListView();
                if (list != null && list.getParent() != null) {
                    entry.oldList = list;
                    readPosition(entry, list);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            pauseForDraft(chat);
            entries.add(entry);
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("morsegram fold: captured chat " + entry.dialogId + "/" + entry.topicId + " mid " + entry.messageId + " offset " + entry.offset);
            }
        }
    }

    private static void readPosition(Entry entry, RecyclerListView list) {
        final RecyclerView.LayoutManager lm = list.getLayoutManager();
        if (!(lm instanceof LinearLayoutManager)) {
            return;
        }
        final int position = ((LinearLayoutManager) lm).findFirstVisibleItemPosition();
        if (position <= 0) {
            return; // RecyclerView.NO_POSITION o ultimo messaggio già in vista (layout invertito): niente da ripristinare
        }
        for (int p = position; p <= position + 1; p++) {
            final RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(p);
            if (holder == null) {
                continue;
            }
            MessageObject message = null;
            if (holder.itemView instanceof ChatMessageCell) {
                message = ((ChatMessageCell) holder.itemView).getMessageObject();
            } else if (holder.itemView instanceof ChatActionCell) {
                message = ((ChatActionCell) holder.itemView).getMessageObject();
            }
            if (message != null && message.getId() != 0 && !message.isSponsored()) {
                entry.messageId = message.getId();
                entry.offset = holder.itemView.getBottom() - list.getMeasuredHeight();
                return;
            }
        }
    }

    private static void pauseForDraft(ChatActivity chat) {
        try {
            if (chat.isPaused()) {
                return;
            }
            final ChatActivityEnterView enterView = chat.getChatActivityEnterView();
            if (enterView != null && enterView.isRecordingAudioVideo()) {
                return; // onPause fermerebbe la registrazione: meglio rischiare la bozza
            }
            chat.onPause();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Hook LaunchActivity.invalidateTabletMode, a fine blocco {@code if (wasTablet != isTablet()) {}. */
    public static void restore() {
        final ArrayList<Entry> list = new ArrayList<>(entries);
        entries.clear();
        for (Entry entry : list) {
            try {
                restore(entry);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        mainStack = rightStack = layerStack = null;
    }

    private static void restore(Entry entry) {
        final ChatActivity chat = entry.chat;
        if (!contains(mainStack, chat) && !contains(rightStack, chat) && !contains(layerStack, chat)) {
            reopen(entry);
            return;
        }
        if (entry.messageId == 0) {
            return;
        }
        final RecyclerListView list = chat.getChatListView();
        if (list == null || list == entry.oldList || list.getParent() == null) {
            return; // non ricostruita o non visibile (sotto un layer): posizione upstream
        }
        final int messageId = entry.messageId;
        final int offset = entry.offset;
        list.post(() -> applyScroll(chat, list, messageId, offset));
    }

    private static void applyScroll(ChatActivity chat, RecyclerListView list, int messageId, int offset) {
        try {
            if (chat.getChatListView() != list || list.getParent() == null) {
                return;
            }
            final RecyclerView.Adapter<?> adapter = list.getAdapter();
            final RecyclerView.LayoutManager lm = list.getLayoutManager();
            int index = -1;
            int startRow = 0;
            if (adapter instanceof ChatActivity.ChatActivityAdapter) {
                final ChatActivity.ChatActivityAdapter chatAdapter = (ChatActivity.ChatActivityAdapter) adapter;
                startRow = chatAdapter.messagesStartRow;
                index = indexOf(chatAdapter.getMessages(), messageId);
            }
            if (index >= 0 && lm instanceof LinearLayoutManager) {
                ((LinearLayoutManager) lm).scrollToPositionWithOffset(startRow + index, -offset - list.getPaddingBottom(), true);
            } else {
                chat.scrollToMessageId(messageId, 0, false, 0, true, 0);
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("morsegram fold: restored mid " + messageId + " index " + index + " offset " + offset);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static int indexOf(List<MessageObject> messages, int messageId) {
        if (messages == null) {
            return -1;
        }
        for (int i = 0, n = messages.size(); i < n; i++) {
            final MessageObject m = messages.get(i);
            if (m != null && m.getId() == messageId) {
                return i;
            }
        }
        return -1;
    }

    private static boolean contains(List<BaseFragment> stack, BaseFragment fragment) {
        return stack != null && stack.contains(fragment);
    }

    /** Solo se upstream avesse scartato l'istanza: stesso dialogo, stesse chiamate di LaunchActivity.presentFragment. */
    private static void reopen(Entry entry) {
        if (entry.dialogId == 0 || entry.topicId != 0) {
            return;
        }
        final LaunchActivity activity = LaunchActivity.instance;
        if (activity == null || activity.isFinishing()) {
            return;
        }
        final long dialogId = entry.dialogId;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                if (!activity.isFinishing()) {
                    activity.presentFragment(ChatActivity.of(dialogId));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }
}

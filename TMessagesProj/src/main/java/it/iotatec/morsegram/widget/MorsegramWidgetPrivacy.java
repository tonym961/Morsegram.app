/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesStorage;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import it.iotatec.morsegram.MorsegramDialogs;

/**
 * Chiusura dei percorsi dei widget di UPSTREAM da cui una chat nascosta restava visibile (studio B, sezione
 * "risks"). In 0.1.2 era già filtrato {@code MessagesStorage.getWidgetDialogs} sui due rami con selezione
 * salvata; qui si coprono gli altri.
 * <ol>
 * <li>{@code MessagesStorage.getWidgetDialogs}, ramo "selezione vuota" (MessagesStorage.java:10272-10285): la
 *     query prende le prime 10 chat per data SENZA filtro. Era il buco CRITICO: un widget piazzato senza
 *     configurarlo ({@code widgetFeatures="configuration_optional"}) mostrava nome, avatar, anteprima, ora e
 *     non letti delle chat nascoste sulla schermata iniziale, leggibili senza sbloccare il telefono. Rimedio:
 *     1 riga che salta le nascoste nel ciclo. Conseguenza accettata: se fra le prime 10 ci sono chat nascoste,
 *     il widget ne mostra meno di 10 (o la vista vuota, se tutte e 10 lo sono): preferibile a pescare oltre il
 *     LIMIT, che vorrebbe una query diversa, cioè una modifica upstream vera.</li>
 * <li>{@code EditWidgetActivity} (schermata "modifica widget", raggiungibile dalla riga "tocca per modificare"
 *     del widget, che passa dal passcode Telegram ma NON dal lucchetto Morsegram): il costruttore riempiva la
 *     selezione con gli id nascosti e la elencava con nome e avatar veri. Rimedio in due righe,
 *     {@link #stashSelection} all'apertura e {@link #restoreSelection} al salvataggio, così la chat nascosta
 *     non viene mostrata MA non viene nemmeno perduta: resta nella selezione salvata del widget. Un filtro
 *     secco avrebbe cancellato silenziosamente una scelta dell'utente.</li>
 * <li>{@code EditWidgetActivity} anteprima del widget contatti con selezione vuota: legge {@code chat_hints}
 *     senza filtro. {@link #hintDialogId} restituisce l'n-esimo suggerimento NON nascosto.</li>
 * </ol>
 * Lo stato di {@link #stashSelection} vive quanto la schermata di modifica: una mappa statica per widgetId,
 * svuotata al salvataggio. Thread UI (costruttore del fragment e voce "Fatto").
 */
public final class MorsegramWidgetPrivacy {

    private static final Map<Integer, ArrayList<Long>> stashed = new HashMap<>();

    private MorsegramWidgetPrivacy() {
    }

    /**
     * Hook EditWidgetActivity, costruttore, subito dopo {@code getWidgetDialogIds(..., edit=true)}: toglie dalla
     * selezione le chat nascoste e le mette da parte per {@link #restoreSelection}.
     */
    public static void stashSelection(int account, int widgetId, ArrayList<Long> selectedDialogs) {
        if (selectedDialogs == null || selectedDialogs.isEmpty()) {
            stashed.remove(widgetId);
            return;
        }
        ArrayList<Long> hidden = null;
        for (int i = selectedDialogs.size() - 1; i >= 0; i--) {
            final long dialogId = selectedDialogs.get(i);
            if (MorsegramDialogs.isHidden(account, dialogId)) {
                if (hidden == null) {
                    hidden = new ArrayList<>();
                }
                hidden.add(0, dialogId);
                selectedDialogs.remove(i);
            }
        }
        if (hidden == null) {
            stashed.remove(widgetId);
        } else {
            stashed.put(widgetId, hidden);
        }
    }

    /**
     * Hook EditWidgetActivity, voce "Fatto", subito prima di {@code putWidgetDialogs}: rimette le chat nascoste
     * messe da parte, così il salvataggio non le cancella dalla configurazione del widget.
     */
    public static void restoreSelection(int widgetId, ArrayList<MessagesStorage.TopicKey> topicKeys) {
        final ArrayList<Long> hidden = stashed.remove(widgetId);
        if (hidden == null || topicKeys == null) {
            return;
        }
        try {
            for (int i = 0; i < hidden.size(); i++) {
                topicKeys.add(MessagesStorage.TopicKey.of(hidden.get(i), 0));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Hook EditWidgetActivity, anteprima del widget contatti (EditWidgetActivity.java:504-506) al posto di
     * {@code hints.get(num).peer.user_id}: l'n-esimo suggerimento NON nascosto, 0 se non ce ne sono più
     * (la riga dell'anteprima resta vuota, come quando i suggerimenti finiscono).
     */
    public static long hintDialogId(int account, ArrayList<TLRPC.TL_topPeer> hints, int index) {
        if (hints == null || index < 0) {
            return 0;
        }
        int seen = 0;
        for (int i = 0; i < hints.size(); i++) {
            final TLRPC.TL_topPeer peer = hints.get(i);
            if (peer == null || peer.peer == null) {
                continue;
            }
            final long userId = peer.peer.user_id;
            if (userId == 0 || MorsegramDialogs.isHidden(account, userId)) {
                continue;
            }
            if (seen == index) {
                return userId;
            }
            seen++;
        }
        return 0;
    }
}

/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import org.telegram.tgnet.TLRPC;

/**
 * Superficie unica per ordinamento/filtro della lista chat, così i file upstream (MessagesController, DialogCell,
 * DialogsAdapter) hanno un solo punto di contatto e le feature future (chat nascoste, M4) non aggiungono righe lì.
 * Oggi delega a {@link LocalPins}. Letture lock-free O(1) senza allocazioni: il comparatore gira n log n a ogni
 * messaggio. {@code filter(account, dialogsType, in)} è RISERVATO a M4 (1 riga futura in DialogsActivity.getDialogsArray).
 */
public final class MorsegramDialogs {

    private MorsegramDialogs() {
    }

    /**
     * Hook MessagesController.dialogComparator, tra il blocco dei pin server e il confronto per data: a quel punto
     * entrambi i dialoghi sono non-folder e non pin server. != 0 → ordine deciso qui; 0 → upstream prosegue.
     */
    public static int compare(int account, TLRPC.Dialog a, TLRPC.Dialog b) {
        return LocalPins.compare(account, a, b);
    }

    /** Hook DialogsAdapter.ItemInternal (ribind DiffUtil) e DialogCell.setDialog (drawPin). */
    public static boolean isLocalPinGlyph(int account, TLRPC.Dialog dialog) {
        return LocalPins.isLocalGlyph(account, dialog);
    }

    /** Hook DialogCell.onDraw: scelta del drawable del pin (glifo locale ambra vs pin server). */
    public static boolean isLocalPinGlyph(int account, long dialogId, int dialogsType) {
        return LocalPins.isLocalGlyph(account, dialogId, dialogsType);
    }
}

/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.fold;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.Cells.TextCheckCell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import it.iotatec.morsegram.MorsegramPrefs;

/**
 * Preferenze foldable (mission 6): {@link MorsegramPrefs#global()} {@code fold.twoPanes} / {@code fold.hingeSplit},
 * default true, con cache volatile perché {@link FoldLayout} le legge dentro launchLayout.onMeasure/onLayout
 * (hot path: mai SharedPreferences lì). Ogni set riallinea subito il layout tramite {@link FoldState}.
 * <p>
 * {@link #rows()} descrive la sezione 'Pieghevole' per {@code MorsegramSettingsActivity} (file di un altro agente,
 * NON toccato da qui): tipo riga, chiave di localizzazione del titolo/sottotitolo, stato e toggle.
 */
public final class FoldSettings {

    /** Tipi riga di {@link Row}: HeaderCell, TextCheckCell (titolo + sottotitolo multiline), TextInfoPrivacyCell. */
    public static final int ROW_HEADER = 0;
    public static final int ROW_CHECK = 1;
    public static final int ROW_INFO = 2;

    private static volatile Boolean twoPanes;
    private static volatile Boolean hingeSplit;

    private FoldSettings() {
    }

    /** Due pannelli (lista + chat) sullo schermo interno anche in portrait, se la finestra è larga ≥ 600dp. */
    public static boolean twoPanes() {
        Boolean v = twoPanes;
        if (v == null) {
            v = MorsegramPrefs.global().getBoolean(MorsegramPrefs.KEY_FOLD_TWO_PANES, true);
            twoPanes = v;
        }
        return v;
    }

    /** Divisore lista/chat allineato alla cerniera (FoldingFeature VERTICAL) invece della formula upstream 320dp/35%. */
    public static boolean hingeSplit() {
        Boolean v = hingeSplit;
        if (v == null) {
            v = MorsegramPrefs.global().getBoolean(MorsegramPrefs.KEY_FOLD_HINGE_SPLIT, true);
            hingeSplit = v;
        }
        return v;
    }

    public static void setTwoPanes(boolean on) {
        twoPanes = on;
        MorsegramPrefs.global().edit().putBoolean(MorsegramPrefs.KEY_FOLD_TWO_PANES, on).apply();
        FoldState.get().onPolicyChanged();
    }

    public static void setHingeSplit(boolean on) {
        hingeSplit = on;
        MorsegramPrefs.global().edit().putBoolean(MorsegramPrefs.KEY_FOLD_HINGE_SPLIT, on).apply();
        FoldState.get().onGeometryChanged();
    }

    /** @return il nuovo valore. */
    public static boolean toggleTwoPanes() {
        final boolean v = !twoPanes();
        setTwoPanes(v);
        return v;
    }

    /** @return il nuovo valore. */
    public static boolean toggleHingeSplit() {
        final boolean v = !hingeSplit();
        setHingeSplit(v);
        return v;
    }

    /** La sezione ha senso solo con una cerniera (sensore hinge o FoldingFeature vista) o su tablet (policy ≥ 600dp). */
    public static boolean relevant() {
        return AndroidUtilities.isFold() || FoldState.get().hasFoldingFeature() || AndroidUtilities.isTablet();
    }

    /**
     * Righe della sezione 'Pieghevole', nell'ordine di visualizzazione: header, due TextCheckCell, info.
     * Chiavi stringa (localization_*.bin, modulo app): MorsegramSettingsFoldable, MorsegramFoldTwoPanes(+Info),
     * MorsegramFoldHingeSplit(+Info), MorsegramFoldInfo.
     */
    public static List<Row> rows() {
        final ArrayList<Row> rows = new ArrayList<>(4);
        rows.add(new Row(ROW_HEADER, "MorsegramSettingsFoldable", null, null));
        rows.add(new Row(ROW_CHECK, "MorsegramFoldTwoPanes", "MorsegramFoldTwoPanesInfo", new Toggle() {
            @Override public boolean get() { return twoPanes(); }
            @Override public boolean toggle() { return toggleTwoPanes(); }
        }));
        rows.add(new Row(ROW_CHECK, "MorsegramFoldHingeSplit", "MorsegramFoldHingeSplitInfo", new Toggle() {
            @Override public boolean get() { return hingeSplit(); }
            @Override public boolean toggle() { return toggleHingeSplit(); }
        }));
        rows.add(new Row(ROW_INFO, "MorsegramFoldInfo", null, null));
        return Collections.unmodifiableList(rows);
    }

    /** Stato booleano di una riga check (niente java.util.function: minSdk 23). */
    public interface Toggle {
        boolean get();
        boolean toggle();
    }

    public static final class Row {
        /** {@link #ROW_HEADER}, {@link #ROW_CHECK} o {@link #ROW_INFO}. */
        public final int type;
        /** Chiave LocaleController del titolo (header/check) o del testo (info). */
        public final String key;
        /** Chiave LocaleController del sottotitolo (solo check), altrimenti null. */
        public final String subtitleKey;
        private final Toggle toggle;

        Row(int type, String key, String subtitleKey, Toggle toggle) {
            this.type = type;
            this.key = key;
            this.subtitleKey = subtitleKey;
            this.toggle = toggle;
        }

        public String title() {
            return LocaleController.getString(key);
        }

        public String subtitle() {
            return subtitleKey == null ? null : LocaleController.getString(subtitleKey);
        }

        public boolean checked() {
            return toggle != null && toggle.get();
        }

        /** Inverte la preferenza (il layout si aggiorna da solo) e restituisce il nuovo stato. */
        public boolean toggle() {
            return toggle != null && toggle.toggle();
        }

        /** Comodità per onBindViewHolder: titolo + sottotitolo multiline + switch. */
        public void bind(TextCheckCell cell, boolean divider) {
            if (cell == null || type != ROW_CHECK) {
                return;
            }
            cell.setTextAndValueAndCheck(title(), subtitle(), checked(), true, divider);
        }
    }
}

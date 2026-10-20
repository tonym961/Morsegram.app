/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.fold;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

/**
 * Policy di layout foldable (mission 6), pura e senza stato: chiamata dagli hook di una riga in
 * {@code LaunchActivity} (launchLayout.onMeasure/onLayout e checkLayout) e in
 * {@code AndroidUtilities.getTabletLeftFragmentSize}.
 * <ul>
 * <li>{@link #twoPanes(Activity)}: regola upstream (mai in multi-window; tablet non 'small' o landscape → due pannelli)
 *     estesa al portrait: finestra ≥ 600dp e preferenza {@code fold.twoPanes}. Sui dispositivi con cerniera la
 *     preferenza governa il portrait in entrambi i sensi (off → pannello singolo anche se upstream lo farebbe doppio).</li>
 * <li>{@link #leftPaneWidth(int, int, int)}: divisore sulla cerniera verticale quando nota e {@code fold.hingeSplit},
 *     altrimenti la formula upstream {@code insetLeft + max(320dp, 35%)}. Gestisce anche la sonda di
 *     {@code getMinTabletSide()} (lato lungo in portrait sui small tablet) perché le bolle non superino il pannello destro.</li>
 * </ul>
 */
public final class FoldLayout {

    /** Larghezza minima (dp) di ciascun pannello perché lo split sulla cerniera sia accettato. */
    private static final int MIN_PANE_DP = 280;

    /** hasSystemFeature è un'IPC sulle versioni vecchie: calcolato una volta, siamo in onMeasure. */
    private static volatile Boolean foldDevice;

    private FoldLayout() {
    }

    /** Hook LaunchActivity: sostituisce {@code !isInMultiwindow && (!isSmallTablet() || landscape)}. */
    public static boolean twoPanes(Activity activity) {
        if (AndroidUtilities.isInMultiwindow) {
            return false;
        }
        FoldState.get().ensureTracking(activity);
        return twoPanes(configuration(activity));
    }

    /**
     * Stessa policy senza Activity, per le celle che la ricopiano inline (es. ChatMessageCell.getGroupPhotosWidth:
     * {@code !isInMultiwindow && isTablet() && (!isSmallTablet() || landscape)}): false nel layout telefono.
     * Usa la Configuration del contesto applicazione (orientation/screenWidthDp coincidono con quelli della
     * LaunchActivity fuori dal multi-window, escluso qui).
     */
    public static boolean twoPanes() {
        if (AndroidUtilities.isInMultiwindow || !AndroidUtilities.isTablet()) {
            return false;
        }
        return twoPanes(configuration(null));
    }

    private static boolean twoPanes(Configuration c) {
        if (c != null && c.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return true;
        }
        final boolean upstream = !AndroidUtilities.isSmallTablet();
        final boolean wide = windowWidthDp(c) >= FoldState.MIN_TWO_PANES_DP;
        if (isFoldDevice()) {
            return FoldSettings.twoPanes() && wide;
        }
        return upstream || (FoldSettings.twoPanes() && wide);
    }

    /** true quando Morsegram (non upstream) decide i due pannelli: small tablet, portrait, finestra ≥ 600dp. */
    private static boolean portraitTwoPanesByMorsegram() {
        if (AndroidUtilities.isInMultiwindow || !AndroidUtilities.isSmallTablet()) {
            return false;
        }
        final Configuration c = configuration(null);
        if (c == null || c.orientation != Configuration.ORIENTATION_PORTRAIT) {
            return false;
        }
        return twoPanes(c);
    }

    /** Hook AndroidUtilities.getTabletLeftFragmentSize. */
    public static int leftPaneWidth(int fullWidth, int insetLeft, int insetRight) {
        final int window = AndroidUtilities.displaySize.x;
        if (insetLeft == 0 && insetRight == 0 && window > 0 && fullWidth > window + AndroidUtilities.dp(48) && portraitTwoPanesByMorsegram()) {
            // getMinTabletSide() sonda lo split in landscape (fullWidth = lato lungo) mentre mostriamo due pannelli in
            // portrait: riporta un "pannello sinistro" tale che maxSide - left = pannello destro reale di portrait.
            return fullWidth - (window - split(window, 0, 0));
        }
        return split(fullWidth, insetLeft, insetRight);
    }

    private static int split(int fullWidth, int insetLeft, int insetRight) {
        final int upstream = insetLeft + Math.max(AndroidUtilities.dp(320), (fullWidth - insetLeft - insetRight) * 35 / 100);
        if (!FoldSettings.hingeSplit()) {
            return upstream;
        }
        final int x = FoldState.get().verticalFoldX(fullWidth);
        if (x < 0) {
            return upstream;
        }
        final int minPane = AndroidUtilities.dp(MIN_PANE_DP);
        if (x - insetLeft < minPane || fullWidth - x - insetRight < minPane) {
            return upstream;
        }
        return x;
    }

    public static boolean isFoldDevice() {
        if (FoldState.get().hasFoldingFeature()) {
            return true;
        }
        Boolean v = foldDevice;
        if (v == null) {
            try {
                v = AndroidUtilities.isFold();
            } catch (Throwable ignore) {
                v = false;
            }
            foldDevice = v;
        }
        return v;
    }

    private static int windowWidthDp(Configuration c) {
        if (c != null && c.screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED && c.screenWidthDp > 0) {
            return c.screenWidthDp;
        }
        return Math.round(AndroidUtilities.displaySize.x / AndroidUtilities.density);
    }

    private static Configuration configuration(Context context) {
        try {
            final Context ctx = context != null ? context : ApplicationLoader.applicationContext;
            return ctx != null ? ctx.getResources().getConfiguration() : null;
        } catch (Throwable ignore) {
            return null;
        }
    }
}

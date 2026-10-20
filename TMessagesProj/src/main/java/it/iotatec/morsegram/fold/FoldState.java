/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.fold;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;

import androidx.core.content.ContextCompat;
import androidx.core.util.Consumer;
import androidx.window.java.layout.WindowInfoTrackerCallbackAdapter;
import androidx.window.layout.DisplayFeature;
import androidx.window.layout.FoldingFeature;
import androidx.window.layout.WindowInfoTracker;
import androidx.window.layout.WindowLayoutInfo;
import androidx.window.layout.WindowMetricsCalculator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.LaunchActivity;

/**
 * Stato della cerniera (mission 6): singleton che ascolta Jetpack WindowManager ({@code androidx.window:window-java},
 * {@link WindowInfoTrackerCallbackAdapter}) per la sola {@link LaunchActivity} e tiene l'ultima {@link FoldingFeature}
 * (bounds in coordinate finestra, orientamento, postura) più le dimensioni della finestra in px.
 * <p>
 * Registrazione: {@link #attach(Application)} da {@code MorsegramApp.onCreate} (ActivityLifecycleCallbacks → onActivityStarted).
 * Se attach non viene mai chiamata gli hook funzionano lo stesso: {@link FoldLayout#twoPanes(Activity)} chiama
 * {@link #ensureTracking(Activity)} la prima volta che il layout tablet viene misurato, e finché nessuna
 * FoldingFeature è nota la geometria resta quella upstream. Dispositivi/ROM senza window extensions → nessuna feature.
 * <p>
 * Reazioni: cerniera spostata/postura cambiata → requestLayout del launchLayout (via un figlio: l'override upstream
 * scarta requestLayout durante onMeasure) + rebind delle chat visibili (larghezze bolle da getMinTabletSide);
 * policy cambiata (toggle utente) → rientro pubblico {@code LaunchActivity.onConfigurationChanged(config)}, che
 * esegue la catena upstream resetTabletFlag → invalidateTabletMode → checkLayout. Il fold/unfold fisico non ha
 * bisogno di nulla qui: è un configuration change (screenSize|smallestScreenSize|screenLayout nel manifest).
 */
public final class FoldState implements Application.ActivityLifecycleCallbacks {

    /** Larghezza finestra minima (dp) per due pannelli in portrait sullo schermo interno. */
    public static final int MIN_TWO_PANES_DP = 600;

    private static final FoldState INSTANCE = new FoldState();
    private static volatile boolean attached;

    private Activity tracked;
    private Tracker tracker;
    private volatile Rect fold;
    private volatile boolean foldVertical;
    private volatile boolean foldHalfOpened;
    private volatile boolean foldSeparating;
    private volatile boolean seenFold;
    private volatile int windowWidthPx;
    private volatile int windowHeightPx;

    private FoldState() {
    }

    public static FoldState get() {
        return INSTANCE;
    }

    /** Da MorsegramApp.onCreate (idempotente). */
    public static void attach(Application app) {
        if (app == null || attached) {
            return;
        }
        attached = true;
        try {
            app.registerActivityLifecycleCallbacks(INSTANCE);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /* ---------- ActivityLifecycleCallbacks: solo LaunchActivity ---------- */

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
        if (activity instanceof LaunchActivity) {
            startTracking(activity);
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
        if (activity == tracked) {
            stopTracking();
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (activity == tracked) {
            stopTracking();
        }
    }

    /** Fallback senza attach(): chiamata (a costo di un confronto) da FoldLayout.twoPanes dentro onMeasure/onLayout. */
    void ensureTracking(Activity activity) {
        if (activity == null || activity == tracked || !(activity instanceof LaunchActivity)) {
            return;
        }
        attach(activity.getApplication());
        startTracking(activity);
    }

    private void startTracking(Activity activity) {
        if (activity == tracked) {
            return;
        }
        stopTracking();
        tracked = activity;
        try {
            tracker = new Tracker(activity);
        } catch (Throwable e) {
            tracker = null;
            FileLog.e(e);
        }
    }

    private void stopTracking() {
        if (tracker != null) {
            tracker.release();
            tracker = null;
        }
        tracked = null;
        // fold/window px restano: la prima WindowLayoutInfo dopo il restart li riallinea e verticalFoldX() scarta i dati
        // non coerenti con la larghezza corrente; azzerarli farebbe saltare il divisore a ogni rientro dal background.
    }

    /* ---------- aggiornamenti (main thread: executor = ContextCompat.getMainExecutor) ---------- */

    private void onWindowLayoutInfo(Activity activity, Rect window, Rect foldBounds, boolean vertical, boolean halfOpened, boolean separating) {
        if (activity != tracked) {
            return;
        }
        if (window != null) {
            windowWidthPx = window.width();
            windowHeightPx = window.height();
        }
        final Rect old = fold;
        final boolean oldVertical = foldVertical;
        final boolean oldHalfOpened = foldHalfOpened;
        fold = foldBounds;
        foldVertical = vertical;
        foldHalfOpened = halfOpened;
        foldSeparating = separating;
        if (foldBounds != null) {
            seenFold = true;
        }
        final boolean changed = (old == null ? foldBounds != null : !old.equals(foldBounds)) || oldVertical != vertical || oldHalfOpened != halfOpened;
        if (changed) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("morsegram fold: window " + windowWidthPx + "x" + windowHeightPx + " feature " + foldBounds + (foldBounds == null ? "" : (vertical ? " VERTICAL" : " HORIZONTAL") + (halfOpened ? " HALF_OPENED" : " FLAT") + (separating ? " separating" : "")));
            }
            onGeometryChanged();
        }
    }

    /**
     * Cerniera spostata o split on/off: rimisura il layout tablet e ribinda le chat visibili (larghezze bolle).
     * Nel layout telefono (schermo esterno) nulla dipende dalla cerniera: nessun requestLayout/rebind inutile
     * quando la FoldingFeature sparisce dopo la piega.
     */
    void onGeometryChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            final LaunchActivity activity = LaunchActivity.instance;
            if (activity == null || activity.isFinishing() || !AndroidUtilities.isTablet()) {
                return;
            }
            try {
                requestTabletRelayout(activity);
                rebindChat(activity.getRightActionBarLayout());
                rebindChat(activity.getActionBarLayout());
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** requestLayout del launchLayout tramite un figlio (l'override upstream scarta requestLayout durante onMeasure). */
    private static void requestTabletRelayout(LaunchActivity activity) {
        View v = null;
        final INavigationLayout right = activity.getRightActionBarLayout();
        if (right != null) {
            v = right.getView();
        }
        if (v == null || v.getParent() == null) {
            final INavigationLayout main = activity.getActionBarLayout();
            v = main != null ? main.getView() : null;
        }
        if (v != null) {
            v.requestLayout();
        }
    }

    private static void rebindChat(INavigationLayout layout) {
        if (layout == null) {
            return;
        }
        final BaseFragment fragment = layout.getLastFragment();
        if (!(fragment instanceof ChatActivity)) {
            return;
        }
        final RecyclerListView list = ((ChatActivity) fragment).getChatListView();
        if (list != null && list.getParent() != null && list.getAdapter() != null) {
            list.getAdapter().notifyDataSetChanged();
        }
    }

    /**
     * Policy due pannelli cambiata senza configuration change (toggle utente): rientro nella catena upstream
     * (resetTabletFlag → invalidateTabletMode no-op → checkLayout sposta la chat tra i pannelli). checkLayout non
     * chiede un layout quando nessuna visibilità cambia (nessuna chat aperta): lo chiediamo qui, così il pannello
     * lista passa subito da larghezza sinistra a piena (o viceversa).
     */
    void onPolicyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            final LaunchActivity activity = LaunchActivity.instance;
            if (activity == null || activity.isFinishing() || !AndroidUtilities.isTablet()) {
                return;
            }
            try {
                activity.onConfigurationChanged(activity.getResources().getConfiguration());
                requestTabletRelayout(activity);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /* ---------- letture ---------- */

    /**
     * x (px, coordinate finestra = launchLayout edge-to-edge) di una cerniera VERTICALE nota per una finestra larga
     * {@code fullWidth}; -1 se assente, orizzontale, fuori finestra o se l'informazione non è allineata alla larghezza
     * corrente (es. prima WindowLayoutInfo dopo una rotazione): allora vale la geometria upstream.
     */
    int verticalFoldX(int fullWidth) {
        final Rect f = fold;
        if (f == null || !foldVertical) {
            return -1;
        }
        final int w = windowWidthPx;
        if (w <= 0 || Math.abs(w - fullWidth) > AndroidUtilities.dp(56)) {
            return -1;
        }
        if (f.left <= 0 || f.left >= fullWidth) {
            return -1;
        }
        return f.left;
    }

    /** Bounds (copia) della FoldingFeature VERTICAL corrente, null se assente. */
    public static Rect verticalHingeBounds() {
        final Rect f = INSTANCE.fold;
        return f != null && INSTANCE.foldVertical ? new Rect(f) : null;
    }

    /** HALF_OPENED + HORIZONTAL (dispositivo a libro appoggiato): riservato alla v2 (padding sulla metà alta). */
    public static boolean isTabletop() {
        return INSTANCE.fold != null && INSTANCE.foldHalfOpened && !INSTANCE.foldVertical;
    }

    /** HALF_OPENED + VERTICAL. */
    public static boolean isBook() {
        return INSTANCE.fold != null && INSTANCE.foldHalfOpened && INSTANCE.foldVertical;
    }

    public static boolean isSeparating() {
        return INSTANCE.fold != null && INSTANCE.foldSeparating;
    }

    /** true se una FoldingFeature è mai stata riportata in questo processo. */
    public boolean hasFoldingFeature() {
        return seenFold;
    }

    public static int windowWidthPx() {
        final int w = INSTANCE.windowWidthPx;
        return w > 0 ? w : AndroidUtilities.displaySize.x;
    }

    public static int windowHeightPx() {
        final int h = INSTANCE.windowHeightPx;
        return h > 0 ? h : AndroidUtilities.displaySize.y;
    }

    /**
     * Larghezza della finestra in dp dalla Configuration (aggiornata PRIMA di onConfigurationChanged, quindi coerente
     * tra checkLayout e onMeasure, a differenza dei px di WindowMetrics che arrivano in modo asincrono).
     */
    public static int windowWidthDp(Context context) {
        Configuration c = null;
        try {
            final Context ctx = context != null ? context : ApplicationLoader.applicationContext;
            if (ctx != null) {
                c = ctx.getResources().getConfiguration();
            }
        } catch (Throwable ignore) {
        }
        if (c != null && c.screenWidthDp != Configuration.SCREEN_WIDTH_DP_UNDEFINED && c.screenWidthDp > 0) {
            return c.screenWidthDp;
        }
        return Math.round(AndroidUtilities.displaySize.x / AndroidUtilities.density);
    }

    /* ---------- glue androidx.window (unico punto che tocca la libreria) ---------- */

    private static final class Tracker {
        private final Activity activity;
        private final WindowInfoTrackerCallbackAdapter adapter;
        private final Consumer<WindowLayoutInfo> consumer;

        Tracker(Activity activity) {
            this.activity = activity;
            adapter = new WindowInfoTrackerCallbackAdapter(WindowInfoTracker.getOrCreate(activity));
            consumer = this::accept;
            adapter.addWindowLayoutInfoListener(activity, ContextCompat.getMainExecutor(activity), consumer);
        }

        private void accept(WindowLayoutInfo info) {
            try {
                Rect window = null;
                try {
                    window = WindowMetricsCalculator.getOrCreate().computeCurrentWindowMetrics(activity).getBounds();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                FoldingFeature feature = null;
                if (info != null) {
                    for (DisplayFeature d : info.getDisplayFeatures()) {
                        if (d instanceof FoldingFeature) {
                            feature = (FoldingFeature) d;
                            break;
                        }
                    }
                }
                INSTANCE.onWindowLayoutInfo(
                    activity,
                    window,
                    feature == null ? null : new Rect(feature.getBounds()),
                    feature != null && feature.getOrientation() == FoldingFeature.Orientation.VERTICAL,
                    feature != null && feature.getState() == FoldingFeature.State.HALF_OPENED,
                    feature != null && feature.isSeparating()
                );
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        void release() {
            try {
                adapter.removeWindowLayoutInfoListener(consumer);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }
}

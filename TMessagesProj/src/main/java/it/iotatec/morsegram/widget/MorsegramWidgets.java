/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.widget.RemoteViews;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatsWidgetProvider;
import org.telegram.messenger.ContactsWidgetProvider;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;

import it.iotatec.morsegram.theme.MorsegramPalette;

/**
 * Registro e parti comuni dei cinque widget Morsegram (modalità aereo, chat nascoste, non letti, vocale rapido,
 * chat singola). Tutti i provider vivono nella libreria ({@code it.iotatec.morsegram.widget}) perché devono
 * vedere {@code org.telegram.messenger.R} (layout e drawable stanno nel res della libreria), AirplaneMode,
 * MorsegramDialogs e MorsegramPalette; le voci {@code <receiver>} stanno invece nel manifest del MODULO APP,
 * come già AirplaneTileService/AirplaneReceiver: zero righe nel manifest di upstream.
 * <p>
 * BATTERIA: nessuno dei nostri {@code appwidget-provider} dichiara {@code updatePeriodMillis} (omesso = 0, mai
 * una sveglia). Gli aggiornamenti arrivano SOLO da eventi: il sistema a piazzamento/riavvio/ridimensionamento,
 * {@link it.iotatec.morsegram.AirplaneMode#set} per l'interruttore, {@link WidgetSync} (osservatori
 * NotificationCenter) per i dati, la fine della configurazione e il ritorno in primo piano dell'app.
 * <p>
 * RISERVATEZZA: nessun widget scrive nulla di una chat nascosta. La verifica non è "isHidden == false" ma
 * {@link #canReadDialogs(int)}: a processo freddo {@code HiddenChats.isHidden} restituirebbe false perché
 * UserConfig non è caricato (HiddenChats.java:117-129), quindi senza account attivato i widget con dati
 * mostrano il segnaposto e cancellano la cache.
 */
public final class MorsegramWidgets {

    public static final int TYPE_AIRPLANE = 1;
    public static final int TYPE_HIDDEN = 2;
    public static final int TYPE_UNREAD = 3;
    public static final int TYPE_VOICE = 4;
    public static final int TYPE_CHAT = 5;

    /** Azione del tocco sull'interruttore modalità aereo: broadcast al provider stesso (è un BroadcastReceiver). */
    static final String ACTION_TOGGLE_AIRPLANE = "it.iotatec.morsegram.widget.TOGGLE_AIRPLANE";

    private MorsegramWidgets() {
    }

    /* ======================================================================================= registro */

    static Class<?> providerClass(int type) {
        switch (type) {
            case TYPE_AIRPLANE:
                return AirplaneWidgetProvider.class;
            case TYPE_HIDDEN:
                return HiddenWidgetProvider.class;
            case TYPE_UNREAD:
                return UnreadWidgetProvider.class;
            case TYPE_VOICE:
                return VoiceWidgetProvider.class;
            case TYPE_CHAT:
                return ChatWidgetProvider.class;
            default:
                return null;
        }
    }

    static MorsegramWidgetProvider provider(int type) {
        switch (type) {
            case TYPE_AIRPLANE:
                return new AirplaneWidgetProvider();
            case TYPE_HIDDEN:
                return new HiddenWidgetProvider();
            case TYPE_UNREAD:
                return new UnreadWidgetProvider();
            case TYPE_VOICE:
                return new VoiceWidgetProvider();
            case TYPE_CHAT:
                return new ChatWidgetProvider();
            default:
                return null;
        }
    }

    /** Tipo dedotto dal provider a cui l'appWidgetId appartiene (la config activity è una per tutti). */
    public static int typeOf(Context context, int appWidgetId) {
        final int stored = WidgetConfig.type(appWidgetId);
        if (stored != 0) {
            return stored;
        }
        try {
            final AppWidgetManager manager = AppWidgetManager.getInstance(context);
            if (manager == null) {
                return 0;
            }
            final android.appwidget.AppWidgetProviderInfo info = manager.getAppWidgetInfo(appWidgetId);
            if (info == null || info.provider == null) {
                return 0;
            }
            final String name = info.provider.getClassName();
            for (int type = TYPE_AIRPLANE; type <= TYPE_CHAT; type++) {
                final Class<?> clazz = providerClass(type);
                if (clazz != null && clazz.getName().equals(name)) {
                    WidgetConfig.setType(appWidgetId, type);
                    return type;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return 0;
    }

    public static int[] ids(Context context, int type) {
        final Class<?> clazz = providerClass(type);
        if (context == null || clazz == null) {
            return new int[0];
        }
        try {
            final AppWidgetManager manager = AppWidgetManager.getInstance(context);
            if (manager == null) {
                return new int[0];
            }
            final int[] result = manager.getAppWidgetIds(new ComponentName(context, clazz));
            return result == null ? new int[0] : result;
        } catch (Throwable e) {
            FileLog.e(e);
            return new int[0];
        }
    }

    /* ==================================================================================== aggiornamento */

    /** Ridisegna i widget di un tipo. Thread qualunque: il disegno passa comunque dal thread UI. */
    public static void refresh(int type) {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final int[] ids = ids(context, type);
                if (ids.length == 0) {
                    return;
                }
                final MorsegramWidgetProvider provider = provider(type);
                final AppWidgetManager manager = AppWidgetManager.getInstance(context);
                if (provider == null || manager == null) {
                    return;
                }
                for (int id : ids) {
                    provider.push(context, manager, id);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** Widget che mostrano dati delle chat: ridisegnati da {@link WidgetSync} a ogni evento utile. */
    public static void refreshData() {
        refresh(TYPE_UNREAD);
        refresh(TYPE_VOICE);
        refresh(TYPE_CHAT);
    }

    public static void refreshAll() {
        for (int type = TYPE_AIRPLANE; type <= TYPE_CHAT; type++) {
            refresh(type);
        }
    }

    /** I due widget di UPSTREAM che mostrano dati delle chat (chat recenti e contatti). */
    private static final Class<?>[] UPSTREAM_PROVIDERS = {ChatsWidgetProvider.class, ContactsWidgetProvider.class};

    /**
     * Fa ri-interrogare i due widget di upstream. Serve perche' il filtro delle chat nascoste dentro
     * {@code MessagesStorage.getWidgetDialogs} vale solo quando la RemoteViewsFactory ri-interroga, e cio'
     * accade solo su {@code notifyAppWidgetViewDataChanged}: upstream la emette da
     * {@code MessagesStorage.updateWidgets} (privato, chiamato solo dai percorsi di messaggio) oppure
     * all'onUpdate/riavvio. Senza questa chiamata nascondere una chat lasciava nome, avatar, ultimo messaggio,
     * ora e contatore sulla schermata iniziale - leggibili senza sbloccare il telefono - fino al messaggio
     * successivo in una chat del widget. Zero righe upstream: {@code R.id.list_view} e' lo stesso id usato da
     * ChatsWidgetProvider:117 e ContactsWidgetProvider:119. Thread qualunque: il lavoro passa dal thread UI.
     */
    public static void refreshUpstream() {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            try {
                final AppWidgetManager manager = AppWidgetManager.getInstance(context);
                if (manager == null) {
                    return;
                }
                for (Class<?> clazz : UPSTREAM_PROVIDERS) {
                    final int[] ids = manager.getAppWidgetIds(new ComponentName(context, clazz));
                    if (ids == null) {
                        continue;
                    }
                    for (int id : ids) {
                        manager.notifyAppWidgetViewDataChanged(id, R.id.list_view);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    /** true se almeno un widget con dati è sulla schermata iniziale (gate di {@link WidgetSync}). */
    public static boolean anyDataWidget(Context context) {
        return ids(context, TYPE_UNREAD).length > 0 || ids(context, TYPE_CHAT).length > 0 || ids(context, TYPE_VOICE).length > 0;
    }

    private static volatile long dataWidgetCheckedAt;
    private static volatile boolean dataWidgetPresent;

    /**
     * Stessa risposta di {@link #anyDataWidget}, ma al massimo una volta ogni 15 s: è il gate di un osservatore
     * di {@code updateInterfaces}, che arriva a ogni "sta scrivendo" e a ogni cambio di stato online, e
     * {@code getAppWidgetIds} è una chiamata Binder.
     */
    static boolean anyDataWidgetCached(Context context) {
        final long now = android.os.SystemClock.elapsedRealtime();
        if (now - dataWidgetCheckedAt > 15_000L || dataWidgetCheckedAt == 0) {
            dataWidgetPresent = anyDataWidget(context);
            dataWidgetCheckedAt = now;
        }
        return dataWidgetPresent;
    }

    /** Il parco widget è cambiato (piazzamento, rimozione): la prossima lettura ricontrolla davvero. */
    static void invalidateWidgetCache() {
        dataWidgetCheckedAt = 0;
    }

    public static boolean anyWidget(Context context) {
        for (int type = TYPE_AIRPLANE; type <= TYPE_CHAT; type++) {
            if (ids(context, type).length > 0) {
                return true;
            }
        }
        return false;
    }

    /** MorsegramApp.onResume: riallinea tutto (tema automatico, stato aereo, dati) e aggancia gli osservatori. */
    public static void onAppResume() {
        final Context context = ApplicationLoader.applicationContext;
        if (context == null || !anyWidget(context)) {
            return;
        }
        WidgetSync.install();
        WidgetSync.recomputeNow();
        refreshAll();
    }

    /* =========================================================================================== tema */

    /**
     * true = disegna in scuro. {@link WidgetConfig#THEME_AUTO} segue il TEMA DELL'APP (che con il "notte
     * automatico" di Telegram segue a sua volta il sistema); se il tema non è ancora caricato ripiega sulla
     * configurazione di sistema, l'unica informazione disponibile a processo freddo.
     */
    static boolean isDark(int widgetId) {
        final int mode = WidgetConfig.theme(widgetId);
        if (mode == WidgetConfig.THEME_LIGHT) {
            return false;
        }
        if (mode == WidgetConfig.THEME_DARK) {
            return true;
        }
        try {
            if (Theme.getCurrentTheme() != null) {
                return Theme.isCurrentThemeDark();
            }
        } catch (Throwable ignore) {
        }
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (context != null) {
                final int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
                return night == Configuration.UI_MODE_NIGHT_YES;
            }
        } catch (Throwable ignore) {
        }
        return false;
    }

    static int backgroundRes(boolean dark, int corners) {
        if (dark) {
            switch (corners) {
                case WidgetConfig.CORNERS_SQUARE:
                    return R.drawable.morsegram_widget_bg_dark_square;
                case WidgetConfig.CORNERS_EXTRA:
                    return R.drawable.morsegram_widget_bg_dark_extra;
                default:
                    return R.drawable.morsegram_widget_bg_dark;
            }
        }
        switch (corners) {
            case WidgetConfig.CORNERS_SQUARE:
                return R.drawable.morsegram_widget_bg_light_square;
            case WidgetConfig.CORNERS_EXTRA:
                return R.drawable.morsegram_widget_bg_light_extra;
            default:
                return R.drawable.morsegram_widget_bg_light;
        }
    }

    static int titleColor(boolean dark) {
        return dark ? MorsegramPalette.DARK_TEXT : MorsegramPalette.INK;
    }

    static int textColor(boolean dark) {
        return dark ? MorsegramPalette.DARK_TEXT_2 : MorsegramPalette.INK_2;
    }

    /** L'unica ambra leggibile come TESTO sul fondo scelto (MorsegramPalette javadoc :22-27). */
    static int accentColor(boolean dark) {
        return dark ? MorsegramPalette.AMBER_LIGHT : MorsegramPalette.AMBER_INK;
    }

    /** Glifi e icone: ambra media su chiaro (3,23:1), ambra piena su scuro. */
    static int iconColor(boolean dark) {
        return dark ? MorsegramPalette.AMBER : MorsegramPalette.AMBER_MID;
    }

    /**
     * Sfondo + angoli + trasparenza + colori del testo. Lo sfondo è un {@code <shape>} dentro una ImageView che
     * riempie la radice: è l'unico modo per avere una trasparenza a scelta dell'utente, perché RemoteViews non
     * sa costruire drawable a runtime e {@code setImageAlpha} è @RemotableViewMethod su ImageView.
     */
    static void applyChrome(RemoteViews views, int widgetId, boolean dark) {
        final int opacity = WidgetConfig.opacity(widgetId);
        views.setImageViewResource(R.id.morsegram_widget_bg, backgroundRes(dark, WidgetConfig.corners(widgetId)));
        views.setInt(R.id.morsegram_widget_bg, "setImageAlpha", Math.round(opacity * 255f / 100f));
        views.setTextColor(R.id.morsegram_widget_title, titleColor(dark));
        views.setTextColor(R.id.morsegram_widget_subtitle, textColor(dark));
        views.setInt(R.id.morsegram_widget_icon, "setColorFilter", iconColor(dark));
    }

    /* ==================================================================================== intenti */

    /** Dati univoci per appWidgetId: gli extra NON contano nell'uguaglianza dei PendingIntent. */
    static Uri unique(int type, int widgetId, String tag) {
        return Uri.parse("morsegram://widget/" + type + "/" + widgetId + "/" + tag);
    }

    /** Tocco che NON apre l'app: broadcast al provider stesso, FLAG_IMMUTABLE (intento esplicito, Android 14 ok). */
    static PendingIntent broadcast(Context context, Class<?> target, String action, int type, int widgetId) {
        final Intent intent = new Intent(context, target)
                .setAction(action)
                .setData(unique(type, widgetId, "b"))
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static PendingIntent activity(Context context, Intent intent) {
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Apre l'app (lista chat), senza bersaglio: stesso schema di ChatsWidgetProvider.java:109-113. */
    static PendingIntent openApp(Context context, int type, int widgetId) {
        final Intent intent = new Intent(context, LaunchActivity.class)
                .setAction("com.tmessages.openchat" + Integer.MAX_VALUE)
                .setData(unique(type, widgetId, "a"))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        return activity(context, intent);
    }

    /** Apre una chat: gli extra chatId/userId/encId sono quelli che LaunchActivity.java:2880-2887 legge già. */
    static PendingIntent openDialog(Context context, int account, long dialogId, int type, int widgetId) {
        final Intent intent = new Intent(context, LaunchActivity.class)
                .setAction("com.tmessages.openchat" + Integer.MAX_VALUE)
                .setData(unique(type, widgetId, "d" + dialogId))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .putExtra("currentAccount", account);
        if (dialogId > 0) {
            intent.putExtra("userId", dialogId);
        } else {
            intent.putExtra("chatId", -dialogId);
        }
        return activity(context, intent);
    }

    /* ================================================================================== riservatezza */

    /**
     * true solo se il filtro delle chat nascoste è AFFIDABILE per questo slot. HiddenChats.isHidden legge
     * UserConfig.clientUserId e, se l'account non è ancora caricato, restituisce false (fallisce in apertura):
     * i widget con dati devono mostrare il segnaposto, non il contenuto.
     */
    static boolean canReadDialogs(int account) {
        try {
            ApplicationLoader.postInitApplication();
            return account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT
                    && UserConfig.getInstance(account).isClientActivated()
                    && UserConfig.getInstance(account).clientUserId != 0;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** Stringa Morsegram con ripiego inglese: il processo del widget può essere appena nato (AirplaneTileService.str). */
    static String str(String key, String fallback) {
        try {
            final String value = LocaleController.getString(key);
            return value == null || value.startsWith("LOC_ERR") ? fallback : value;
        } catch (Throwable ignore) {
            return fallback;
        }
    }

    /**
     * Come {@link #str}, per una stringa con segnaposto. Serve per la stessa ragione: a processo appena nato il
     * bundle di localizzazione non è ancora caricato e {@code LocaleController.formatString} restituisce
     * "LOC_ERR: <chiave>", che finirebbe disegnato sulla schermata iniziale al posto del testo.
     */
    static String format(String key, String fallback, Object... args) {
        try {
            final String value = LocaleController.formatString(key, 0, args);
            return value == null || value.startsWith("LOC_ERR") ? fallback : value;
        } catch (Throwable ignore) {
            return fallback;
        }
    }
}

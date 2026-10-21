/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;

/**
 * Modalità aereo in-app (MORSEGRAM.md mission 8): sospende la connettività di Morsegram senza toccare quella del telefono.
 * <p>
 * Agisce SOLO sull'oracolo di rete {@code ApplicationLoader.isNetworkOnline()} (hook di 1 riga): tgnet riceve
 * {@code setNetworkAvailable(false)} tramite {@link ConnectionsManager#checkConnection()}, la stessa chiamata che il
 * BroadcastReceiver CONNECTIVITY_ACTION di upstream fa quando cade la rete, e i socket vivi vengono sospesi con
 * {@link ConnectionsManager#discardConnection(int, int)}. Le richieste in coda (comprese le messages.readHistory delle
 * chat lette offline) restano in coda e partono al rientro, esattamente come dopo la modalità aereo di sistema:
 * nessuna richiesta viene cancellata o filtrata (niente ghost mode, Telegram API ToS 1.4). Le push FCM vengono
 * ignorate via {@code ApplicationLoader.consumePush} e rigenerate da getDifference al rientro.
 * <p>
 * Stato: {@link MorsegramPrefs#global()} {@code airplane.on} / {@code airplane.since}, con cache volatile perché
 * {@link #isOn()} viene chiamata dentro isNetworkOnline() su thread caldi (stageQueue, UI, FCM, cold start).
 */
public final class AirplaneMode {

    public static final String ACTION_OFF = "it.iotatec.morsegram.AIRPLANE_OFF";
    public static final String CHANNEL_ID = "morsegram_airplane";
    /** 'MORS': fuori dagli id usati da NotificationsController (account + 1, ecc.). */
    public static final int NOTIFICATION_ID = 0x4D4F5253;

    /** DC 1..5 + INT_MAX (DC "corrente" per discardConnection, cfr. ConnectionsManager.cpp reconnect/getDatacenterWithId). */
    private static final int[] DC_IDS = {1, 2, 3, 4, 5, Integer.MAX_VALUE};
    /* Tipi di connessione non esposti dal Java upstream (jni/tgnet/Defines.h enum ConnectionType). */
    private static final int CONNECTION_TYPE_TEMP = 16;
    private static final int CONNECTION_TYPE_PROXY = 32;
    private static final int CONNECTION_TYPE_GENERIC_MEDIA = 64;
    /* Pool size (Defines.h UPLOAD_CONNECTIONS_COUNT / PROXY_CONNECTIONS_COUNT; Download è esposto da ConnectionsManager). */
    private static final int UPLOAD_CONNECTIONS_COUNT = 4;
    private static final int PROXY_CONNECTIONS_COUNT = 4;

    /** -1 = non ancora letto dalle prefs, 0 = off, 1 = on. */
    private static volatile int cached = -1;
    /** id drawable del modulo app (morsegram_airplane) passato da MorsegramApp.onCreate; 0 = fallback R.drawable.notification. */
    private static volatile int iconRes;

    private AirplaneMode() {
    }

    public static void setIconRes(int res) {
        iconRes = res;
    }

    public static int getIconRes() {
        return iconRes != 0 ? iconRes : R.drawable.notification;
    }

    /** Lettura calda: una volatile. Mai eccezioni, false finché il context non esiste (cold start). */
    public static boolean isOn() {
        int c = cached;
        if (c < 0) {
            if (ApplicationLoader.applicationContext == null) {
                return false;
            }
            synchronized (AirplaneMode.class) {
                c = cached;
                if (c < 0) {
                    try {
                        c = MorsegramPrefs.global().getBoolean(MorsegramPrefs.KEY_AIRPLANE_ON, false) ? 1 : 0;
                    } catch (Throwable ignore) {
                        return false;
                    }
                    cached = c;
                }
            }
        }
        return c == 1;
    }

    /** epoch ms dell'attivazione, 0 se spenta. */
    public static long since() {
        return isOn() ? MorsegramPrefs.global().getLong(MorsegramPrefs.KEY_AIRPLANE_SINCE, 0) : 0;
    }

    public static void toggle() {
        set(!isOn());
    }

    /**
     * Accende/spegne la modalità (thread UI, idempotente). Può girare a processo freddo (tile QS, receiver):
     * {@code postInitApplication()} è obbligatorio prima di toccare ConnectionsManager.
     *
     * @return false se rifiutata (chiamata VoIP in corso).
     */
    public static boolean set(boolean on) {
        ApplicationLoader.postInitApplication();
        if (on && VoIPService.getSharedInstance() != null) {
            toast(LocaleController.getString("MorsegramAirplaneCallActive"));
            return false;
        }
        final boolean changed = on != isOn();
        if (changed) {
            synchronized (AirplaneMode.class) {
                cached = on ? 1 : 0;
                MorsegramPrefs.global().edit()
                        .putBoolean(MorsegramPrefs.KEY_AIRPLANE_ON, on)
                        .putLong(MorsegramPrefs.KEY_AIRPLANE_SINCE, on ? System.currentTimeMillis() : 0)
                        .apply();
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("morsegram airplane mode " + (on ? "ON" : "OFF"));
            }
            // Stesso loop del receiver CONNECTIVITY_ACTION upstream (ApplicationLoader.onCreate): per ogni slot account
            // checkConnection() -> native_setNetworkAvailable(a, isNetworkOnline(), ...) che ora legge il nostro flag.
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                final ConnectionsManager cm = ConnectionsManager.getInstance(a);
                cm.checkConnection();
                if (on) {
                    // setNetworkAvailable(false) non chiude i socket già aperti: sweep con discardConnection (-> native reconnect
                    // -> suspendConnection). Passa da stageQueue + doppio scheduleTask nativo, quindi arriva sempre DOPO
                    // networkAvailable=false e Connection::connect() rifiuta la riconnessione. Nessuna richiesta viene cancellata.
                    suspendConnections(cm);
                } else if (cm.getPauseTime() != 0) {
                    // app in background (es. toggle dalla tile): sveglia la rete come farebbe una push, così le letture in
                    // sospeso partono senza aspettare il prossimo FCM. Niente getDifference manuale: arriva da onSessionCreated.
                    cm.resumeNetworkMaybe();
                }
            }
        }
        syncIndicator();
        requestTileUpdate();
        it.iotatec.morsegram.widget.MorsegramWidgets.refresh(it.iotatec.morsegram.widget.MorsegramWidgets.TYPE_AIRPLANE); // widget interruttore allineato con tile e notifica, da qualunque punto arrivi il toggle
        return true;
    }

    private static void suspendConnections(ConnectionsManager cm) {
        for (int dc : DC_IDS) {
            cm.discardConnection(dc, ConnectionsManager.ConnectionTypeGeneric);
            cm.discardConnection(dc, CONNECTION_TYPE_GENERIC_MEDIA);
            cm.discardConnection(dc, ConnectionsManager.ConnectionTypePush);
            cm.discardConnection(dc, CONNECTION_TYPE_TEMP);
            for (int i = 0; i < ConnectionsManager.DownloadConnectionsCount; i++) {
                cm.discardConnection(dc, ConnectionsManager.ConnectionTypeDownload | (i << 16));
            }
            for (int i = 0; i < UPLOAD_CONNECTIONS_COUNT; i++) {
                cm.discardConnection(dc, ConnectionsManager.ConnectionTypeUpload | (i << 16));
            }
            for (int i = 0; i < PROXY_CONNECTIONS_COUNT; i++) {
                cm.discardConnection(dc, CONNECTION_TYPE_PROXY | (i << 16));
            }
        }
    }

    /** Tap sul titolo 'Modalità aereo' (LaunchActivity.updateCurrentConnectionState): conferma, poi set(false). */
    public static void askTurnOff(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        try {
            new AlertDialog.Builder(activity)
                    .setTitle(LocaleController.getString("MorsegramAirplaneMode"))
                    .setMessage(LocaleController.getString("MorsegramAirplaneTurnOffQuestion"))
                    .setPositiveButton(LocaleController.getString("MorsegramAirplaneTurnOff"), (dialog, which) -> set(false))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .show();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** LaunchActivity.onResume (via ApplicationLoaderImpl.onResume -> MorsegramApp.onResume): riallinea notifica e tile. */
    public static void onAppResume() {
        syncIndicator();
        requestTileUpdate();
    }

    /** Sottotesto per la voce di menu / tile: 'Disattivata' oppure 'Attiva dalle 14:32'. */
    public static CharSequence statusSubtext() {
        if (!isOn()) {
            return LocaleController.getString("MorsegramAirplaneModeOff");
        }
        final long since = since();
        if (since <= 0) {
            return LocaleController.getString("MorsegramAirplaneModeOn");
        }
        return LocaleController.formatString("MorsegramAirplaneModeOnSince", 0, LocaleController.stringForMessageListDate(since / 1000));
    }

    /**
     * Idempotente: posta (on) o cancella (off) la notifica persistente silenziosa 'Modalità aereo attiva' con l'azione
     * 'Disattiva' ({@link AirplaneReceiver}). Su Android 14+ l'utente può scartarla: lo stato non cambia e viene ripostata
     * al prossimo set(true)/onResume/updateCurrentConnectionState. Se POST_NOTIFICATIONS è negato non appare.
     */
    public static void syncIndicator() {
        final Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return;
        }
        try {
            final NotificationManagerCompat nm = NotificationManagerCompat.from(ctx);
            if (!isOn()) {
                nm.cancel(NOTIFICATION_ID);
                return;
            }
            ensureChannel(ctx);
            final int piFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            final Intent off = new Intent(ctx, AirplaneReceiver.class).setAction(ACTION_OFF);
            final PendingIntent offIntent = PendingIntent.getBroadcast(ctx, 0, off, piFlags);
            final NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(getIconRes())
                    .setContentTitle(LocaleController.getString("MorsegramAirplaneNotification"))
                    .setContentText(LocaleController.getString("MorsegramAirplaneNotificationText"))
                    .setOngoing(true)
                    .setSilent(true)
                    .setOnlyAlertOnce(true)
                    .setShowWhen(false)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .addAction(0, LocaleController.getString("MorsegramAirplaneTurnOff"), offIntent);
            final Intent open = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (open != null) {
                builder.setContentIntent(PendingIntent.getActivity(ctx, 0, open, piFlags));
            }
            nm.notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        // Canale proprio: OTHER_NOTIFICATIONS_CHANNEL di NotificationsController ha importanza DEFAULT e id rotante.
        final NotificationChannel channel = new NotificationChannel(CHANNEL_ID, LocaleController.getString("MorsegramAirplaneMode"), NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.enableLights(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    /** Tile passiva: chiede al sistema un onStartListening così lo stato della tile segue i cambi in-app. */
    public static void requestTileUpdate() {
        final Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null || Build.VERSION.SDK_INT < 24) {
            return;
        }
        try {
            TileService.requestListeningState(ctx, new ComponentName(ctx, AirplaneTileService.class));
        } catch (Throwable ignore) {
        }
    }

    /** 'Aggiungi tile' (API 33+): StatusBarManager.requestAddTileService, solo con app in foreground. */
    public static boolean canRequestAddTile() {
        return Build.VERSION.SDK_INT >= 33;
    }

    public static void requestAddTile(Context context) {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        final Context ctx = context != null ? context : ApplicationLoader.applicationContext;
        if (ctx == null) {
            return;
        }
        try {
            final StatusBarManager sbm = ctx.getSystemService(StatusBarManager.class);
            if (sbm == null) {
                return;
            }
            MorsegramPrefs.global().edit().putBoolean(MorsegramPrefs.KEY_AIRPLANE_TILE_PROMPTED, true).apply();
            sbm.requestAddTileService(
                    new ComponentName(ctx, AirplaneTileService.class),
                    LocaleController.getString("MorsegramAirplaneMode"),
                    Icon.createWithResource(ctx, getIconRes()),
                    ctx.getMainExecutor(),
                    result -> {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("morsegram requestAddTileService result " + result);
                        }
                    });
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void toast(CharSequence text) {
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignore) {
            }
        });
    }
}

package it.iotatec.morsegram.update;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.os.Build;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import it.iotatec.morsegram.MorsegramPrefs;

/**
 * Aggiornamento in-app della sola build sideload.
 *
 * Un'app pubblicata su Google Play non puo' aggiornarsi da se' (policy Device and Network Abuse):
 * isSupported() e' vero solo per la variante standalone (applicationId con suffisso ".web") e
 * tutta la UI relativa sparisce altrove.
 *
 * Sorgente: una chat Telegram scelta dall'utente, per impostazione predefinita i Messaggi salvati.
 * Si cerca il documento .apk piu' recente il cui nome contiene una versione (Morsegram-1.2.3-....apk)
 * e, se e' piu' nuova di quella installata, si scarica con il FileLoader dell'app.
 *
 * Prima di installare, in quest'ordine e tutte obbligatorie: sha256 (se dichiarato nella didascalia),
 * certificato di firma identico a quello dell'app in esecuzione, stesso nome pacchetto, versionCode
 * strettamente maggiore. Se una sola fallisce il file viene cancellato e non si installa niente.
 */
public final class Updater {

    private Updater() {
    }

    public static final int STATE_IDLE = 0;
    public static final int STATE_CHECKING = 1;
    public static final int STATE_AVAILABLE = 2;
    public static final int STATE_DOWNLOADING = 3;
    public static final int STATE_READY = 4;
    public static final int STATE_UP_TO_DATE = 5;
    public static final int STATE_ERROR = 6;

    private static final String KEY_SOURCE = "update.source";
    private static final String KEY_AUTO = "update.auto";
    private static final String KEY_LAST_CHECK = "update.lastCheck";
    private static final String DEFAULT_SOURCE = "me";
    private static final long CHECK_INTERVAL = 24 * 60 * 60 * 1000L;
    private static final String INSTALL_ACTION = "it.morsegram.app.INSTALL_RESULT";

    public interface Listener {
        void onUpdaterState(int state, Found found, String error);
    }

    /** Aggiornamento trovato nella chat sorgente. */
    public static final class Found {
        public final TLRPC.Document document;
        public final String version;
        public final String sha256;
        public final String notes;
        public final long size;
        public final int account;

        Found(int account, TLRPC.Document document, String version, String sha256, String notes) {
            this.account = account;
            this.document = document;
            this.version = version;
            this.sha256 = sha256;
            this.notes = notes;
            this.size = document != null ? document.size : 0;
        }
    }

    private static volatile int state = STATE_IDLE;
    private static volatile Found found;
    private static volatile String lastError;
    private static volatile int progress;
    private static Listener listener;
    private static boolean downloadObserved;
    private static boolean installReceiverRegistered;

    /**
     * Vero solo nella build sideload, compilata con -PMORSEGRAM_SIDELOAD: la variante pubblicata
     * su Google Play non puo' aggiornarsi da se' (policy Device and Network Abuse), quindi li'
     * l'updater e tutta la sua UI non esistono.
     */
    public static boolean isSupported() {
        return it.iotatec.morsegram.MorsegramEdition.sideloadOnly() && ApplicationLoader.applicationContext != null;
    }

    public static int state() {
        return state;
    }

    public static Found available() {
        return state == STATE_AVAILABLE || state == STATE_DOWNLOADING || state == STATE_READY ? found : null;
    }

    public static String lastError() {
        return lastError;
    }

    public static int progress() {
        return progress;
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    public static String source() {
        return MorsegramPrefs.global().getString(KEY_SOURCE, DEFAULT_SOURCE);
    }

    public static void setSource(String value) {
        String v = value == null ? "" : value.trim();
        v = v.replace("https://t.me/", "").replace("@", "").trim();
        MorsegramPrefs.global().edit().putString(KEY_SOURCE, v.isEmpty() ? DEFAULT_SOURCE : v).apply();
        found = null;
        setState(STATE_IDLE, null);
    }

    public static boolean autoCheckEnabled() {
        return MorsegramPrefs.global().getBoolean(KEY_AUTO, true);
    }

    public static void setAutoCheckEnabled(boolean on) {
        MorsegramPrefs.global().edit().putBoolean(KEY_AUTO, on).apply();
    }

    public static long lastCheck() {
        return MorsegramPrefs.global().getLong(KEY_LAST_CHECK, 0);
    }

    public static String currentVersion() {
        return BuildConfig.MORSEGRAM_VERSION;
    }

    /** Controllo automatico all'avvio: al massimo una volta al giorno e mai su rete a consumo. */
    public static void maybeCheckOnResume() {
        if (!isSupported() || !autoCheckEnabled()) {
            return;
        }
        if (System.currentTimeMillis() - lastCheck() < CHECK_INTERVAL) {
            return;
        }
        if (isMetered()) {
            return;
        }
        checkNow(false);
    }

    private static boolean isMetered() {
        try {
            final ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered();
        } catch (Throwable ignore) {
            return false;
        }
    }

    private interface PeerCallback {
        void run(TLRPC.InputPeer peer);
    }

    /** Cerca un aggiornamento nella chat sorgente. */
    public static void checkNow(boolean userRequested) {
        if (!isSupported() || state == STATE_CHECKING || state == STATE_DOWNLOADING) {
            return;
        }
        final int account = UserConfig.selectedAccount;
        if (!UserConfig.getInstance(account).isClientActivated()) {
            setState(STATE_ERROR, "no_account");
            return;
        }
        setState(STATE_CHECKING, null);
        MorsegramPrefs.global().edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply();
        resolveSource(account, peer -> {
            if (peer == null) {
                setState(STATE_ERROR, "no_source");
                return;
            }
            final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
            req.peer = peer;
            req.limit = 40;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!(response instanceof TLRPC.messages_Messages)) {
                            setState(STATE_ERROR, error != null ? error.text : "no_history");
                            return;
                        }
                        onHistory(account, (TLRPC.messages_Messages) response);
                    }));
        });
    }

    private static void resolveSource(int account, PeerCallback callback) {
        final String src = source();
        if (src.isEmpty() || "me".equalsIgnoreCase(src)) {
            callback.run(new TLRPC.TL_inputPeerSelf());
            return;
        }
        final TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = src;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> {
                    if (!(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                        callback.run(null);
                        return;
                    }
                    final TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                    MessagesController.getInstance(account).putUsers(res.users, false);
                    MessagesController.getInstance(account).putChats(res.chats, false);
                    long peerId = 0;
                    if (res.peer != null) {
                        if (res.peer.channel_id != 0) {
                            peerId = -res.peer.channel_id;
                        } else if (res.peer.chat_id != 0) {
                            peerId = -res.peer.chat_id;
                        } else {
                            peerId = res.peer.user_id;
                        }
                    }
                    callback.run(peerId == 0 ? null : MessagesController.getInstance(account).getInputPeer(peerId));
                }));
    }

    private static void onHistory(int account, TLRPC.messages_Messages messages) {
        Found best = null;
        for (int i = 0; i < messages.messages.size(); i++) {
            final TLRPC.Message m = messages.messages.get(i);
            final TLRPC.Document doc = m.media != null ? m.media.document : null;
            if (doc == null) {
                continue;
            }
            final String name = FileLoader.getDocumentFileName(doc);
            if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                continue;
            }
            final String version = versionIn(name);
            if (version == null || compareVersions(version, currentVersion()) <= 0) {
                continue;
            }
            if (best == null || compareVersions(version, best.version) > 0) {
                best = new Found(account, doc, version, sha256In(m.message), notesIn(m.message));
            }
        }
        if (best == null) {
            found = null;
            setState(STATE_UP_TO_DATE, null);
        } else {
            found = best;
            setState(STATE_AVAILABLE, null);
        }
    }

    /** Estrae una versione tipo 1.2.3 dal nome del file. */
    static String versionIn(String name) {
        final Matcher m = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(name);
        return m.find() ? m.group(1) : null;
    }

    private static String sha256In(String caption) {
        if (caption == null) {
            return null;
        }
        final Matcher m = Pattern.compile("(?i)sha256\\s*[:=]\\s*([0-9a-fA-F]{64})").matcher(caption);
        return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private static String notesIn(String caption) {
        if (caption == null) {
            return null;
        }
        final String trimmed = caption.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Confronto semantico grezzo: 0.2.0 > 0.1.9 > 0.1. */
    static int compareVersions(String a, String b) {
        final String[] pa = a.split("\\.");
        final String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            final int va = i < pa.length ? parse(pa[i]) : 0;
            final int vb = i < pb.length ? parse(pb[i]) : 0;
            if (va != vb) {
                return va < vb ? -1 : 1;
            }
        }
        return 0;
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Scarica l'APK trovato e, superate le verifiche, apre l'installazione di sistema. */
    public static void downloadAndInstall(Activity activity) {
        final Found f = found;
        if (!isSupported() || f == null || f.document == null) {
            return;
        }
        final File ready = FileLoader.getInstance(f.account).getPathToAttach(f.document, true);
        if (ready != null && ready.exists() && ready.length() == f.document.size) {
            verifyAndInstall(activity, f, ready);
            return;
        }
        setState(STATE_DOWNLOADING, null);
        progress = 0;
        observeDownload(activity, f);
        FileLoader.getInstance(f.account).loadFile(f.document, "morsegram_update",
                FileLoader.PRIORITY_HIGH, 1);
    }

    private static void observeDownload(final Activity activity, final Found f) {
        if (downloadObserved) {
            return;
        }
        downloadObserved = true;
        final String path = FileLoader.getAttachFileName(f.document);
        final NotificationCenter center = NotificationCenter.getInstance(f.account);
        final NotificationCenter.NotificationCenterDelegate delegate = new NotificationCenter.NotificationCenterDelegate() {
            @Override
            public void didReceivedNotification(int id, int acc, Object... args) {
                if (args.length == 0 || !(args[0] instanceof String) || !path.equals(args[0])) {
                    return;
                }
                if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
                    center.removeObserver(this, NotificationCenter.fileLoaded);
                    center.removeObserver(this, NotificationCenter.fileLoadFailed);
                    center.removeObserver(this, NotificationCenter.fileLoadProgressChanged);
                    downloadObserved = false;
                    if (id == NotificationCenter.fileLoaded) {
                        verifyAndInstall(activity, f, FileLoader.getInstance(f.account).getPathToAttach(f.document, true));
                    } else {
                        setState(STATE_ERROR, "download_failed");
                    }
                } else if (id == NotificationCenter.fileLoadProgressChanged && args.length >= 3
                        && args[1] instanceof Long && args[2] instanceof Long) {
                    final long done = (Long) args[1];
                    final long total = (Long) args[2];
                    progress = total > 0 ? (int) (done * 100 / total) : 0;
                    notifyListener();
                }
            }
        };
        center.addObserver(delegate, NotificationCenter.fileLoaded);
        center.addObserver(delegate, NotificationCenter.fileLoadFailed);
        center.addObserver(delegate, NotificationCenter.fileLoadProgressChanged);
    }

    private static void verifyAndInstall(final Activity activity, final Found f, final File file) {
        Utilities.globalQueue.postRunnable(() -> {
            final String problem = verify(f, file);
            AndroidUtilities.runOnUIThread(() -> {
                if (problem != null) {
                    if (file != null) {
                        file.delete();
                    }
                    found = null;
                    setState(STATE_ERROR, problem);
                    return;
                }
                setState(STATE_READY, null);
                install(activity, file);
            });
        });
    }

    /** Ritorna null se il file e' installabile, altrimenti la chiave dell'errore. */
    private static String verify(Found f, File file) {
        if (file == null || !file.exists() || file.length() == 0) {
            return "missing_file";
        }
        try {
            if (f.sha256 != null && !f.sha256.equals(sha256(file))) {
                return "bad_hash";
            }
            final Context ctx = ApplicationLoader.applicationContext;
            final PackageManager pm = ctx.getPackageManager();
            final int flags = Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
            final PackageInfo candidate = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
            if (candidate == null) {
                return "not_an_apk";
            }
            if (!ctx.getPackageName().equals(candidate.packageName)) {
                return "other_package";
            }
            if (versionCode(candidate) <= versionCode(pm.getPackageInfo(ctx.getPackageName(), 0))) {
                return "not_newer";
            }
            if (!sameSigner(pm, ctx.getPackageName(), candidate)) {
                return "bad_signature";
            }
            return null;
        } catch (Throwable e) {
            FileLog.e(e);
            return "verify_failed";
        }
    }

    private static long versionCode(PackageInfo info) {
        if (info == null) {
            return -1;
        }
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    /** Il certificato dell'APK scaricato deve essere identico a quello dell'app in esecuzione. */
    private static boolean sameSigner(PackageManager pm, String packageName, PackageInfo candidate) throws Exception {
        Signature[] mine;
        Signature[] theirs;
        if (Build.VERSION.SDK_INT >= 28) {
            final PackageInfo installed = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            if (installed.signingInfo == null || candidate.signingInfo == null) {
                return false;
            }
            mine = installed.signingInfo.hasMultipleSigners()
                    ? installed.signingInfo.getApkContentsSigners()
                    : installed.signingInfo.getSigningCertificateHistory();
            theirs = candidate.signingInfo.hasMultipleSigners()
                    ? candidate.signingInfo.getApkContentsSigners()
                    : candidate.signingInfo.getSigningCertificateHistory();
        } else {
            mine = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures;
            theirs = candidate.signatures;
        }
        if (mine == null || theirs == null || mine.length == 0 || theirs.length == 0) {
            return false;
        }
        for (Signature a : mine) {
            for (Signature b : theirs) {
                if (Arrays.equals(a.toByteArray(), b.toByteArray())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String sha256(File file) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            final byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) > 0) {
                digest.update(buf, 0, read);
            }
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignore) {
                }
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    /** Consegna l'APK verificato all'installer di sistema: e' l'utente a confermare. */
    private static void install(Activity activity, File file) {
        final Context ctx = activity != null ? activity : ApplicationLoader.applicationContext;
        PackageInstaller.Session session = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            final PackageInstaller installer = ctx.getPackageManager().getPackageInstaller();
            final PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(ctx.getPackageName());
            final int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);
            in = new FileInputStream(file);
            out = session.openWrite("morsegram", 0, file.length());
            final byte[] buf = new byte[256 * 1024];
            int read;
            while ((read = in.read(buf)) > 0) {
                out.write(buf, 0, read);
            }
            session.fsync(out);
            out.close();
            out = null;
            in.close();
            in = null;
            registerInstallReceiver(ctx.getApplicationContext());
            final int piFlags = PendingIntent.FLAG_UPDATE_CURRENT
                    | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            final Intent intent = new Intent(INSTALL_ACTION).setPackage(ctx.getPackageName());
            final PendingIntent pending = PendingIntent.getBroadcast(ctx, sessionId, intent, piFlags);
            session.commit(pending.getIntentSender());
        } catch (Throwable e) {
            FileLog.e(e);
            setState(STATE_ERROR, "install_failed");
        } finally {
            closeQuietly(out);
            closeQuietly(in);
            if (session != null) {
                session.close();
            }
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignore) {
            }
        }
    }

    private static void registerInstallReceiver(Context ctx) {
        if (installReceiverRegistered) {
            return;
        }
        installReceiverRegistered = true;
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    final Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            context.startActivity(confirm);
                        } catch (Throwable e) {
                            FileLog.e(e);
                            setState(STATE_ERROR, "install_failed");
                        }
                    }
                } else if (status != PackageInstaller.STATUS_SUCCESS) {
                    setState(STATE_ERROR, "install_failed");
                }
            }
        };
        final IntentFilter filter = new IntentFilter(INSTALL_ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }
    }

    private static void setState(int newState, String error) {
        state = newState;
        lastError = error;
        notifyListener();
    }

    private static void notifyListener() {
        final Listener l = listener;
        if (l != null) {
            AndroidUtilities.runOnUIThread(() -> l.onUpdaterState(state, found, lastError));
        }
    }
}

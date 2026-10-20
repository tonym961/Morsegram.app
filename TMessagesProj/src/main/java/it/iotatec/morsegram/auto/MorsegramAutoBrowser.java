/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.auto;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.MediaDescription;
import android.media.browse.MediaBrowser;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.collection.LongSparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramMediaSession;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;


/**
 * Estensione dell'albero media-browser upstream (MusicBrowserService -> TelegramMediaSession) con i VOCALI: dal
 * head unit il guidatore apre una chat qualsiasi e ascolta i messaggi vocali. Percorso PRODUZIONE (media app), non
 * il modulo labs: nessuna dipendenza da androidx.car.app, tutto dentro il modulo libreria perche' i due hook
 * upstream (TelegramMediaSession) devono poter chiamare questa classe.
 *
 * <p>Albero: sotto la radice upstream ({@code __ROOT__}, oggi solo le chat con FILE MUSICALI) aggiungiamo
 * <b>Non letti</b> (chat con vocali non ascoltati, primo nodo) e <b>Vocali</b> (chat con vocali, piu' recenti
 * prima). I figli di una chat sono i suoi vocali (piu' recenti prima) piu' un "Riproduci tutti". La riproduzione
 * passa da {@link MediaController#playMessage(MessageObject)}, che chiama
 * {@code MessagesController.markMessageContentAsRead}: la ricevuta "ascoltato" parte esattamente come dal telefono
 * (richiesto dai termini API Telegram). I file non ancora scaricati li prende FileLoader (dentro playMessage) e la
 * riga mostra il marcatore di download come segnaposto.
 *
 * <p>Chat nascoste ({@link MorsegramAutoCommon#allowed}) e chat segrete non compaiono MAI, ne' in browse ne' in
 * riproduzione (ricontrollate anche sul media id, che arriva dall'host). Il filtro vale anche per le righe
 * <b>upstream</b> della radice e per i loro id di riproduzione ({@link #upstreamAllowed}): {@code
 * TelegramMediaSession} costruisce la sua lista di chat da SQL scartando solo le chat segrete, quindi senza quel
 * filtro una chat nascosta con un file musicale finirebbe sull'head unit con titolo e avatar veri.
 *
 * <p>Thread: {@code onLoadChildren} fa {@code result.detach()}, quindi nessuna delle nostre fasi blocca il binder:
 * SQL sulla storageQueue, decodifica avatar sulla globalQueue, consegna e chiamate a MediaController sul thread UI.
 */
public final class MorsegramAutoBrowser {

    /** {@code TelegramMediaSession.MEDIA_ID_ROOT} (privato upstream). */
    private static final String ROOT = "__ROOT__";
    /** {@code TelegramMediaSession.MEDIA_ID_CHAT_PREFIX} (privato upstream): prefisso delle righe di chat loro. */
    private static final String UP_CHAT = "__CHAT_";

    /**
     * Prefisso dei nostri media id. NIENTE '_': {@code TelegramMediaSession.onPlayFromMediaId} fa
     * {@code mediaId.split("_")} e accetta solo lunghezza 2, e {@code getDialogIdFromMediaId} guarda
     * {@code "__CHAT_"}: i nostri id non collidono con quelli upstream in nessuna delle due direzioni.
     */
    private static final String P = "mgv:";
    private static final String ID_VOICES = P + "voices";
    private static final String ID_UNREAD = P + "unread";
    private static final String ID_CHAT = P + "chat:";    // + dialogId: tutti i vocali della chat
    private static final String ID_UCHAT = P + "uchat:";  // + dialogId: solo i non ascoltati
    private static final String ID_PLAY = P + "play:";    // + dialogId + ':' + messageId
    private static final String ID_ALL = P + "all:";      // + dialogId: coda di tutti i vocali
    private static final String ID_ALLU = P + "allu:";    // + dialogId: coda dei soli non ascoltati

    private static final int MAX_CHATS = 50;
    private static final int MAX_VOICES = 30;
    /** Righe lette dal cursore: il doppio delle voci mostrate, perche' i video tondi vengono scartati dopo. */
    private static final int SCAN = MAX_VOICES * 2;
    /** Avatar decodificati per lista (oltre, nessuna icona: il costo non vale la riga). */
    private static final int MAX_AVATARS = 16;
    private static final long INDEX_TTL = 60_000L;
    private static final int PREFETCH = 2;

    private MorsegramAutoBrowser() {
    }

    /* ======================================================================= hook 1: albero browse */

    /**
     * Hook {@code TelegramMediaSession.loadBrowseChildren}, prima riga. true = richiesta gestita qui, upstream esce.
     *
     * <ul>
     * <li>media id nostro: i figli li costruiamo noi, upstream non li conosce (e non deve nemmeno aspettare la
     *     scansione delle chat con file musicali);</li>
     * <li>radice: rientriamo nello stesso metodo con la callback avvolta, cosi' upstream costruisce le SUE righe
     *     (chat con file musicali) e {@link RootNodes} ci mette davanti i nostri nodi. Il secondo giro porta una
     *     callback {@link Ours} e passa liscio: nessuna ricorsione, e lo stesso vale quando upstream ri-chiama
     *     {@code loadBrowseChildren} dai {@code pendingBrowseRequests} a {@code chatsLoaded} true.</li>
     * </ul>
     */
    public static boolean intercept(TelegramMediaSession holder, String parentMediaId, TelegramMediaSession.BrowseChildrenCallback callback) {
        if (holder == null || callback == null) {
            return false;
        }
        if (parentMediaId != null && parentMediaId.startsWith(P)) {
            ourChildren(holder, parentMediaId, callback);
            return true;
        }
        if (parentMediaId != null && parentMediaId.startsWith(UP_CHAT)
                && !allowed(holder.getCurrentAccount(), dialogId(parentMediaId, UP_CHAT))) {
            // l'host puo' ripresentare un id di chat upstream che gli abbiamo dato prima che la chat fosse
            // nascosta (cronologia, "riprendi"): da una chat nascosta non escono righe
            AndroidUtilities.runOnUIThread(() -> callback.onResult(new ArrayList<>()));
            return true;
        }
        if (ROOT.equals(parentMediaId) && !(callback instanceof Ours)) {
            holder.loadBrowseChildren(parentMediaId, new RootNodes(holder, callback));
            return true;
        }
        return false;
    }

    /** Marcatore anti-ricorsione per {@link #intercept}. */
    private interface Ours extends TelegramMediaSession.BrowseChildrenCallback {
    }

    private static final class RootNodes implements Ours {
        private final TelegramMediaSession holder;
        private final TelegramMediaSession.BrowseChildrenCallback delegate;

        RootNodes(TelegramMediaSession holder, TelegramMediaSession.BrowseChildrenCallback delegate) {
            this.holder = holder;
            this.delegate = delegate;
        }

        @Override
        public void onResult(List<MediaBrowser.MediaItem> items) {
            final ArrayList<MediaBrowser.MediaItem> upstream = items == null ? new ArrayList<>() : new ArrayList<>(items);
            final int account = holder.getCurrentAccount();
            index(account, idx -> Utilities.globalQueue.postRunnable(() -> {
                final ArrayList<MediaBrowser.MediaItem> out = new ArrayList<>(upstream.size() + 2);
                try {
                    if (idx != null && idx.account == account && idx.unreadChats > 0) {
                        out.add(node(ID_UNREAD, string("MorsegramCarUnlistened"),
                                idx.unreadChats + " " + string("MorsegramCarChatsCount"), nodeIcon(0xFFF0A23C)));
                    }
                    if (idx != null && idx.account == account && !idx.rows.isEmpty()) {
                        out.add(node(ID_VOICES, string("MorsegramCarVoices"),
                                idx.rows.size() + " " + string("MorsegramCarChatsCount"), nodeIcon(0xFF4FA3E3)));
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                    out.clear();
                }
                // le righe upstream (chat con file musicali) NON passano da MorsegramDialogs: TelegramMediaSession
                // le costruisce da SQL scartando solo le chat segrete, quindi una chat nascosta con un brano
                // comparirebbe qui con titolo e avatar veri. Il filtro vale anche per i nodi nostri, piu' sotto.
                for (int a = 0; a < upstream.size(); a++) {
                    if (upstreamAllowed(account, upstream.get(a))) {
                        out.add(upstream.get(a));
                    }
                }
                AndroidUtilities.runOnUIThread(() -> delegate.onResult(out));
            }));
        }
    }

    /**
     * Una riga upstream della radice puo' comparire sull'head unit? Le righe di chat hanno media id
     * {@code "__CHAT_" + dialogId} ({@code TelegramMediaSession.MEDIA_ID_CHAT_PREFIX}, privato upstream); tutto il
     * resto (nodi di servizio) passa liscio. Id di chat illeggibile = riga scartata: meglio una riga in meno che
     * una chat nascosta a schermo.
     */
    private static boolean upstreamAllowed(int account, MediaBrowser.MediaItem item) {
        try {
            if (item == null) {
                return false;
            }
            final String id = item.getMediaId();
            if (id == null || !id.startsWith(UP_CHAT)) {
                return true;
            }
            return allowed(account, Long.parseLong(id.substring(UP_CHAT.length())));
        } catch (Throwable e) {
            return false;
        }
    }

    private static void ourChildren(TelegramMediaSession holder, String id, TelegramMediaSession.BrowseChildrenCallback callback) {
        final int account = holder.getCurrentAccount();
        if (ID_VOICES.equals(id)) {
            chatList(account, false, callback);
        } else if (ID_UNREAD.equals(id)) {
            chatList(account, true, callback);
        } else if (id.startsWith(ID_UCHAT)) {
            voiceList(account, dialogId(id, ID_UCHAT), true, callback);
        } else if (id.startsWith(ID_CHAT)) {
            voiceList(account, dialogId(id, ID_CHAT), false, callback);
        } else {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(new ArrayList<>()));
        }
    }

    /* ==================================================================== hook 2: riproduzione */

    /**
     * Hook {@code TelegramMediaSession.SessionCallback.onPlayFromMediaId}, prima riga (copre anche
     * {@code onPrepareFromMediaId} e {@code onPlayFromSearch}, che vi rientrano). true = media id consumato:
     * upstream non deve interpretarlo.
     *
     * <p>Due casi: un id nostro ({@code mgv:}) lo suoniamo noi; un id <b>upstream</b>
     * ({@code "<dialogId>_<indice>"}, un brano) lo lasciamo a upstream <i>tranne</i> quando la chat e' nascosta —
     * allora lo consumiamo e non suona niente. Questo e' l'unico punto di ingresso della riproduzione, quindi
     * copre anche la scheda Musica del modulo labs, che chiama {@code playFromMediaId(dialogId + "_" + index)}.</p>
     */
    public static boolean playFromMediaId(TelegramMediaSession holder, String mediaId) {
        if (mediaId == null) {
            return false;
        }
        final int account = holder != null ? holder.getCurrentAccount() : UserConfig.selectedAccount;
        if (!mediaId.startsWith(P)) {
            try {
                final String[] parts = mediaId.split("_");
                if (parts.length == 2 && !allowed(account, Long.parseLong(parts[0]))) {
                    return true;   // chat nascosta: id consumato, nessun audio
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
        try {
            if (mediaId.startsWith(ID_PLAY)) {
                final String rest = mediaId.substring(ID_PLAY.length());
                final int sep = rest.lastIndexOf(':');
                if (sep > 0) {
                    playOne(holder, account, Long.parseLong(rest.substring(0, sep)), Integer.parseInt(rest.substring(sep + 1)));
                }
            } else if (mediaId.startsWith(ID_ALLU)) {
                playAll(holder, account, dialogId(mediaId, ID_ALLU), true);
            } else if (mediaId.startsWith(ID_ALL)) {
                playAll(holder, account, dialogId(mediaId, ID_ALL), false);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return true;
    }

    private static long dialogId(String mediaId, String prefix) {
        try {
            return Long.parseLong(mediaId.substring(prefix.length()));
        } catch (Throwable e) {
            return 0;
        }
    }

    /** Una chat puo' arrivare solo dal nostro albero, ma il media id lo manda l'host: ricontrolliamo sempre. */
    private static boolean allowed(int account, long dialogId) {
        return MorsegramAutoCommon.allowed(account, dialogId);   // filtro unico, condiviso col modulo labs
    }

    private static void playOne(TelegramMediaSession holder, int account, long dialogId, int messageId) {
        if (!allowed(account, dialogId)) {
            return;
        }
        final MessageObject cached = findCached(account, dialogId, messageId);
        if (cached != null) {
            start(holder, account, cached, null);
            return;
        }
        load(account, dialogId, false, voices -> {
            remember(account, dialogId, voices.messages);
            for (int a = 0; a < voices.messages.size(); a++) {
                final MessageObject object = voices.messages.get(a);
                if (object.getId() == messageId) {
                    start(holder, account, object, null);
                    return;
                }
            }
        });
    }

    private static void playAll(TelegramMediaSession holder, int account, long dialogId, boolean unreadOnly) {
        if (!allowed(account, dialogId)) {
            return;
        }
        load(account, dialogId, unreadOnly, voices -> {
            remember(account, dialogId, voices.messages);
            if (voices.messages.isEmpty()) {
                return;
            }
            // la coda va in ordine cronologico, dal piu' vecchio: il guidatore li sente come sono arrivati
            final ArrayList<MessageObject> queue = new ArrayList<>(voices.messages);
            Collections.reverse(queue);
            start(holder, account, queue.get(0), queue);
            prefetch(account, queue);
        });
    }

    private static void start(TelegramMediaSession holder, int account, MessageObject object, ArrayList<MessageObject> queue) {
        AndroidUtilities.runOnUIThread(() -> {
            // playMessage -> markMessageContentAsRead: la ricevuta "ascoltato" parte come dal telefono
            final boolean played = MediaController.getInstance().playMessage(object);
            if (played && queue != null && queue.size() > 1) {
                // dopo playMessage: cleanupPlayer() dentro playMessage azzera la coda vocali
                MediaController.getInstance().setVoiceMessagesPlaylist(queue, false);
            }
            if (played) {
                MorsegramAutoPlayback.attach(holder, account, object);
            }
        });
    }

    private static void prefetch(int account, ArrayList<MessageObject> queue) {
        for (int a = 1, n = Math.min(queue.size(), 1 + PREFETCH); a < n; a++) {
            final MessageObject object = queue.get(a);
            if (object.getDocument() == null || cached(account, object)) {
                continue;
            }
            FileLoader.getInstance(account).loadFile(object.getDocument(), object, FileLoader.PRIORITY_LOW, 0);
        }
    }

    /* ======================================================================== liste (figli) */

    private static void chatList(int account, boolean unreadOnly, TelegramMediaSession.BrowseChildrenCallback callback) {
        index(account, idx -> Utilities.globalQueue.postRunnable(() -> {
            final List<MediaBrowser.MediaItem> items = new ArrayList<>();
            try {
                int avatars = 0;
                for (int a = 0; idx != null && idx.account == account && a < idx.rows.size(); a++) {
                    final Row row = idx.rows.get(a);
                    if (unreadOnly && row.unread <= 0) {
                        continue;
                    }
                    final String subtitle = unreadOnly
                            ? row.unread + " " + string("MorsegramCarNewCount")
                            : row.count + " · " + LocaleController.stringForMessageListDate(row.lastDate);
                    Bitmap icon = null;
                    if (row.avatar != null && avatars < MAX_AVATARS) {
                        icon = avatar(account, row.avatar);
                        if (icon != null) {
                            avatars++;
                        }
                    }
                    items.add(node((unreadOnly ? ID_UCHAT : ID_CHAT) + row.did, row.title, subtitle, icon));
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            AndroidUtilities.runOnUIThread(() -> callback.onResult(items));
        }));
    }

    private static void voiceList(int account, long dialogId, boolean unreadOnly, TelegramMediaSession.BrowseChildrenCallback callback) {
        if (!allowed(account, dialogId)) {
            AndroidUtilities.runOnUIThread(() -> callback.onResult(new ArrayList<>()));
            return;
        }
        load(account, dialogId, unreadOnly, voices -> {
            remember(account, dialogId, voices.messages);
            Utilities.globalQueue.postRunnable(() -> {
                final List<MediaBrowser.MediaItem> items = new ArrayList<>();
                try {
                    if (voices.messages.size() > 1) {
                        int total = 0;
                        for (int a = 0; a < voices.messages.size(); a++) {
                            total += (int) voices.messages.get(a).getDuration();
                        }
                        items.add(playable((unreadOnly ? ID_ALLU : ID_ALL) + dialogId,
                                string("MorsegramCarPlayAll"),
                                voices.messages.size() + " · " + clock(total)));
                    }
                    for (int a = 0; a < voices.messages.size(); a++) {
                        final MessageObject object = voices.messages.get(a);
                        final boolean unheard = !object.isOut() && object.isContentUnread();
                        final StringBuilder title = new StringBuilder();
                        if (unheard && !unreadOnly) {
                            title.append("• ");
                        }
                        title.append(voices.senders.get(a));
                        title.append(" · ");
                        title.append(LocaleController.stringForMessageListDate(object.messageOwner.date));
                        String subtitle = clock((int) object.getDuration());
                        if (!cached(account, object)) {
                            // segnaposto: il file arriva con FileLoader quando il guidatore lo seleziona
                            subtitle = subtitle + " · " + string("MorsegramCarNotCached");
                        }
                        items.add(playable(ID_PLAY + dialogId + ":" + object.getId(), title.toString(), subtitle));
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                AndroidUtilities.runOnUIThread(() -> callback.onResult(items));
            });
        });
    }

    /* ============================================================ indice delle chat con vocali */

    private static final class Row {
        long did;
        int count;
        int unread;
        int lastDate;
        String title;
        TLRPC.FileLocation avatar;
    }

    private static final class Index {
        final int account;
        final long builtAt = SystemClock.elapsedRealtime();
        final ArrayList<Row> rows = new ArrayList<>();
        int unreadChats;

        Index(int account) {
            this.account = account;
        }
    }

    private interface IndexCallback {
        void onIndex(Index index);
    }

    private static volatile Index cachedIndex;
    private static final ArrayList<IndexCallback> indexWaiting = new ArrayList<>();
    private static boolean indexBuilding;

    private static void index(final int account, final IndexCallback callback) {
        final Index cached = cachedIndex;
        if (cached != null && cached.account == account && SystemClock.elapsedRealtime() - cached.builtAt < INDEX_TTL) {
            callback.onIndex(cached);
            return;
        }
        synchronized (MorsegramAutoBrowser.class) {
            indexWaiting.add(callback);
            if (indexBuilding) {
                return;
            }
            indexBuilding = true;
        }
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> {
            final Index built = buildIndex(account);
            final ArrayList<IndexCallback> callbacks;
            synchronized (MorsegramAutoBrowser.class) {
                cachedIndex = built;
                indexBuilding = false;
                callbacks = new ArrayList<>(indexWaiting);
                indexWaiting.clear();
            }
            for (int a = 0; a < callbacks.size(); a++) {
                callbacks.get(a).onIndex(built);
            }
        });
    }

    /**
     * Una sola aggregazione: chat con vocali, quanti sono, l'ultimo e quanti non ascoltati. Lo stato di lettura sta
     * in {@code messages_v2.read_state} (bit 2 = contenuto letto), non nel blob di media_v4, quindi serve la join
     * sulla chiave primaria (mid, uid). {@code IFNULL(..., 2)} = riga di messages_v2 assente -> contato come letto.
     */
    private static Index buildIndex(int account) {
        final Index index = new Index(account);
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        final ArrayList<Row> rows = new ArrayList<>();
        try {
            SQLiteCursor cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                    "SELECT m.uid, COUNT(*), MAX(m.date), SUM(CASE WHEN s.out = 0 AND (IFNULL(s.read_state, 2) & 2) = 0 THEN 1 ELSE 0 END)" +
                            " FROM media_v4 m LEFT JOIN messages_v2 s ON s.mid = m.mid AND s.uid = m.uid" +
                            " WHERE m.type = %d AND m.mid > 0 AND m.uid != 0 GROUP BY m.uid", MediaDataController.MEDIA_AUDIO));
            while (cursor.next()) {
                final long did = cursor.longValue(0);
                if (!allowed(account, did)) {
                    continue;
                }
                final Row row = new Row();
                row.did = did;
                row.count = cursor.intValue(1);
                row.lastDate = cursor.intValue(2);
                row.unread = cursor.intValue(3);
                rows.add(row);
            }
            cursor.dispose();

            Collections.sort(rows, (a, b) -> Integer.compare(b.lastDate, a.lastDate));
            while (rows.size() > MAX_CHATS) {
                rows.remove(rows.size() - 1);
            }

            final ArrayList<Long> usersToLoad = new ArrayList<>();
            final ArrayList<Long> chatsToLoad = new ArrayList<>();
            for (int a = 0; a < rows.size(); a++) {
                final long did = rows.get(a).did;
                if (DialogObject.isUserDialog(did)) {
                    usersToLoad.add(did);
                } else {
                    chatsToLoad.add(-did);
                }
            }
            final LongSparseArray<TLRPC.User> users = new LongSparseArray<>();
            final LongSparseArray<TLRPC.Chat> chats = new LongSparseArray<>();
            if (!usersToLoad.isEmpty()) {
                final ArrayList<TLRPC.User> loaded = new ArrayList<>();
                storage.getUsersInternal(usersToLoad, loaded);
                for (int a = 0; a < loaded.size(); a++) {
                    users.put(loaded.get(a).id, loaded.get(a));
                }
            }
            if (!chatsToLoad.isEmpty()) {
                final ArrayList<TLRPC.Chat> loaded = new ArrayList<>();
                storage.getChatsInternal(TextUtils.join(",", chatsToLoad), loaded);
                for (int a = 0; a < loaded.size(); a++) {
                    chats.put(loaded.get(a).id, loaded.get(a));
                }
            }
            for (int a = rows.size() - 1; a >= 0; a--) {
                final Row row = rows.get(a);
                if (DialogObject.isUserDialog(row.did)) {
                    final TLRPC.User user = users.get(row.did);
                    if (user != null) {
                        row.title = ContactsController.formatName(user.first_name, user.last_name);
                        if (user.photo != null && !(user.photo.photo_small instanceof TLRPC.TL_fileLocationUnavailable)) {
                            row.avatar = user.photo.photo_small;
                        }
                    }
                } else {
                    final TLRPC.Chat chat = chats.get(-row.did);
                    if (chat != null) {
                        row.title = chat.title;
                        if (chat.photo != null && !(chat.photo.photo_small instanceof TLRPC.TL_fileLocationUnavailable)) {
                            row.avatar = chat.photo.photo_small;
                        }
                    }
                }
                if (TextUtils.isEmpty(row.title)) {
                    rows.remove(a); // peer non risolvibile: nessuna riga "DELETED" sul head unit
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        index.rows.addAll(rows);
        for (int a = 0; a < rows.size(); a++) {
            if (rows.get(a).unread > 0) {
                index.unreadChats++;
            }
        }
        return index;
    }

    /* =========================================================== vocali di una singola chat */

    private static final class Voices {
        final ArrayList<MessageObject> messages = new ArrayList<>();
        final ArrayList<String> senders = new ArrayList<>();
    }

    private interface VoicesCallback {
        void onVoices(Voices voices);
    }

    private static void load(int account, long dialogId, boolean unreadOnly, VoicesCallback callback) {
        MessagesStorage.getInstance(account).getStorageQueue().postRunnable(() -> callback.onVoices(loadSync(account, dialogId, unreadOnly)));
    }

    private static Voices loadSync(int account, long dialogId, boolean unreadOnly) {
        final Voices voices = new Voices();
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        try {
            final ArrayList<TLRPC.Message> messages = new ArrayList<>();
            // il filtro "non ascoltato" sta in SQL (join sulla chiave primaria di messages_v2): cosi' le righe
            // deserializzate restano poche anche in una chat con migliaia di vocali
            SQLiteCursor cursor = storage.getDatabase().queryFinalized(String.format(Locale.US,
                    "SELECT m.data, m.mid, IFNULL(s.read_state, -1) FROM media_v4 m" +
                            " LEFT JOIN messages_v2 s ON s.mid = m.mid AND s.uid = m.uid" +
                            " WHERE m.uid = %d AND m.type = %d AND m.mid > 0 %s ORDER BY m.date DESC, m.mid DESC LIMIT %d",
                    dialogId, MediaDataController.MEDIA_AUDIO,
                    unreadOnly ? "AND s.out = 0 AND (IFNULL(s.read_state, 2) & 2) = 0" : "",
                    SCAN));
            while (cursor.next()) {
                final NativeByteBuffer data = cursor.byteBufferValue(0);
                if (data == null) {
                    continue;
                }
                final TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                if (message != null) {
                    message.readAttachPath(data, UserConfig.getInstance(account).clientUserId);
                }
                data.reuse();
                if (message == null || !MessageObject.isVoiceMessage(message)) {
                    continue; // i video tondi stanno nello stesso MEDIA_AUDIO: fuori, qui si ascolta
                }
                message.id = cursor.intValue(1);
                message.dialog_id = dialogId;
                final int readState = cursor.intValue(2);
                if (readState >= 0) {
                    message.media_unread = (readState & 2) == 0; // verita' su read_state, non sul blob
                }
                messages.add(message);
                if (messages.size() >= MAX_VOICES) {
                    break;
                }
            }
            cursor.dispose();

            final ArrayList<Long> usersToLoad = new ArrayList<>();
            final ArrayList<Long> chatsToLoad = new ArrayList<>();
            for (int a = 0; a < messages.size(); a++) {
                final long from = fromId(messages.get(a));
                if (from > 0 && !usersToLoad.contains(from)) {
                    usersToLoad.add(from);
                } else if (from < 0 && !chatsToLoad.contains(-from)) {
                    chatsToLoad.add(-from);
                }
            }
            final LongSparseArray<TLRPC.User> users = new LongSparseArray<>();
            final LongSparseArray<TLRPC.Chat> chats = new LongSparseArray<>();
            if (!usersToLoad.isEmpty()) {
                final ArrayList<TLRPC.User> loaded = new ArrayList<>();
                storage.getUsersInternal(usersToLoad, loaded);
                for (int a = 0; a < loaded.size(); a++) {
                    users.put(loaded.get(a).id, loaded.get(a));
                }
            }
            if (!chatsToLoad.isEmpty()) {
                final ArrayList<TLRPC.Chat> loaded = new ArrayList<>();
                storage.getChatsInternal(TextUtils.join(",", chatsToLoad), loaded);
                for (int a = 0; a < loaded.size(); a++) {
                    chats.put(loaded.get(a).id, loaded.get(a));
                }
            }

            for (int a = 0; a < messages.size(); a++) {
                final TLRPC.Message message = messages.get(a);
                final MessageObject object = new MessageObject(account, message, false, true);
                if (object.isVoiceOnce()) {
                    continue; // vocale usa-e-getta: non si brucia dal head unit
                }
                if (unreadOnly && (object.isOut() || !object.isContentUnread())) {
                    continue;
                }
                voices.messages.add(object);
                voices.senders.add(sender(object, users, chats));
                if (voices.messages.size() >= MAX_VOICES) {
                    break;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return voices;
    }

    /** &gt; 0 utente, &lt; 0 chat/canale, 0 sconosciuto (stessa convenzione dei dialogId). */
    private static long fromId(TLRPC.Message message) {
        try {
            return MessageObject.getFromChatId(message);
        } catch (Throwable e) {
            return 0;
        }
    }

    private static String sender(MessageObject object, LongSparseArray<TLRPC.User> users, LongSparseArray<TLRPC.Chat> chats) {
        if (object.isOut()) {
            return LocaleController.getString(R.string.FromYou);
        }
        final long from = fromId(object.messageOwner);
        if (from > 0) {
            final TLRPC.User user = users.get(from);
            if (user != null) {
                final String name = ContactsController.formatName(user.first_name, user.last_name);
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
            }
        } else if (from < 0) {
            final TLRPC.Chat chat = chats.get(-from);
            if (chat != null && !TextUtils.isEmpty(chat.title)) {
                return chat.title;
            }
        }
        return string("MorsegramCarVoices");
    }

    /* ============================================================= cache per la riproduzione */

    private static final LongSparseArray<ArrayList<MessageObject>> known = new LongSparseArray<>();
    private static int knownAccount = -1;

    private static void remember(int account, long dialogId, ArrayList<MessageObject> messages) {
        synchronized (known) {
            if (knownAccount != account) {
                known.clear();
                knownAccount = account;
            }
            if (!messages.isEmpty()) {
                known.put(dialogId, new ArrayList<>(messages));
            }
        }
    }

    private static MessageObject findCached(int account, long dialogId, int messageId) {
        synchronized (known) {
            if (knownAccount != account) {
                return null;
            }
            final ArrayList<MessageObject> messages = known.get(dialogId);
            for (int a = 0; messages != null && a < messages.size(); a++) {
                if (messages.get(a).getId() == messageId) {
                    return messages.get(a);
                }
            }
        }
        return null;
    }

    /** Invalidazione su cambio account: chiamato da {@link MorsegramAutoPlayback}. */
    static void invalidate() {
        cachedIndex = null;
        synchronized (known) {
            known.clear();
            knownAccount = -1;
        }
    }

    /* ================================================================================ utility */

    static boolean cached(int account, MessageObject object) {
        try {
            if (!TextUtils.isEmpty(object.messageOwner.attachPath)) {
                final File attach = new File(object.messageOwner.attachPath);
                if (attach.exists() && attach.length() > 0) {
                    return true;
                }
            }
            // useFileDatabaseQueue=false: siamo su storageQueue/globalQueue, niente attese incrociate
            final File file = FileLoader.getInstance(account).getPathToMessage(object.messageOwner, false);
            return file != null && file.exists() && file.length() > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    static String clock(int seconds) {
        return MorsegramAutoCommon.clock(seconds);   // formato unico, condiviso col modulo labs
    }

    private static String string(String key) {
        final String value = LocaleController.getString(key);
        return value == null || value.startsWith("LOC_ERR") ? key : value;
    }

    private static MediaBrowser.MediaItem node(String mediaId, String title, String subtitle, Bitmap icon) {
        final MediaDescription.Builder builder = new MediaDescription.Builder()
                .setMediaId(mediaId)
                .setTitle(title)
                .setSubtitle(subtitle);
        if (icon != null) {
            builder.setIconBitmap(icon);
        }
        return new MediaBrowser.MediaItem(builder.build(), MediaBrowser.MediaItem.FLAG_BROWSABLE);
    }

    private static MediaBrowser.MediaItem playable(String mediaId, String title, String subtitle) {
        final MediaDescription.Builder builder = new MediaDescription.Builder()
                .setMediaId(mediaId)
                .setTitle(title)
                .setSubtitle(subtitle);
        return new MediaBrowser.MediaItem(builder.build(), MediaBrowser.MediaItem.FLAG_PLAYABLE);
    }

    /* ==================================================================================== icone */

    /** l'host scala l'icona a dimensione tile: oltre questo e' banda Binder sprecata. */
    private static final int AVATAR_PX = 128;

    private static final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF iconRect = new RectF();
    private static final LongSparseArray<Bitmap> nodeIcons = new LongSparseArray<>();
    private static Bitmap glyph;
    private static boolean glyphTried;

    /**
     * Tile del nodo: cerchio pieno col glifo "vocale" bianco al centro. Niente {@code setIconUri}: l'host dovrebbe
     * leggere la risorsa dal nostro APK e i vector drawable non si decodificano come bitmap (upstream punta a
     * {@code drawable/contact_blue}, che in questo albero non esiste piu').
     */
    private static Bitmap nodeIcon(int color) {
        synchronized (nodeIcons) {
            Bitmap cached = nodeIcons.get(color);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
            try {
                final int size = 160;
                final Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.TRANSPARENT);
                final Canvas canvas = new Canvas(bitmap);
                iconPaint.setShader(null);
                iconPaint.setColorFilter(null);
                iconPaint.setColor(color);
                canvas.drawCircle(size / 2f, size / 2f, size / 2f, iconPaint);
                if (!glyphTried) {
                    glyphTried = true;
                    glyph = BitmapFactory.decodeResource(ApplicationLoader.applicationContext.getResources(), R.drawable.msg_filled_data_voice);
                }
                if (glyph != null && !glyph.isRecycled()) {
                    iconPaint.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
                    final int inset = size / 4;
                    iconRect.set(inset, inset, size - inset, size - inset);
                    canvas.drawBitmap(glyph, new Rect(0, 0, glyph.getWidth(), glyph.getHeight()), iconRect, iconPaint);
                    iconPaint.setColorFilter(null);
                }
                nodeIcons.put(color, bitmap);
                return bitmap;
            } catch (Throwable e) {
                FileLog.e(e);
                return null;
            }
        }
    }

    /** Avatar tondo dal file già in cache; mai scaricato qui (la riga non vale una richiesta di rete). */
    private static Bitmap avatar(int account, TLRPC.FileLocation location) {
        return MorsegramAutoCommon.avatar(account, location, AVATAR_PX);   // decodifica unica, condivisa col modulo labs
    }
}

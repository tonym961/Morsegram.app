/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.auto;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramMediaSession;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

/**
 * "In riproduzione" sul head unit per i vocali aperti dall'albero media ({@link MorsegramAutoBrowser}).
 *
 * <p>Serve perche' upstream, oggi, pubblica su {@code TelegramMediaSession} soltanto lo stato iniziale
 * ({@code STATE_NONE}) e i metadati della coda: {@code publishMetadata}/{@code publishPlaybackState} non vengono
 * chiamati da nessuno (MusicPlayerService ha una sua MediaSession, quella del telefono). Senza stato pubblicato il
 * head unit non mostra titolo/durata e i comandi del volante restano spenti. Qui osserviamo
 * {@code NotificationCenter} e pubblichiamo stato e metadati SOLO per la riproduzione partita dall'auto (chat
 * agganciata con {@link #attach}), cosi' la sessione del telefono resta esattamente come la lascia upstream.
 *
 * <p>Zero righe upstream: {@code getSession()}, {@code publishPlaybackState()} e {@code getAvailableActions()} sono
 * API pubbliche di TelegramMediaSession. Pause/play/seek dal volante arrivano alla SessionCallback upstream, che
 * chiama MediaController: niente da duplicare.
 */
final class MorsegramAutoPlayback implements NotificationCenter.NotificationCenterDelegate {

    private static MorsegramAutoPlayback instance;

    /** Lo stato si ripubblica al massimo 1 volta al secondo: messagePlayingProgressDidChanged e' fittissimo. */
    private static final long THROTTLE_MS = 900L;

    private TelegramMediaSession holder;
    private int account;
    private long dialogId;
    private boolean publishing;
    private int publishedId;
    private long publishedAt;

    private MorsegramAutoPlayback() {
    }

    /** Thread UI. Agancia la chat da cui il guidatore ha scelto il vocale. */
    static void attach(TelegramMediaSession holder, int account, MessageObject object) {
        if (holder == null || object == null) {
            return;
        }
        if (instance == null) {
            instance = new MorsegramAutoPlayback();
            instance.observe();
        }
        instance.holder = holder;
        instance.account = account;
        instance.dialogId = object.getDialogId();
        instance.publishedId = 0;
        instance.publishedAt = 0;
        instance.publish();
    }

    private void observe() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final NotificationCenter center = NotificationCenter.getInstance(a);
            center.addObserver(this, NotificationCenter.messagePlayingDidStart);
            center.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
            center.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
            center.addObserver(this, NotificationCenter.messagePlayingDidSeek);
            center.addObserver(this, NotificationCenter.messagePlayingDidReset);
        }
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.activeAccountChanged);
    }

    @Override
    public void didReceivedNotification(int id, int notifiedAccount, Object... args) {
        if (id == NotificationCenter.activeAccountChanged) {
            MorsegramAutoBrowser.invalidate();
            dialogId = 0;
            publishing = false;
            publishedId = 0;
            return;
        }
        // solo i tick di progresso vanno limitati: play/pause/seek/reset devono arrivare subito
        publish(id == NotificationCenter.messagePlayingProgressDidChanged);
    }

    private void publish() {
        publish(false);
    }

    private void publish(boolean throttled) {
        if (holder == null || dialogId == 0) {
            return;
        }
        try {
            final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
            if (playing == null || !playing.isVoice() || playing.getDialogId() != dialogId) {
                if (publishing) {
                    publishing = false;
                    holder.publishPlaybackState(new PlaybackStateCompat.Builder()
                            .setState(PlaybackStateCompat.STATE_STOPPED, 0, 1f)
                            .setActions(holder.getAvailableActions())
                            .build());
                }
                return;
            }
            final MediaSessionCompat session = holder.getSession();
            if (session == null) {
                return;
            }
            final boolean paused = MediaController.getInstance().isMessagePaused();
            final long position = Math.max(0, playing.audioProgressSec) * 1000L;
            final long now = android.os.SystemClock.elapsedRealtime();
            final boolean fresh = publishedId != playing.getId();
            if (throttled && !fresh && now - publishedAt < THROTTLE_MS) {
                return;
            }
            publishedAt = now;
            // segnaposto di scaricamento: playMessage ha chiesto il file a FileLoader e sta aspettando
            final boolean buffering = !paused && position == 0 && fresh && !MorsegramAutoBrowser.cached(account, playing);
            if (fresh) {
                publishedId = playing.getId();
                session.setMetadata(new MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title(playing))
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, chatTitle())
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ARTIST, chatTitle())
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (long) (playing.getDuration() * 1000))
                        .build());
            }
            holder.publishPlaybackState(new PlaybackStateCompat.Builder()
                    .setState(buffering
                                    ? PlaybackStateCompat.STATE_BUFFERING
                                    : paused ? PlaybackStateCompat.STATE_PAUSED : PlaybackStateCompat.STATE_PLAYING,
                            position, 1f)
                    .setActions(holder.getAvailableActions())
                    .build());
            publishing = true;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Mittente + ora: mai il testo del messaggio (un vocale non ne ha, e la trascrizione non va sul head unit). */
    private String title(MessageObject object) {
        String name = null;
        if (object.isOut()) {
            name = LocaleController.getString(R.string.FromYou);
        } else {
            final long from = object.getFromChatId();
            if (from > 0) {
                final TLRPC.User user = MessagesController.getInstance(account).getUser(from);
                if (user != null) {
                    name = ContactsController.formatName(user.first_name, user.last_name);
                }
            } else if (from < 0) {
                final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-from);
                if (chat != null) {
                    name = chat.title;
                }
            }
        }
        if (TextUtils.isEmpty(name)) {
            name = chatTitle();
        }
        return name + " · " + LocaleController.stringForMessageListDate(object.messageOwner.date);
    }

    private String chatTitle() {
        if (DialogObject.isUserDialog(dialogId)) {
            final TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
            if (user != null) {
                return ContactsController.formatName(user.first_name, user.last_name);
            }
        } else {
            final TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            if (chat != null && !TextUtils.isEmpty(chat.title)) {
                return chat.title;
            }
        }
        return LocaleController.getString("MorsegramCarVoices");
    }
}

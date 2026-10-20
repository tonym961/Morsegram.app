package it.iotatec.morsegram.carlabs.voice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import it.iotatec.morsegram.auto.MorsegramAutoCommon;

/**
 * Plays the voice notes of one conversation through the car speakers, in order, hands-free.
 *
 * <p>Everything goes through upstream {@link MediaController#playMessage(MessageObject)}, which is the same entry
 * point the phone UI uses: it marks the voice note as listened
 * ({@code MessagesController.markMessageContentAsRead}) so the "listened" receipt reaches the sender exactly like on
 * the phone (the Telegram API terms require it), it starts {@code MusicPlayerService} (so the car's own media controls
 * and notification show the playback) and it owns audio focus, ducking and routing. We add only the queue, the
 * download state and the screen-facing state.
 *
 * <p>We deliberately do NOT use {@code MediaController.setVoiceMessagesPlaylist()} for the advance: we null it out and
 * advance ourselves on {@code messagePlayingDidReset}, because upstream's playlist walk also restores a saved music
 * playlist (paused) when music was interrupted, which would silently stall the queue mid-drive. Any playback that
 * starts outside our queue (the phone user tapping a voice note, upstream restoring music) deactivates this session,
 * so we never fight the phone for the player.
 *
 * <p>Audio focus: {@link MediaController} already pauses on {@code AUDIOFOCUS_LOSS} /
 * {@code AUDIOFOCUS_LOSS_TRANSIENT} (navigation prompts, a phone call, the host's own TTS reading a message) and
 * resumes on gain for the transient case. We mirror that in the screen state, we never resume by ourselves, and we
 * never request focus directly.
 *
 * <p>Single instance on purpose: only one head unit, only one player.
 */
public final class CarVoicePlayback implements NotificationCenter.NotificationCenterDelegate {

    /** Called on the main thread whenever the state shown on the head unit changes. */
    public interface Listener {
        void onCarVoiceStateChanged();
    }

    public static final int STATE_IDLE = 0;
    public static final int STATE_DOWNLOADING = 1;
    public static final int STATE_PLAYING = 2;
    public static final int STATE_PAUSED = 3;
    public static final int STATE_FINISHED = 4;

    @Nullable
    private static CarVoicePlayback instance;

    @NonNull
    public static CarVoicePlayback getInstance() {
        if (instance == null) {
            instance = new CarVoicePlayback();
        }
        return instance;
    }

    private final ArrayList<MessageObject> queue = new ArrayList<>();
    private int account = -1;
    private long dialogId;
    private int index = -1;
    private boolean active;
    private boolean finished;

    @Nullable
    private Listener listener;
    @Nullable
    private NotificationCenter.ObserversGroup observers;

    private CarVoicePlayback() {
    }

    // ===== wiring =====

    /** The screen showing the playback state; cleared when it goes away. */
    public void setListener(@Nullable Listener listener) {
        this.listener = listener;
    }

    public void clearListener(@Nullable Listener listener) {
        if (this.listener == listener) {
            this.listener = null;
        }
    }

    // ===== queue =====

    /**
     * Starts the queue. {@code voices} must be in chronological order (oldest first); only playable voice notes are
     * kept. Returns false when there is nothing to play (or the chat is hidden, which must never be audible in the car).
     */
    public boolean start(int account, long dialogId, @Nullable List<MessageObject> voices, int startIndex) {
        if (!MorsegramAutoCommon.allowed(account, dialogId)) {
            return false;
        }
        final ArrayList<MessageObject> filtered = new ArrayList<>();
        if (voices != null) {
            for (int i = 0; i < voices.size(); i++) {
                final MessageObject messageObject = voices.get(i);
                if (CarVoiceLabels.isPlayableVoice(messageObject)) {
                    filtered.add(messageObject);
                }
            }
        }
        if (filtered.isEmpty()) {
            return false;
        }
        stopInternal(false);
        queue.clear();
        queue.addAll(filtered);
        this.account = account;
        this.dialogId = dialogId;
        this.index = Math.max(0, Math.min(startIndex, queue.size() - 1));
        this.active = true;
        this.finished = false;
        subscribe();
        playCurrent();
        return true;
    }

    /** Index of the first voice note still to listen to, or the newest one when everything was already listened. */
    public static int startIndexFor(@Nullable List<MessageObject> voices) {
        if (voices == null || voices.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < voices.size(); i++) {
            if (CarVoiceLabels.isUnlistened(voices.get(i))) {
                return i;
            }
        }
        return voices.size() - 1;
    }

    public static int unlistenedCount(@Nullable List<MessageObject> voices) {
        if (voices == null) return 0;
        int count = 0;
        for (int i = 0; i < voices.size(); i++) {
            if (CarVoiceLabels.isUnlistened(voices.get(i))) count++;
        }
        return count;
    }

    // ===== one-tap actions =====

    public void playPause() {
        final MessageObject current = current();
        if (current == null) {
            return;
        }
        final MediaController controller = MediaController.getInstance();
        if (!active || !isOurPlayer()) {
            // stopped, finished, or the player was taken over: start this item again from the top of the queue item
            active = true;
            finished = false;
            subscribe();
            playCurrent();
            return;
        }
        if (controller.isDownloadingCurrentMessage()) {
            return;
        }
        if (controller.isMessagePaused()) {
            controller.playMessage(current);
        } else {
            controller.pauseMessage(current);
        }
        notifyState();
    }

    public boolean hasNext() {
        return active && index >= 0 && index + 1 < queue.size();
    }

    public boolean hasPrevious() {
        return index > 0;
    }

    public void next() {
        if (!hasNext()) {
            return;
        }
        index++;
        finished = false;
        active = true;
        subscribe();
        playCurrent();
    }

    public void previous() {
        if (!hasPrevious()) {
            return;
        }
        index--;
        finished = false;
        active = true;
        subscribe();
        playCurrent();
    }

    /** Obvious, one-tap stop: releases the player and the audio focus with it. */
    public void stop() {
        stopInternal(true);
        notifyState();
    }

    /** La schermata sparisce: niente coda appesa in memoria per il resto del processo. */
    public void release() {
        if (active) {
            stopInternal(false);
        } else {
            queue.clear();
            index = -1;
            finished = false;
        }
    }

    private void stopInternal(boolean keepQueue) {
        final boolean wasActive = active;
        active = false;
        if (wasActive && isOurPlayer()) {
            // active == false first: the messagePlayingDidReset this triggers must not advance the queue
            MediaController.getInstance().cleanupPlayer(true, true);
        }
        unsubscribe();
        if (!keepQueue) {
            queue.clear();
            index = -1;
            finished = false;
        }
    }

    private void playCurrent() {
        final MessageObject current = current();
        if (current == null) {
            return;
        }
        final MediaController controller = MediaController.getInstance();
        // we own the queue: upstream must not advance it behind our back (see class javadoc)
        controller.setVoiceMessagesPlaylist(null, false);
        // playMessage() downloads the file itself when it is missing (FileLoader, PRIORITY_LOW) and plays it when the
        // download lands; isDownloadingCurrentMessage() is what we show as "Scarico...".
        controller.playMessage(current);
        prefetchNext();
        notifyState();
    }

    /** Keep the next voice note warm so the queue does not stall on a slow link mid-drive. */
    private void prefetchNext() {
        if (!hasNext()) {
            return;
        }
        final MessageObject next = queue.get(index + 1);
        if (next == null || next.getDocument() == null) {
            return;
        }
        try {
            final File path = FileLoader.getInstance(account).getPathToMessage(next.messageOwner);
            if (path != null && path.exists()) {
                return;
            }
            FileLoader.getInstance(account).loadFile(next.getDocument(), next, FileLoader.PRIORITY_LOW, 0);
        } catch (Throwable ignored) {
        }
    }

    // ===== state for the screen =====

    public boolean isActive() {
        return active;
    }

    public long getDialogId() {
        return dialogId;
    }

    public int getAccount() {
        return account;
    }

    @Nullable
    public MessageObject current() {
        if (index < 0 || index >= queue.size()) {
            return null;
        }
        return queue.get(index);
    }

    public int getPosition() {
        return index + 1;
    }

    public int getCount() {
        return queue.size();
    }

    public int getState() {
        if (!active) {
            return finished ? STATE_FINISHED : STATE_IDLE;
        }
        if (current() == null || !isOurPlayer()) {
            return STATE_IDLE;
        }
        final MediaController controller = MediaController.getInstance();
        if (controller.isDownloadingCurrentMessage()) {
            return STATE_DOWNLOADING;
        }
        if (controller.isMessagePaused()) {
            return STATE_PAUSED;
        }
        return STATE_PLAYING;
    }

    public int getElapsedSec() {
        final MessageObject current = current();
        if (current == null || !isOurPlayer()) {
            return 0;
        }
        return Math.max(0, current.audioProgressSec);
    }

    public int getDurationSec() {
        return CarVoiceLabels.durationSec(current());
    }

    @NonNull
    public String getSenderName() {
        return CarVoiceLabels.senderName(account, current());
    }

    private boolean isOurPlayer() {
        final MessageObject current = current();
        if (current == null) {
            return false;
        }
        final MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        return isSame(playing, current);
    }

    private static boolean isSame(@Nullable MessageObject a, @Nullable MessageObject b) {
        return a != null && b != null && a.getId() == b.getId() && a.getDialogId() == b.getDialogId();
    }

    // ===== notifications =====

    private void subscribe() {
        if (observers != null || account < 0) {
            return;
        }
        // NIENTE messagePlayingProgressDidChanged: upstream lo manda ogni 17 ms e ogni ridisegno della schermata
        // costa un template della quota di task dell'host (5), quindi si ascolta solo i cambi di stato veri
        observers = NotificationCenter.getInstance(account).createObserversGroup(this)
                .add(NotificationCenter.messagePlayingDidReset)
                .add(NotificationCenter.messagePlayingPlayStateChanged)
                .add(NotificationCenter.messagePlayingDidStart);
    }

    private void unsubscribe() {
        if (observers != null) {
            observers.removeAllObservers();
            observers = null;
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (!active || account != this.account) {
            return;
        }
        if (id == NotificationCenter.messagePlayingDidReset) {
            final MessageObject current = current();
            if (current == null || args.length == 0 || !(args[0] instanceof Integer)
                    || (Integer) args[0] != current.getId()) {
                return;
            }
            // the item ended on its own (upstream posts this only when it has no playlist left of its own)
            if (hasNext()) {
                index++;
                playCurrent();
            } else {
                finished = true;
                active = false;
                unsubscribe();
                notifyState();
            }
        } else if (id == NotificationCenter.messagePlayingDidStart) {
            final MessageObject started = args.length > 0 && args[0] instanceof MessageObject
                    ? (MessageObject) args[0] : null;
            if (started != null && !isSame(started, current())) {
                // something else took the player (phone user, restored music playlist): step aside
                active = false;
                unsubscribe();
            }
            notifyState();
        } else if (id == NotificationCenter.messagePlayingPlayStateChanged) {
            notifyState();
        }
    }

    private void notifyState() {
        final Listener target = listener;
        if (target == null) {
            return;
        }
        AndroidUtilities.runOnUIThread(target::onCarVoiceStateChanged);
    }
}

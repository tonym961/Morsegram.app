package it.iotatec.morsegram.carlabs.voice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.tgnet.ConnectionsManager;

import java.util.ArrayList;
import java.util.List;

import it.iotatec.morsegram.auto.MorsegramAutoCommon;

/**
 * Loads the voice notes of one conversation, oldest first, so the car can play them without opening a chat.
 *
 * <p>Uses upstream {@code MediaDataController.loadMedia(..., MEDIA_AUDIO, ...)} - the same shared-media query the
 * phone's "Voice messages" tab uses - with {@code fromCache = 1}: upstream answers from the local database and falls
 * through to the server by itself when the cache has nothing, so a chat whose voice notes are already on the phone
 * needs no network at all. Round video messages come back with the same filter and are dropped here.
 */
public final class CarVoiceLoader implements NotificationCenter.NotificationCenterDelegate {

    public interface Callback {
        /** Chronological order, oldest first; empty when the chat has no playable voice note. */
        void onVoicesLoaded(@NonNull ArrayList<MessageObject> voices);

        void onVoicesFailed();
    }

    private static final int DEFAULT_LIMIT = 30;
    private static final long TIMEOUT_MS = 20_000L;

    private final int account;
    private final long dialogId;
    private final int classGuid;

    @Nullable
    private Callback callback;
    @Nullable
    private NotificationCenter.ObserversGroup observers;
    @Nullable
    private Runnable timeout;
    private boolean done;

    public CarVoiceLoader(int account, long dialogId) {
        this.account = account;
        this.dialogId = dialogId;
        this.classGuid = ConnectionsManager.generateClassGuid();
    }

    /** Hidden chats must never be reachable from the head unit, not even as audio. */
    public void load(@NonNull Callback callback) {
        this.callback = callback;
        if (!MorsegramAutoCommon.allowed(account, dialogId)) {
            fail();
            return;
        }
        observers = NotificationCenter.getInstance(account).createObserversGroup(this)
                .add(NotificationCenter.mediaDidLoad);
        timeout = this::fail;
        AndroidUtilities.runOnUIThread(timeout, TIMEOUT_MS);
        try {
            MediaDataController.getInstance(account).loadMedia(
                    dialogId, DEFAULT_LIMIT, 0, 0, MediaDataController.MEDIA_AUDIO,
                    0, 1, classGuid, 0, null, null);
        } catch (Throwable t) {
            fail();
        }
    }

    public void cancel() {
        done = true;
        callback = null;
        cleanup();
        try {
            ConnectionsManager.getInstance(account).cancelRequestsForGuid(classGuid);
            MessagesStorage.getInstance(account).cancelTasksForGuid(classGuid);
        } catch (Throwable ignored) {
        }
    }

    private void cleanup() {
        if (observers != null) {
            observers.removeAllObservers();
            observers = null;
        }
        if (timeout != null) {
            AndroidUtilities.cancelRunOnUIThread(timeout);
            timeout = null;
        }
    }

    private void fail() {
        if (done) return;
        done = true;
        final Callback target = callback;
        callback = null;
        cleanup();
        if (target != null) target.onVoicesFailed();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (done || id != NotificationCenter.mediaDidLoad || account != this.account) {
            return;
        }
        if (args.length < 5 || !(args[3] instanceof Integer) || (Integer) args[3] != classGuid) {
            return;
        }
        if (!(args[4] instanceof Integer) || (Integer) args[4] != MediaDataController.MEDIA_AUDIO) {
            return;
        }
        final ArrayList<MessageObject> voices = new ArrayList<>();
        if (args[2] instanceof List) {
            final List<?> loaded = (List<?>) args[2];
            // shared media comes back newest first: walk backwards for chronological order
            for (int i = loaded.size() - 1; i >= 0; i--) {
                final Object item = loaded.get(i);
                if (item instanceof MessageObject && CarVoiceLabels.isPlayableVoice((MessageObject) item)) {
                    voices.add((MessageObject) item);
                }
            }
        }
        done = true;
        final Callback target = callback;
        callback = null;
        cleanup();
        if (target != null) target.onVoicesLoaded(voices);
    }
}

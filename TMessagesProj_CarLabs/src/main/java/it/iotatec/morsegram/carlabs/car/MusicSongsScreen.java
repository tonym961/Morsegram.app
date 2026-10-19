package it.iotatec.morsegram.carlabs.car;

import android.support.v4.media.session.MediaControllerCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Header;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.TelegramMediaSession;

import java.util.ArrayList;

/**
 * Songs of one chat (Music tab); tapping a row plays it through the app's MediaSession.
 * Derived from upstream org.telegram.messenger.car.MusicSongsScreen; title/back action go through
 * {@link Header} (setTitle/setHeaderAction are deprecated in car.app 1.7.0).
 */
public class MusicSongsScreen extends Screen
        implements DefaultLifecycleObserver, NotificationCenter.NotificationCenterDelegate {

    private static final int MAX_SONGS = 200;

    private final long dialogId;
    private final String title;
    @Nullable
    private NotificationCenter.ObserversGroup observers;

    public MusicSongsScreen(@NonNull CarContext carContext, long dialogId, String title) {
        super(carContext);
        this.dialogId = dialogId;
        this.title = title != null ? title : "";
        getLifecycle().addObserver(this);
    }

    @Override
    public void onResume(@NonNull LifecycleOwner owner) {
        // upstream deprecated addObserver(delegate, id) in favour of observer groups
        unsubscribe();
        observers = NotificationCenter.getGlobalInstance().createObserversGroup(this)
                .add(NotificationCenter.messagePlayingDidStart)
                .add(NotificationCenter.messagePlayingPlayStateChanged)
                .add(NotificationCenter.activeAccountChanged);
    }

    @Override
    public void onPause(@NonNull LifecycleOwner owner) {
        unsubscribe();
    }

    private void unsubscribe() {
        if (observers != null) {
            observers.removeAllObservers();
            observers = null;
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.activeAccountChanged) {
            getScreenManager().pop();
            return;
        }
        invalidate();
    }

    @NonNull
    @Override
    public Template onGetTemplate() {
        TelegramMediaSession session = TelegramMediaSession.getInstance(getCarContext().getApplicationContext());
        Header header = new Header.Builder()
                .setTitle(title.isEmpty() ? " " : title)
                .setStartHeaderAction(Action.BACK)
                .build();

        ArrayList<MessageObject> songs = session.getMusicMessages(dialogId);
        if (songs == null || songs.isEmpty()) {
            return new MessageTemplate.Builder(LocaleController.getString(R.string.NoCarMusic))
                    .setHeader(header)
                    .build();
        }

        MessageObject playing = MediaController.getInstance().getPlayingMessageObject();
        long playingId = playing != null ? playing.getId() : 0;
        long playingDialog = playing != null ? playing.getDialogId() : 0;

        ItemList.Builder list = new ItemList.Builder();
        int limit = Math.min(songs.size(), MAX_SONGS);
        for (int i = 0; i < limit; i++) {
            MessageObject mo = songs.get(i);
            if (mo == null) continue;
            String songTitle = mo.getMusicTitle();
            String author = mo.getMusicAuthor();

            Row.Builder row = new Row.Builder()
                    .setTitle(songTitle != null ? songTitle : " ");
            if (author != null && !author.isEmpty()) {
                row.addText(author);
            }
            boolean isCurrent = playing != null
                    && playingDialog == mo.getDialogId()
                    && playingId == mo.getId();
            if (isCurrent) {
                row.setImage(new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), R.drawable.ic_player)).build());
            }
            final int index = i;
            row.setOnClickListener(() -> playAtIndex(index));
            list.addItem(row.build());
        }

        return new ListTemplate.Builder()
                .setHeader(header)
                .setSingleList(list.build())
                .build();
    }

    private void playAtIndex(int index) {
        try {
            TelegramMediaSession session = TelegramMediaSession.getInstance(getCarContext().getApplicationContext());
            MediaControllerCompat controller = session.getSession().getController();
            controller.getTransportControls().playFromMediaId(dialogId + "_" + index, null);
        } catch (Throwable ignored) {
        }
    }
}

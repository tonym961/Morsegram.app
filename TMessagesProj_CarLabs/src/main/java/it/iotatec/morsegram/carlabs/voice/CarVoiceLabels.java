package it.iotatec.morsegram.carlabs.voice;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.ContactsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;

import it.iotatec.morsegram.auto.MorsegramAutoCommon;

/**
 * Short, head-unit friendly labels for voice notes.
 *
 * <p>Android Auto rules: a messaging app must never paint message content on screen (the host reads messages aloud),
 * and rows truncate. So a voice note never becomes a transcript here: it becomes
 * "Messaggio vocale 0:12", which is what the host announces instead of trying to speak audio.
 */
public final class CarVoiceLabels {

    private CarVoiceLabels() {
    }

    /** m:ss (or h:mm:ss), i.e. "0:12" - unlike LocaleController.formatShortDuration, which pads to "00:12". */
    @NonNull
    public static String mmss(int seconds) {
        return MorsegramAutoCommon.clock(seconds);   // formato unico, condiviso con l'albero media di produzione
    }

    /** Duration of a voice note in whole seconds (document attribute, so it is known before downloading). */
    public static int durationSec(@Nullable MessageObject messageObject) {
        if (messageObject == null) return 0;
        return (int) Math.round(messageObject.getDuration());
    }

    /**
     * Body of the {@code CarMessage} that stands for a voice note: "Messaggio vocale 0:12".
     * Never the transcript, never the audio: this is the text the host speaks.
     */
    @NonNull
    public static String voiceBody(@Nullable MessageObject messageObject) {
        final int duration = durationSec(messageObject);
        if (duration <= 0) {
            return LocaleController.getString("MorsegramCarVoiceNote");
        }
        return LocaleController.formatString("MorsegramCarVoiceNoteDuration", 0, mmss(duration));
    }

    /** Who sent this voice note, short enough for a head-unit title. */
    @NonNull
    public static String senderName(int account, @Nullable MessageObject messageObject) {
        if (messageObject == null) {
            return "";
        }
        if (messageObject.isOutOwner()) {
            return LocaleController.getString(R.string.FromYou);
        }
        final long senderId = messageObject.getSenderId();
        final MessagesController controller = MessagesController.getInstance(account);
        if (senderId > 0) {
            final TLRPC.User user = controller.getUser(senderId);
            if (user != null) {
                final String name = ContactsController.formatName(user.first_name, user.last_name);
                if (name != null && name.length() > 0) return name;
            }
        } else if (senderId < 0) {
            final TLRPC.Chat chat = controller.getChat(-senderId);
            if (chat != null && chat.title != null) return chat.title;
        }
        return LocaleController.getString("MorsegramCarVoiceNote");
    }

    /** A voice note we are allowed to queue: real voice note, not a play-once one. */
    public static boolean isPlayableVoice(@Nullable MessageObject messageObject) {
        return messageObject != null
                && messageObject.messageOwner != null
                && messageObject.isVoice()
                && !messageObject.isVoiceOnce()
                && messageObject.getDocument() != null;
    }

    /** Not listened yet: incoming and still content-unread (exactly the condition the phone shows a blue dot for). */
    public static boolean isUnlistened(@Nullable MessageObject messageObject) {
        return messageObject != null && !messageObject.isOut() && messageObject.isContentUnread();
    }
}

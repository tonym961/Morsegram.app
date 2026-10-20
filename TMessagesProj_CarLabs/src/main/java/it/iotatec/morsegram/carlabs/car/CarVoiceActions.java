/*
 * Morsegram - client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.carlabs.car;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.messaging.model.CarMessage;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.CarText;
import androidx.car.app.model.Row;
import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;

import java.util.List;

import it.iotatec.morsegram.auto.MorsegramAutoCommon;
import it.iotatec.morsegram.carlabs.voice.CarVoiceLabels;
import it.iotatec.morsegram.carlabs.voice.CarVoicePlayback;

/**
 * Gli innesti dell'ascolto vocale per le altre schermate dell'auto: una riga, un'azione, una sola chiamata di setup.
 *
 * <p>{@link #install()} registra il decoratore dei {@code CarMessage}: un vocale non diventa testo da leggere ma
 * l'etichetta corta "Messaggio vocale 0:12", così l'host annuncia che c'è un vocale invece di provare a leggere
 * dell'audio. {@link #listenRow} e {@link #playAction} portano, con un tap, a {@link VoicePlaybackScreen}, che parte
 * da sola: niente seconda schermata, niente bersagli piccoli mentre si guida.</p>
 */
public final class CarVoiceActions {

    private static boolean installed;

    private CarVoiceActions() {
    }

    /**
     * Da chiamare una volta per sessione auto (vedi {@code MorsegramCarSession}): i vocali mostrati in una
     * conversazione diventano "Messaggio vocale 0:12" invece del testo generico della notifica.
     */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        CarConversations.setMessageDecorator(new CarConversations.MessageDecorator() {
            @Override
            public void decorate(@NonNull CarMessage.Builder builder, int account, @NonNull MessageObject message) {
                if (CarVoiceLabels.isPlayableVoice(message)) {
                    builder.setBody(CarText.create(CarVoiceLabels.voiceBody(message)));
                }
            }
        });
    }

    /** Le chat nascoste non arrivano all'head unit, nemmeno come audio. */
    public static boolean isAvailable(int account, long dialogId) {
        return MorsegramAutoCommon.allowed(account, dialogId);
    }

    /** true se in questa lista di messaggi c'è già un vocale riproducibile. */
    public static boolean hasVoice(@Nullable List<MessageObject> messages) {
        if (messages == null) {
            return false;
        }
        for (int i = 0; i < messages.size(); i++) {
            if (CarVoiceLabels.isPlayableVoice(messages.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Riga per un ListTemplate: "Ascolta ultimi vocali". {@code knownMessages} è solo una rete di sicurezza per
     * quando i vocali della chat non si riescono a caricare: la coda vera la prepara {@link VoicePlaybackScreen}.
     */
    @NonNull
    public static Row listenRow(@NonNull Screen screen, int account, long dialogId,
                                @Nullable String conversationTitle, @Nullable List<MessageObject> knownMessages) {
        final Row.Builder row = new Row.Builder()
                .setTitle(LocaleController.getString("MorsegramCarListenVoices"))
                .setImage(icon(screen.getCarContext(), R.drawable.ic_action_play), Row.IMAGE_TYPE_ICON)
                .setBrowsable(true)
                .setOnClickListener(() -> open(screen, account, dialogId, conversationTitle, knownMessages));
        final int unlistened = CarVoicePlayback.unlistenedCount(knownMessages);
        if (unlistened > 0) {
            row.addText(LocaleController.formatString("MorsegramCarVoiceNewCount", 0, unlistened));
        }
        return row.build();
    }

    @NonNull
    public static Row listenRow(@NonNull Screen screen, int account, long dialogId,
                                @Nullable String conversationTitle) {
        return listenRow(screen, account, dialogId, conversationTitle, null);
    }

    /**
     * Azione per un {@code ConversationItem}: "Riproduci vocale". Un conversation item accetta <b>una</b> azione e
     * l'host pretende che abbia un'icona.
     */
    @NonNull
    public static Action playAction(@NonNull Screen screen, int account, long dialogId,
                                    @Nullable String conversationTitle, @Nullable List<MessageObject> knownMessages) {
        return new Action.Builder()
                .setTitle(LocaleController.getString("MorsegramCarPlayVoice"))
                .setIcon(icon(screen.getCarContext(), R.drawable.ic_action_play))
                .setOnClickListener(() -> open(screen, account, dialogId, conversationTitle, knownMessages))
                .build();
    }

    /** Apre la riproduzione (che parte da sola) per questa chat. */
    public static void open(@NonNull Screen screen, int account, long dialogId,
                            @Nullable String conversationTitle, @Nullable List<MessageObject> knownMessages) {
        if (!isAvailable(account, dialogId)) {
            return;
        }
        screen.getScreenManager().push(new VoicePlaybackScreen(
                screen.getCarContext(), account, dialogId, conversationTitle, knownMessages));
    }

    @NonNull
    private static CarIcon icon(@NonNull CarContext carContext, int resId) {
        return new CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build();
    }
}

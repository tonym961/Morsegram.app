/*
 * Morsegram - client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.carlabs.car;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.Screen;
import androidx.car.app.model.Action;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Row;
import androidx.core.graphics.drawable.IconCompat;

import org.telegram.messenger.LocaleController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import it.iotatec.morsegram.auto.MorsegramAutoCommon;
import it.iotatec.morsegram.carlabs.voice.CarVoiceRecorder;

/**
 * Gli innesti della registrazione vocale per le schermate dell'auto: una riga, un'azione, una sola chiamata di
 * setup. Tutto porta, con un tap, a {@link RecordScreen}, che parte già registrando.
 *
 * <p>{@link #install()} mette l'azione "Vocale" sui {@code ConversationItem} costruiti da
 * {@link CarConversations}: un {@code ConversationItem} accetta <b>una</b> sola azione
 * ({@code ACTIONS_CONSTRAINTS_CONVERSATION_ITEM}: maxActions 1, maxCustomTitles 1), quindi quello slot è uno
 * solo per tutta l'app. Una schermata che preferisca mettere lì l'ascolto
 * ({@code CarVoiceActions.playAction}) può richiamare {@code CarConversations.setActionProvider} dopo di noi e
 * usare {@link #recordRow} per la registrazione: vince l'ultima chiamata, nessuna delle due rompe l'altra.</p>
 */
public final class CarRecordActions {

    private static boolean installed;

    private CarRecordActions() {
    }

    /** Da chiamare una volta per sessione auto (vedi {@code MorsegramCarSession}). */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        CarConversations.setActionProvider(CarRecordActions::actionsFor);
    }

    /** Le chat nascoste non arrivano all'head unit, nemmeno come bersaglio di un invio. */
    public static boolean isAvailable(int account, long dialogId) {
        return CarVoiceRecorder.isSupported() && MorsegramAutoCommon.allowed(account, dialogId);
    }

    @NonNull
    private static List<Action> actionsFor(@NonNull CarContext carContext, int account, long dialogId) {
        if (!isAvailable(account, dialogId)) {
            return Collections.emptyList();
        }
        List<Action> actions = new ArrayList<>(1);
        actions.add(recordAction(carContext, account, dialogId));
        return actions;
    }

    /**
     * Azione per un {@code ConversationItem}: "Vocale" + icona microfono (l'host pretende l'icona). Titolo
     * cortissimo perché le righe dell'head unit troncano; l'icona dice il resto.
     */
    @NonNull
    public static Action recordAction(@NonNull CarContext carContext, int account, long dialogId) {
        return new Action.Builder()
                .setTitle(LocaleController.getString("MorsegramCarVoiceAction"))
                .setIcon(micIcon(carContext))
                .setOnClickListener(() -> open(carContext, account, dialogId))
                .build();
    }

    /** Riga per un ListTemplate (schermata chat o conversazione): "Registra vocale". */
    @NonNull
    public static Row recordRow(@NonNull Screen screen, int account, long dialogId) {
        return new Row.Builder()
                .setTitle(LocaleController.getString("MorsegramCarRecordVoice"))
                .setImage(micIcon(screen.getCarContext()), Row.IMAGE_TYPE_ICON)
                .setBrowsable(true)
                .setOnClickListener(() -> open(screen.getCarContext(), account, dialogId))
                .build();
    }

    /** Apre la registrazione (che parte da sola) verso questa chat. */
    public static void open(@NonNull CarContext carContext, int account, long dialogId) {
        if (!isAvailable(account, dialogId)) {
            return;
        }
        carContext.getCarService(androidx.car.app.ScreenManager.class)
                .push(new RecordScreen(carContext, account, dialogId));
    }

    @NonNull
    private static CarIcon micIcon(@NonNull CarContext carContext) {
        return new CarIcon.Builder(IconCompat.createWithResource(
                carContext, it.iotatec.morsegram.carlabs.R.drawable.morsegram_car_mic))
                .setTint(CarColor.DEFAULT)
                .build();
    }

    @Nullable
    static Screen recordScreen(@NonNull CarContext carContext, int account, long dialogId) {
        return isAvailable(account, dialogId) ? new RecordScreen(carContext, account, dialogId) : null;
    }
}

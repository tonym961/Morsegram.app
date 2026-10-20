package it.iotatec.morsegram.carlabs.car;

import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.car.app.Screen;
import androidx.car.app.Session;

/** One Android Auto session: starts on the Chats/Music home screen. */
public class MorsegramCarSession extends Session {

    @NonNull
    @Override
    public Screen onCreateScreen(@NonNull Intent intent) {
        // i vocali devono essere annunciati come vocali, non letti: decoratore dei CarMessage, idempotente
        CarVoiceActions.install();
        // azione "Vocale" sulle conversazioni: registra e invia un vocale dall'auto, idempotente
        CarRecordActions.install();
        return new HomeScreen(getCarContext());
    }
}

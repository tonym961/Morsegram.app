package it.iotatec.morsegram.wear

import android.app.Application
import it.iotatec.morsegram.wear.chats.ChatRepository
import it.iotatec.morsegram.wear.notify.MessageNotifications
import it.iotatec.morsegram.wear.settings.AirplaneMode
import it.iotatec.morsegram.wear.tdlib.Tdlib

class MorsegramWearApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Tdlib.start(this)
        // La cache delle chat si iscrive agli update prima che esista qualsiasi UI
        // (evita i getChat di recupero per gli updateNewChat arrivati prima della lista).
        ChatRepository.start()
        // Notifiche: canale, bridging, registrazione push e osservatore delle notifiche TDLib.
        MessageNotifications.ensureStarted(this)
        // Modalità aereo in-app: riapplica la scelta persistita al client TDLib (e a ogni client successivo).
        AirplaneMode.applyPersisted(this)
        // Stato online = app visibile (termini dell'API: niente ghost mode); va registrato prima di ogni activity.
        OnlineStatus.start(this)
    }

    companion object {
        lateinit var instance: MorsegramWearApp
            private set
    }
}

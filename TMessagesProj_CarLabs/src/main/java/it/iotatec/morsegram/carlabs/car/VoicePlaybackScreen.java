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
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.Header;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Template;
import androidx.core.graphics.drawable.IconCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;

import java.util.ArrayList;
import java.util.List;

import it.iotatec.morsegram.carlabs.voice.CarVoiceLabels;
import it.iotatec.morsegram.carlabs.voice.CarVoiceLoader;
import it.iotatec.morsegram.carlabs.voice.CarVoicePlayback;

/**
 * Ascolto dei vocali di una chat sull'head unit: si apre e parte.
 *
 * <p>Un tap sulla riga "Ascolta ultimi vocali" apre questa schermata, che mette in coda i vocali <b>non ancora
 * ascoltati</b> della chat (in ordine, dal più vecchio; il più recente se erano già tutti ascoltati) e li riproduce
 * dagli altoparlanti dell'auto. A schermo c'è solo il titolo della chat e una riga di stato costante
 * ("In riproduzione" / "In pausa"): il contenuto di un messaggio non si disegna mai (lo legge l'host), un vocale
 * non si trascrive — si ascolta — e niente cambia da sé a schermo, per non consumare la quota di template
 * dell'host (vedi {@link #statusLine(int)}).</p>
 *
 * <p>La riproduzione vive in {@link CarVoicePlayback} sopra {@code MediaController.playMessage()}, quindi la ricevuta
 * "ascoltato" parte esattamente come dal telefono (termini API Telegram). Uscire dalla schermata ferma l'audio:
 * l'auto non deve continuare a parlare dopo che il guidatore è andato altrove.</p>
 */
public class VoicePlaybackScreen extends Screen implements DefaultLifecycleObserver, CarVoicePlayback.Listener {

    private final int account;
    private final long dialogId;
    private final String conversationTitle;
    @Nullable
    private final ArrayList<MessageObject> fallbackVoices;

    @Nullable
    private ArrayList<MessageObject> voices;
    @Nullable
    private CarVoiceLoader loader;
    private boolean loadKicked;
    private boolean empty;
    private boolean started;

    /**
     * @param knownVoices messaggi già in mano alla schermata chiamante (possono contenere vocali o no): usati solo
     *                    come rete di sicurezza se la ricerca dei vocali della chat non trova nulla
     */
    public VoicePlaybackScreen(@NonNull CarContext carContext, int account, long dialogId,
                               @Nullable String conversationTitle, @Nullable List<MessageObject> knownVoices) {
        super(carContext);
        this.account = account;
        this.dialogId = dialogId;
        this.conversationTitle = conversationTitle != null ? conversationTitle : "";
        this.fallbackVoices = filterVoices(knownVoices);
        getLifecycle().addObserver(this);
    }

    public VoicePlaybackScreen(@NonNull CarContext carContext, int account, long dialogId,
                               @Nullable String conversationTitle) {
        this(carContext, account, dialogId, conversationTitle, null);
    }

    @Nullable
    private static ArrayList<MessageObject> filterVoices(@Nullable List<MessageObject> messages) {
        if (messages == null) {
            return null;
        }
        final ArrayList<MessageObject> filtered = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (CarVoiceLabels.isPlayableVoice(messages.get(i))) {
                filtered.add(messages.get(i));
            }
        }
        return filtered.isEmpty() ? null : filtered;
    }

    // ===== ciclo di vita =====

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        final CarVoicePlayback playback = CarVoicePlayback.getInstance();
        playback.setListener(this);
        if (voices != null) {
            // tornando dalla mappa la riproduzione e' ancora in corso: si riaggancia la UI, non si riparte da capo
            if (playback.isActive()) {
                started = true;
                invalidate();
                return;
            }
            startPlayback();
            return;
        }
        if (loadKicked) {
            return;
        }
        loadKicked = true;
        final CarVoiceLoader voiceLoader = new CarVoiceLoader(account, dialogId);
        loader = voiceLoader;
        voiceLoader.load(new CarVoiceLoader.Callback() {
            @Override
            public void onVoicesLoaded(@NonNull ArrayList<MessageObject> loaded) {
                loader = null;
                voices = !loaded.isEmpty() ? loaded : fallbackVoices;
                empty = voices == null;
                if (voices != null) {
                    startPlayback();
                }
                invalidate();
            }

            @Override
            public void onVoicesFailed() {
                loader = null;
                voices = fallbackVoices;
                empty = voices == null;
                if (voices != null) {
                    startPlayback();
                }
                invalidate();
            }
        });
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        // la schermata non e' piu' visibile (es. il guidatore passa alla mappa): il vocale CONTINUA,
        // come farebbe qualunque app media in auto. Si stacca solo la UI per non ridisegnare a vuoto
        // (e non consumare la quota di template dell'host).
        CarVoicePlayback.getInstance().clearListener(this);
    }

    @Override
    public void onDestroy(@NonNull LifecycleOwner owner) {
        // la schermata esce davvero dallo stack (indietro) o la sessione si chiude: qui si smette di parlare
        if (loader != null) {
            loader.cancel();
            loader = null;
        }
        started = false;
        final CarVoicePlayback playback = CarVoicePlayback.getInstance();
        playback.stop();
        playback.release();
    }

    private void startPlayback() {
        if (started || voices == null) {
            return;
        }
        started = true;
        final CarVoicePlayback playback = CarVoicePlayback.getInstance();
        if (!playback.start(account, dialogId, voices, CarVoicePlayback.startIndexFor(voices))) {
            empty = true;
        }
        invalidate();
    }

    @Override
    public void onCarVoiceStateChanged() {
        // solo i cambi di stato veri (avvio, pausa, avanzamento, stop): CarVoicePlayback non segue più il
        // progresso al secondo, perché ogni ridisegno costa un template della quota di task dell'host
        if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
            invalidate();
        }
    }

    // ===== template =====

    @NonNull
    @Override
    public Template onGetTemplate() {
        final CarVoicePlayback playback = CarVoicePlayback.getInstance();
        // titolo FISSO per tutta la vita della schermata (mai il mittente del vocale in corso): cambiarlo fa
        // contare all'host un template nuovo invece di un refresh gratuito, e la quota è di 5 per task
        final Header header = new Header.Builder()
                .setTitle(conversationTitle.isEmpty()
                        ? LocaleController.getString("MorsegramCarVoiceNote") : conversationTitle)
                .setStartHeaderAction(Action.BACK)
                .build();

        if (empty) {
            return new MessageTemplate.Builder(LocaleController.getString("MorsegramCarVoiceNone"))
                    .setHeader(header)
                    .build();
        }
        if (voices == null) {
            return new MessageTemplate.Builder(LocaleController.getString("MorsegramCarVoiceLoading"))
                    .setHeader(header)
                    .setLoading(true)
                    .build();
        }

        final int state = playback.getState();
        final MessageTemplate.Builder builder = new MessageTemplate.Builder(statusLine(state))
                .setHeader(header)
                .setIcon(icon(R.drawable.filled_voice_comment_32));

        if (state == CarVoicePlayback.STATE_PLAYING || state == CarVoicePlayback.STATE_DOWNLOADING) {
            // scarico e riproduzione danno lo STESSO template (testo e azioni): quando il file arriva e l'audio
            // parte, per l'host è un refresh gratuito invece di un template nuovo, e così un'intera coda di
            // vocali — anche tutta da scaricare — consuma un solo passo della quota di task
            builder.addAction(action(LocaleController.getString("MorsegramCarVoicePause"),
                    R.drawable.ic_action_pause, true, playback::playPause));
            builder.addAction(action(LocaleController.getString("MorsegramCarVoiceStop"),
                    R.drawable.filled_profile_stop_24, false, playback::stop));
        } else if (state == CarVoicePlayback.STATE_PAUSED) {
            builder.addAction(action(LocaleController.getString("MorsegramCarVoicePlay"),
                    R.drawable.ic_action_play, true, playback::playPause));
            builder.addAction(action(LocaleController.getString("MorsegramCarVoiceStop"),
                    R.drawable.filled_profile_stop_24, false, playback::stop));
        } else {
            builder.addAction(action(LocaleController.getString("MorsegramCarVoicePlay"),
                    R.drawable.ic_action_play, true, playback::playPause));
        }

        // precedente / successivo: solo icone (un'action strip accetta al massimo un titolo personalizzato) e
        // SEMPRE le stesse, senza setEnabled che segue la posizione in coda: un avanzamento non deve far contare
        // all'host un template nuovo. Fuori coda previous()/next() sono no-op, quindi un tap di troppo non fa nulla
        builder.setActionStrip(new ActionStrip.Builder()
                .addAction(new Action.Builder()
                        .setIcon(icon(R.drawable.ic_action_previous))
                        .setOnClickListener(playback::previous)
                        .build())
                .addAction(new Action.Builder()
                        .setIcon(icon(R.drawable.ic_action_next))
                        .setOnClickListener(playback::next)
                        .build())
                .build());
        return builder.build();
    }

    /**
     * Riga di stato <b>costante per stato</b>: "In riproduzione" (scarico compreso), "In pausa", "Vocali finiti".
     *
     * <p>Niente cronometro e niente contatore "2/5". L'host conta 5 template per task e considera refresh
     * gratuito solo un {@link MessageTemplate} con titolo e messaggio <b>identici</b> al precedente (stessa
     * regola documentata in {@link RecordScreen}). Un trascorso che scorre — o un contatore che cambia a ogni
     * vocale della coda — sarebbe un template nuovo ogni volta: con questa schermata a profondità 3
     * (home -> conversazione -> ascolto) la quota finirebbe dopo pochi secondi di ascolto e l'host chiuderebbe
     * il task mentre si guida. Durata e posizione restano sulla riga della chat, prima di entrare qui.</p>
     *
     * <p>Per lo stesso motivo lo scarico non ha una riga sua: l'attesa del file e la riproduzione mostrano lo
     * stesso template, così l'unico cambio automatico che resta in tutta la coda è "finito".</p>
     */
    @NonNull
    private String statusLine(int state) {
        switch (state) {
            case CarVoicePlayback.STATE_DOWNLOADING:
            case CarVoicePlayback.STATE_PLAYING:
                return LocaleController.getString("MorsegramCarVoicePlaying");
            case CarVoicePlayback.STATE_PAUSED:
                return LocaleController.getString("MorsegramCarVoicePaused");
            case CarVoicePlayback.STATE_FINISHED:
                return LocaleController.getString("MorsegramCarVoiceDone");
            default:
                return LocaleController.getString("MorsegramCarVoiceNote");
        }
    }

    @NonNull
    private Action action(@NonNull String title, int iconRes, boolean primary, @NonNull Runnable onClick) {
        final Action.Builder builder = new Action.Builder()
                .setTitle(title)
                .setIcon(icon(iconRes))
                .setOnClickListener(onClick::run);
        if (primary) {
            builder.setFlags(Action.FLAG_PRIMARY);
        }
        return builder.build();
    }

    @NonNull
    private CarIcon icon(int resId) {
        return new CarIcon.Builder(IconCompat.createWithResource(getCarContext(), resId)).build();
    }
}

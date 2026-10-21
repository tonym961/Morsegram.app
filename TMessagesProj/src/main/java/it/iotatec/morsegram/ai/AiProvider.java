/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import org.telegram.messenger.LocaleController;

import java.util.concurrent.Future;

import it.iotatec.morsegram.MorsegramGates;

/**
 * Fornitore dell'assistente: chi trasforma un {@link AiPayload} in testo.
 *
 * <p>Due famiglie, non tre: i fornitori di RETE ({@link GroqProvider}, {@link GeminiProvider}) condividono la
 * macchina HTTP, che sta tutta in {@link HttpAiProvider}; il fornitore SUL DISPOSITIVO
 * ({@link OnDeviceProvider}) non ha endpoint, intestazioni, corpo JSON né chiave, quindi non eredita niente di
 * quella macchina. Qui resta solo ciò che vale per tutti: chi sei ({@link #id()}, {@link #label()},
 * {@link #model()}), se giri sul telefono ({@link #local()}) e come si fa un lavoro ({@link #complete}).</p>
 *
 * <p>{@link #local()} non è un dettaglio di implementazione ma la discriminante di tutto il package: decide se
 * serve una chiave, se il payload va oscurato, se l'anteprima ha un destinatario da nominare e se il ripiego
 * automatico è lecito (vedi {@link AiClient}).</p>
 *
 * <p>Tutti i metodi di questa classe girano su thread di fondo (vedi {@link AiClient}): non toccano la UI.</p>
 */
public abstract class AiProvider {

    public static final int GROQ = 0;
    public static final int GEMINI = 1;
    /**
     * Il modello sul dispositivo: ML Kit Prompt API su Gemini Nano via AICore. Nessuna chiave, nessun account,
     * nessun destinatario esterno. Dietro il gate di compilazione {@link MorsegramGates#LOCAL_AI}: con il gate
     * spento questa costante resta ma non esiste nessun fornitore che la serva, e R8 elimina la libreria.
     */
    public static final int ON_DEVICE = 2;

    /** Nessun fornitore: valore del ripiego quando l'utente non ne vuole uno. */
    public static final int NONE = -1;

    /** Annullamento cooperativo: {@link AiClient.Call} lo implementa. */
    public interface Abort {
        boolean isAborted();

        /**
         * Registra il lavoro vivo, così l'annullamento lo ferma subito invece di aspettare che finisca. Per la
         * rete il lavoro è una connessione (vedi {@link HttpAiProvider.HttpAbort}), sul dispositivo è il future
         * dell'inferenza.
         */
        void attach(Future<?> work);
    }

    /**
     * Avanzamento di un lavoro spezzato in blocchi. Serve al modello sul dispositivo, dove un arretrato lungo
     * diventa più inferenze in fila e l'attesa si misura in decine di secondi: uno spinner muto, lì, è un
     * difetto. I fornitori di rete dichiarano un blocco solo.
     */
    public interface Progress {
        /**
         * @param step    blocco in corso, da 1
         * @param total   quanti blocchi in tutto
         * @param partial testo arrivato finora (streaming), vuoto se non c'è ancora niente da mostrare
         */
        void onStep(int step, int total, String partial);
    }

    public abstract int id();

    /** Nome mostrato all'utente. */
    public abstract String label();

    /**
     * Modello usato dalle richieste. Per i fornitori di rete è una costante (i nomi dei modelli vengono ritirati
     * ogni pochi mesi), sul dispositivo è il nome vero letto da AICore.
     */
    public abstract String model();

    /** Vero se l'elaborazione avviene su questo telefono: niente esce, nessuna chiave serve. */
    public boolean local() {
        return false;
    }

    /**
     * Un lavoro sincrono. Ogni esito diventa un {@link AiException} classificato, così la politica di ripiego di
     * {@link AiClient} non deve interpretare testi di errore.
     *
     * @param progress può essere null
     */
    public abstract String complete(AiPayload payload, Abort abort, Progress progress) throws AiException;

    /** Il modello scelto dall'utente se c'e', altrimenti quello predefinito del fornitore. */
    protected static String pick(String chosen, String fallback) {
        return chosen == null || chosen.trim().isEmpty() ? fallback : chosen.trim();
    }

    public static AiProvider of(int id) {
        if (id == GEMINI) {
            return new GeminiProvider();
        }
        if (id == GROQ) {
            return new GroqProvider();
        }
        if (MorsegramGates.LOCAL_AI && id == ON_DEVICE) {
            // il gate è una costante di compilazione: nell'edizione senza modello locale questo ramo non esiste,
            // OnDeviceProvider non è più raggiungibile e R8 butta via anche la libreria ML Kit
            return new OnDeviceProvider();
        }
        return null;
    }

    /** Vero per il fornitore sul dispositivo, e solo nell'edizione che lo contiene. */
    public static boolean isLocal(int id) {
        return MorsegramGates.LOCAL_AI && id == ON_DEVICE;
    }

    public static String labelOf(int id) {
        if (id == ON_DEVICE) {
            // tradotta, al contrario di Groq e Gemini: non è un marchio, è il posto dove gira il modello, e il
            // posto è tutto il senso di questo fornitore
            return MorsegramGates.LOCAL_AI ? LocaleController.getString("MorsegramAiOnDevice") : "";
        }
        final AiProvider p = of(id);
        return p != null ? p.label() : "";
    }
}

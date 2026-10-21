/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import org.telegram.messenger.LocaleController;

import it.iotatec.morsegram.MorsegramGates;
import it.iotatec.morsegram.MorsegramPrefs;

/**
 * Impostazioni dell'assistente che NON sono segreti: interruttore generale e scelta del fornitore principale e di
 * ripiego. Stanno in {@code MorsegramPrefs.global()}; le chiavi API stanno cifrate in {@link AiKeys}.
 *
 * <p><b>Default: il modello sul dispositivo.</b> Su un dispositivo che lo sa fare, il fornitore predefinito è
 * {@link AiProvider#ON_DEVICE} e l'interruttore generale nasce ACCESO: non c'è nessun destinatario da nominare,
 * nessuna chiave da chiedere e niente che esca dal telefono, quindi la ragione per cui l'assistente nasceva
 * spento non esiste più. Dove il modello non c'è, il predefinito resta Groq e l'interruttore resta spento: finché
 * l'utente non lo accende e non accetta l'anteprima su una chat, nessun testo esce dal dispositivo.</p>
 *
 * <p>Lo stato del dispositivo si legge PIGRAMENTE ({@link OnDeviceModel#status()}: una chiamata binder verso
 * AICore, su coda di fondo, mai durante l'avvio a freddo). Finché la sonda non è tornata il predefinito è quello
 * prudente; quando torna, gli ascoltatori di {@link AiGate} ridisegnano la UI.</p>
 *
 * <p>Cambiare fornitore cambia il DESTINATARIO del testo, quindi {@link #setPrimary} e {@link #setFallback}
 * dimenticano le accettazioni dell'anteprima ({@link AiGate#forgetPreviews()}): l'anteprima nomina i destinatari,
 * e un sì dato per Groq non è un sì per Google — né un sì dato quando il lavoro restava sul telefono.</p>
 */
public final class AiSettings {

    private AiSettings() {
    }

    /* ==================================================================================== fornitore */

    /** Vero se il fornitore principale lavora sul dispositivo: niente esce, nessuna chiave serve. */
    public static boolean primaryIsLocal() {
        return AiProvider.isLocal(primary());
    }

    /** Vero se il modello sul dispositivo è pronto adesso. Non blocca. */
    public static boolean localReady() {
        return MorsegramGates.LOCAL_AI && OnDeviceModel.ready();
    }

    /** Vero se questo telefono sa fare inferenza locale (modello pronto o scaricabile). Non blocca. */
    public static boolean localSupported() {
        return MorsegramGates.LOCAL_AI && OnDeviceModel.supported();
    }

    /**
     * Facciata del modello sul dispositivo. Esiste per una ragione di COMPILAZIONE, non di stile: ogni accesso a
     * {@link OnDeviceModel} deve stare dietro la costante {@link MorsegramGates#LOCAL_AI}, altrimenti R8 non può
     * dimostrare che la classe è irraggiungibile e l'edizione senza modello locale si porta dietro la libreria
     * ML Kit (178 KB invece di 11). Chi sta fuori da questo package chiama questi metodi, non OnDeviceModel.
     */
    public static boolean localShown() {
        return MorsegramGates.LOCAL_AI;
    }

    /** La sonda del dispositivo non è ancora tornata: non si sa, e non si dice niente di definitivo. */
    public static boolean localChecking() {
        return MorsegramGates.LOCAL_AI && OnDeviceModel.status() == OnDeviceModel.STATUS_UNKNOWN;
    }

    public static String localStatusText() {
        return MorsegramGates.LOCAL_AI ? OnDeviceModel.statusText() : "";
    }

    public static boolean localNeedsDownload() {
        return MorsegramGates.LOCAL_AI && OnDeviceModel.needsDownload();
    }

    public static boolean localDownloading() {
        return MorsegramGates.LOCAL_AI && OnDeviceModel.isDownloading();
    }

    public static void localDownload(OnDeviceModel.DownloadListener listener) {
        if (MorsegramGates.LOCAL_AI) {
            OnDeviceModel.download(listener);
        }
    }

    /** Lo scaricamento continua, ma chi lo ha chiesto non resta trattenuto: vedi {@link OnDeviceModel#forgetDownloadListener}. */
    public static void localForgetDownload(OnDeviceModel.DownloadListener listener) {
        if (MorsegramGates.LOCAL_AI) {
            OnDeviceModel.forgetDownloadListener(listener);
        }
    }

    public static void localClearCaches(Runnable done) {
        if (MorsegramGates.LOCAL_AI) {
            OnDeviceModel.clearCaches(done);
        } else if (done != null) {
            done.run();
        }
    }

    /* ================================================================================ interruttore */

    /**
     * Interruttore generale. Finché l'utente non lo tocca il valore è calcolato: acceso se il modello sul
     * dispositivo è pronto, spento altrimenti. Appena lo tocca, vale la sua scelta per sempre.
     *
     * <p><b>Il default calcolato fa partire la sonda, e va bene così.</b> {@link #localReady()} chiama
     * {@code OnDeviceModel.status()}, che alla prima lettura avvia {@code probe()}: siccome questo metodo è letto
     * da {@code AiGate.isEnabled}, cioè all'apertura di ogni chat, nell'edizione sideload la prima chat aperta fa
     * una chiamata binder verso {@code com.google.android.aicore}. È stato valutato (review 0.1.4) se leggere
     * invece uno stato "già noto" e sondare solo dalle impostazioni: si è deciso di NO. La sonda è
     * {@code checkStatus()}, non porta nessun contenuto fuori dal processo, costa una volta per avvio
     * ({@code probing} + stato in cache) e gira su {@code globalQueue}; mentre senza di essa l'assistente
     * risulterebbe SPENTO su un telefono che lo sa fare, e la voce "Assistente" nel menu della chat (che non è
     * condizionata all'interruttore) risponderebbe "è spento, accendilo nelle impostazioni" a chi non ha niente
     * da accendere. Si preferisce una chiamata locale a un servizio di sistema a una funzione che si nasconde.</p>
     */
    public static boolean enabled() {
        if (MorsegramPrefs.global().contains(MorsegramPrefs.KEY_AI_ENABLED)) {
            return MorsegramPrefs.global().getBoolean(MorsegramPrefs.KEY_AI_ENABLED, false);
        }
        return localReady();
    }

    public static void setEnabled(boolean on) {
        MorsegramPrefs.global().edit().putBoolean(MorsegramPrefs.KEY_AI_ENABLED, on).apply();
        AiGate.notifyChanged();
    }

    public static int primary() {
        final int id = MorsegramPrefs.global().getInt(MorsegramPrefs.KEY_AI_PRIMARY, defaultPrimary());
        return normalize(id);
    }

    /**
     * Il predefinito su questo telefono: il modello locale dove c'è (anche se va ancora scaricato — lo si spiega
     * e lo si mostra, non si passa di nascosto alla rete), Groq dove non c'è.
     */
    private static int defaultPrimary() {
        return localSupported() ? AiProvider.ON_DEVICE : AiProvider.GROQ;
    }

    /** Un valore salvato da un'edizione con il modello locale non deve restare attivo in una che non lo ha. */
    private static int normalize(int id) {
        if (id == AiProvider.ON_DEVICE) {
            return MorsegramGates.LOCAL_AI ? AiProvider.ON_DEVICE : AiProvider.GROQ;
        }
        return id == AiProvider.GEMINI ? AiProvider.GEMINI : AiProvider.GROQ;
    }

    public static void setPrimary(int providerId) {
        final int next = normalize(providerId);
        final boolean changed = next != primary();
        MorsegramPrefs.global().edit().putInt(MorsegramPrefs.KEY_AI_PRIMARY, next).apply();
        if (changed) {
            AiGate.forgetPreviews();
            AiGate.notifyChanged();
        }
    }

    /** Fornitore di ripiego, o {@link AiProvider#NONE}. */
    public static int fallback() {
        final int id = MorsegramPrefs.global().getInt(MorsegramPrefs.KEY_AI_FALLBACK, AiProvider.NONE);
        if (id == AiProvider.ON_DEVICE) {
            return MorsegramGates.LOCAL_AI && id != primary() ? AiProvider.ON_DEVICE : AiProvider.NONE;
        }
        if (id != AiProvider.GROQ && id != AiProvider.GEMINI) {
            return AiProvider.NONE;
        }
        return id == primary() ? AiProvider.NONE : id;
    }

    public static void setFallback(int providerId) {
        final boolean changed = providerId != fallback();
        MorsegramPrefs.global().edit().putInt(MorsegramPrefs.KEY_AI_FALLBACK, providerId).apply();
        if (changed) {
            AiGate.forgetPreviews();
        }
    }

    /**
     * Modello scelto dall'utente per un fornitore, vuoto = quello predefinito.
     *
     * <p>I fornitori ritirano i modelli ogni pochi mesi: Groq ha spento
     * llama-3.3-70b-versatile il 16/08/2026 e Google gemini-2.0-flash il 01/06/2026. Con il nome
     * cablato nel codice l'assistente smette di funzionare e serve una nuova versione dell'app;
     * lasciandolo cambiare dalle impostazioni, l'utente se la cava da solo il giorno stesso.
     * Sul dispositivo non si sceglie niente: il modello è quello che AICore ha installato.
     */
    public static String model(int providerId) {
        return MorsegramPrefs.global().getString("ai.model." + providerId, "");
    }

    public static void setModel(int providerId, String model) {
        final String v = model == null ? "" : model.trim();
        MorsegramPrefs.global().edit().putString("ai.model." + providerId, v).apply();
    }

    /** Se c'è almeno una chiave salvata: senza chiavi i fornitori di rete non possono fare nulla. */
    public static boolean hasAnyKey() {
        return AiKeys.hasKey(AiProvider.GROQ) || AiKeys.hasKey(AiProvider.GEMINI);
    }

    /** Pronto all'uso: interruttore acceso e fornitore principale utilizzabile davvero. */
    public static boolean ready() {
        if (!enabled()) {
            return false;
        }
        return primaryIsLocal() ? localReady() : AiKeys.hasKey(primary());
    }

    /**
     * Un fornitore di rete con la chiave salvata, per il pulsante "fai questo lavoro con ..." della finestra di
     * errore del modello locale. {@link AiProvider#NONE} se non ce n'è nessuno: allora si aprono le impostazioni.
     */
    public static int remoteWithKey() {
        final int fallbackId = fallback();
        if (!AiProvider.isLocal(fallbackId) && fallbackId != AiProvider.NONE && AiKeys.hasKey(fallbackId)) {
            return fallbackId;
        }
        final int primaryId = primary();
        if (!AiProvider.isLocal(primaryId) && AiKeys.hasKey(primaryId)) {
            return primaryId;
        }
        if (AiKeys.hasKey(AiProvider.GROQ)) {
            return AiProvider.GROQ;
        }
        if (AiKeys.hasKey(AiProvider.GEMINI)) {
            return AiProvider.GEMINI;
        }
        return AiProvider.NONE;
    }

    /* ======================================================================================= righe */

    /** Etichette per la scelta del principale: l'indice è l'id del fornitore. */
    public static String[] providerLabels() {
        if (MorsegramGates.LOCAL_AI) {
            return new String[]{
                    AiProvider.labelOf(AiProvider.GROQ),
                    AiProvider.labelOf(AiProvider.GEMINI),
                    AiProvider.labelOf(AiProvider.ON_DEVICE)
            };
        }
        return new String[]{AiProvider.labelOf(AiProvider.GROQ), AiProvider.labelOf(AiProvider.GEMINI)};
    }

    /** Etichette per la scelta del ripiego: la prima voce è "nessuno". */
    public static String[] fallbackLabels() {
        if (MorsegramGates.LOCAL_AI) {
            return new String[]{
                    LocaleController.getString("MorsegramAiFallbackNone"),
                    AiProvider.labelOf(AiProvider.GROQ),
                    AiProvider.labelOf(AiProvider.GEMINI),
                    AiProvider.labelOf(AiProvider.ON_DEVICE)
            };
        }
        return new String[]{
                LocaleController.getString("MorsegramAiFallbackNone"),
                AiProvider.labelOf(AiProvider.GROQ),
                AiProvider.labelOf(AiProvider.GEMINI)
        };
    }

    public static int fallbackIndex() {
        final int id = fallback();
        if (id == AiProvider.GROQ) {
            return 1;
        }
        if (id == AiProvider.GEMINI) {
            return 2;
        }
        return AiProvider.isLocal(id) ? 3 : 0;
    }

    public static int fallbackIdForIndex(int index) {
        if (index == 1) {
            return AiProvider.GROQ;
        }
        if (index == 2) {
            return AiProvider.GEMINI;
        }
        return index == 3 && MorsegramGates.LOCAL_AI ? AiProvider.ON_DEVICE : AiProvider.NONE;
    }

    /** Valore della riga di una chiave: mai la chiave, solo se c'è. */
    public static String keyStatus(int providerId) {
        return LocaleController.getString(AiKeys.hasKey(providerId) ? "MorsegramAiKeySet" : "MorsegramAiKeyNotSet");
    }

    /* ============================================================================ chi lavora, e perché */

    /** Chi sta lavorando adesso, in due parole, per la riga delle impostazioni. */
    public static String activeProviderLabel() {
        return AiProvider.labelOf(primary());
    }

    /**
     * Chi lavora e PERCHÉ, in una frase: è la riga che l'utente deve poter leggere senza aprire niente. Con il
     * modello sul dispositivo dice anche la cosa che conta — che non esce nulla dal telefono.
     */
    public static String activeProviderExplanation() {
        if (primaryIsLocal()) {
            if (localReady()) {
                return LocaleController.getString("MorsegramAiActiveLocal");
            }
            if (MorsegramGates.LOCAL_AI && OnDeviceModel.needsDownload()) {
                return LocaleController.getString("MorsegramAiActiveLocalPending");
            }
            return LocaleController.getString("MorsegramAiActiveLocalMissing");
        }
        final String label = AiProvider.labelOf(primary());
        if (!MorsegramGates.LOCAL_AI) {
            return LocaleController.formatString("MorsegramAiActiveRemote", 0, label);
        }
        if (localReady()) {
            return LocaleController.formatString("MorsegramAiActiveRemoteLocalReady", 0, label);
        }
        return LocaleController.formatString("MorsegramAiActiveRemote", 0, label);
    }
}

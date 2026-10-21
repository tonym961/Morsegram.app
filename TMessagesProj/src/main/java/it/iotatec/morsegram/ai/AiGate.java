/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;

import java.util.ArrayList;
import java.util.Arrays;

import it.iotatec.morsegram.MorsegramPrefs;
import it.iotatec.morsegram.hidden.HiddenChats;

/**
 * Chi può usare l'assistente, e su quale chat. Tutte le quattro funzioni passano da qui.
 *
 * <p>Le barriere non sono le stesse per i due modi di lavorare, perché non difendono dalla stessa cosa:</p>
 * <ol>
 * <li><b>Interruttore generale</b> ({@link AiSettings#enabled()}): spento di default quando il testo uscirebbe
 *     dal telefono, acceso di default quando il modello sul dispositivo è pronto.</li>
 * <li><b>Accensione per singola chat</b> ({@link #enable}): serve a limitare CIÒ CHE ESCE, quindi vale per i
 *     fornitori di rete. Con il modello sul dispositivo non ha più una ragione — non c'è niente da limitare — e
 *     l'assistente è disponibile su ogni chat non nascosta.</li>
 * <li><b>Mai su una chat nascosta</b> ({@link HiddenChats#isHidden}): questa RESTA in entrambi i modi, su OGNI
 *     lettura ({@link #isEnabled}) e non solo sull'accensione. Non è soltanto privacy di rete ma disciplina
 *     dell'utente: una chat messa dietro il PIN non si riassume, e la prefix caching del modello locale scrive
 *     comunque file di cache nello storage privato dell'app.</li>
 * </ol>
 *
 * <p>E una garanzia in più per la rete: {@link #previewRequired} è vero finché l'utente non ha visto, alla prima
 * attivazione su quella chat, il testo esatto che uscirebbe dal dispositivo. Con il modello locale non c'è nessuna
 * anteprima, perché non c'è nessun destinatario da nominare: è il senso della finestra, non un adempimento.</p>
 */
public final class AiGate {

    /** Avvisato quando l'assistente si accende o si spegne: serve al segno nella barra della chat. */
    public interface Listener {
        void onAiStateChanged();
    }

    private static final ArrayList<Listener> listeners = new ArrayList<>();

    private AiGate() {
    }

    /* ==================================================================================== letture */

    /**
     * Se l'assistente è utilizzabile adesso su questa chat: interruttore generale acceso, chat NON nascosta e —
     * solo per i fornitori di rete — chat accesa. Non controlla la presenza della chiave API né lo stato del
     * modello locale: quelli sono errori da mostrare, non motivi per far sparire la voce di menu (l'utente deve
     * capire che gli manca una chiave, o che il modello va scaricato).
     */
    public static boolean isEnabled(int account, long dialogId) {
        if (!AiSettings.enabled() || dialogId == 0) {
            return false;
        }
        if (HiddenChats.isHidden(account, dialogId)) {
            return false;
        }
        if (AiSettings.primaryIsLocal()) {
            return true;
        }
        return contains(account, MorsegramPrefs.KEY_AI_CHATS, dialogId);
    }

    /** Se questa chat ha bisogno di essere accesa a mano: con il modello sul dispositivo, no. */
    public static boolean needsChatActivation(int account, long dialogId) {
        return !AiSettings.primaryIsLocal() && !contains(account, MorsegramPrefs.KEY_AI_CHATS, dialogId);
    }

    /** Se prima di partire va mostrata l'anteprima: solo quando il testo esce davvero dal dispositivo. */
    public static boolean previewRequired(int account, long dialogId) {
        return !AiSettings.primaryIsLocal() && !previewAccepted(account, dialogId);
    }

    /** Se l'utente può accendere l'assistente su questa chat. Una chat nascosta non è mai attivabile. */
    public static boolean canEnable(int account, long dialogId) {
        return dialogId != 0 && !HiddenChats.isHidden(account, dialogId);
    }

    /** Se su questa chat l'anteprima è già stata mostrata e accettata. */
    public static boolean previewAccepted(int account, long dialogId) {
        return contains(account, MorsegramPrefs.KEY_AI_PREVIEWED, dialogId);
    }

    /** Quante chat hanno l'assistente attivo (riga di riepilogo nelle impostazioni). */
    public static int activeCount(int account) {
        final long[] ids = read(account, MorsegramPrefs.KEY_AI_CHATS);
        int n = 0;
        for (long id : ids) {
            if (!HiddenChats.isHidden(account, id)) {
                n++;
            }
        }
        return n;
    }

    /* ==================================================================================== scritture */

    /** Accende l'assistente sulla chat. Falso (e nessuna scrittura) se la chat è nascosta. */
    public static boolean enable(int account, long dialogId) {
        if (!canEnable(account, dialogId)) {
            return false;
        }
        add(account, MorsegramPrefs.KEY_AI_CHATS, dialogId);
        notifyChanged();
        return true;
    }

    /**
     * Spegne l'assistente sulla chat. L'accettazione dell'anteprima NON viene dimenticata: l'utente ha già visto
     * che forma ha il testo inviato, e riaccendere non deve sembrare un adempimento burocratico. Resta però
     * un'anteprima per chat nuova.
     */
    public static void disable(int account, long dialogId) {
        remove(account, MorsegramPrefs.KEY_AI_CHATS, dialogId);
        notifyChanged();
    }

    public static void markPreviewAccepted(int account, long dialogId) {
        add(account, MorsegramPrefs.KEY_AI_PREVIEWED, dialogId);
    }

    /**
     * Dimentica le accettazioni dell'anteprima su TUTTI gli slot, lasciando accese le chat. Serve quando cambia
     * chi riceve il testo (fornitore principale o di ripiego): l'anteprima li nomina, quindi un consenso dato per
     * un destinatario non vale per un altro e va richiesto di nuovo alla prima funzione usata.
     */
    public static void forgetPreviews() {
        for (int account = 0; account < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; account++) {
            try {
                MorsegramPrefs.account(account).edit().remove(MorsegramPrefs.KEY_AI_PREVIEWED).apply();
            } catch (Throwable ignore) {
            }
        }
    }

    /** Dimentica tutto: usata quando l'utente spegne l'interruttore generale o cancella le chiavi. */
    public static void forgetAll(int account) {
        MorsegramPrefs.account(account).edit()
                .remove(MorsegramPrefs.KEY_AI_CHATS)
                .remove(MorsegramPrefs.KEY_AI_PREVIEWED)
                .apply();
        notifyChanged();
    }

    /* ================================================================================== osservatori */

    public static void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Sul thread UI: i soli ascoltatori sono viste.
     *
     * <p>Si itera una COPIA e non la lista viva: un ascoltatore si toglie da dentro la propria callback
     * (l'attesa della sonda in {@code AiSheet.start}, il segno nella barra quando la vista si stacca), e con
     * l'indice sulla lista viva il successivo scalerebbe di una posizione e non verrebbe mai chiamato — la riga
     * "Modello sul telefono" resterebbe su "controllo in corso" fino al prossimo cambio di stato.</p>
     */
    public static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            final ArrayList<Listener> snapshot = new ArrayList<>(listeners);
            for (int i = 0; i < snapshot.size(); i++) {
                try {
                    snapshot.get(i).onAiStateChanged();
                } catch (Throwable ignore) {
                }
            }
        });
    }

    /* ========================================================================================= CSV */

    private static long[] read(int account, String key) {
        final String csv = MorsegramPrefs.account(account).getString(key, "");
        if (csv == null || csv.isEmpty()) {
            return new long[0];
        }
        final String[] parts = csv.split(",");
        final long[] tmp = new long[parts.length];
        int n = 0;
        for (String part : parts) {
            try {
                final long id = Long.parseLong(part.trim());
                if (id != 0) {
                    tmp[n++] = id;
                }
            } catch (NumberFormatException ignore) {
            }
        }
        final long[] out = Arrays.copyOf(tmp, n);
        Arrays.sort(out);
        return out;
    }

    private static boolean contains(int account, String key, long dialogId) {
        final long[] ids = read(account, key);
        return ids.length > 0 && Arrays.binarySearch(ids, dialogId) >= 0;
    }

    private static void add(int account, String key, long dialogId) {
        final long[] ids = read(account, key);
        if (ids.length > 0 && Arrays.binarySearch(ids, dialogId) >= 0) {
            return;
        }
        final long[] next = Arrays.copyOf(ids, ids.length + 1);
        next[ids.length] = dialogId;
        Arrays.sort(next);
        write(account, key, next);
    }

    private static void remove(int account, String key, long dialogId) {
        final long[] ids = read(account, key);
        final int at = ids.length > 0 ? Arrays.binarySearch(ids, dialogId) : -1;
        if (at < 0) {
            return;
        }
        final long[] next = new long[ids.length - 1];
        System.arraycopy(ids, 0, next, 0, at);
        System.arraycopy(ids, at + 1, next, at, ids.length - at - 1);
        write(account, key, next);
    }

    private static void write(int account, String key, long[] ids) {
        final StringBuilder sb = new StringBuilder(ids.length * 12);
        for (long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        final SharedPreferences.Editor editor = MorsegramPrefs.account(account).edit();
        if (sb.length() == 0) {
            editor.remove(key);
        } else {
            editor.putString(key, sb.toString());
        }
        editor.apply();
    }
}

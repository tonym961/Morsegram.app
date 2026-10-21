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
 * <p>Tre barriere, in quest'ordine:</p>
 * <ol>
 * <li><b>Interruttore generale spento di default</b> ({@link AiSettings#enabled()}): a installazione fresca
 *     l'assistente non esiste.</li>
 * <li><b>Accensione per singola chat</b> ({@link #enable}): accendere l'interruttore generale non accende
 *     l'assistente da nessuna parte. L'utente lo attiva chat per chat, e finché è attivo su una chat lo vede
 *     (il segno nella barra della chat, {@link AiEntry}).</li>
 * <li><b>Mai su una chat nascosta</b> ({@link HiddenChats#isHidden}): una chat che l'utente ha scelto di
 *     nascondere dietro il PIN non manda il suo testo a un servizio esterno, e il controllo è su OGNI lettura
 *     ({@link #isEnabled}) non solo sull'accensione — se una chat già attiva viene nascosta dopo, l'assistente si
 *     spegne da sé su quella chat.</li>
 * </ol>
 *
 * <p>E una garanzia in più: {@link #previewAccepted} è falso finché l'utente non ha visto, alla prima attivazione
 * su quella chat, il testo esatto che uscirebbe dal dispositivo.</p>
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
     * Se l'assistente è utilizzabile adesso su questa chat: interruttore generale acceso, chat accesa, chat NON
     * nascosta. Non controlla la presenza della chiave API (quello è un errore da mostrare, non un motivo per far
     * sparire la voce di menu: l'utente deve capire che gli manca la chiave).
     */
    public static boolean isEnabled(int account, long dialogId) {
        if (!AiSettings.enabled() || dialogId == 0) {
            return false;
        }
        if (HiddenChats.isHidden(account, dialogId)) {
            return false;
        }
        return contains(account, MorsegramPrefs.KEY_AI_CHATS, dialogId);
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

    /** Sul thread UI: i soli ascoltatori sono viste. */
    public static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> {
            for (int i = 0; i < listeners.size(); i++) {
                try {
                    listeners.get(i).onAiStateChanged();
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

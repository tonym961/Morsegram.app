/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.notify;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

import it.iotatec.morsegram.MorsegramPrefs;
import it.iotatec.morsegram.hidden.HiddenChats;

/**
 * Avvisi per categoria: "questa chat e' silenziosa, ma se mi nominano o mi rispondono voglio saperlo".
 *
 * <p><b>Quello che upstream fa già.</b> Telegram fa passare le menzioni attraverso il silenzio di una chat: in
 * {@code NotificationsController}, quando {@code messageOwner.mentioned} è vero, il dialogo su cui si leggono
 * {@code notify2_}/{@code notifyuntil_} viene rimappato sul MITTENTE (4 punti: processNewMessages, le due
 * ricostruzioni al riavvio, showOrUpdateNotification), il muto della chat non viene nemmeno consultato, e il
 * contatore {@code pushDialogsOverrideMention} riapre il cancello anche a valle. Quindi la notifica COMPARE già.
 * Quello che manca, e che sta qui, è (a) un interruttore per utente e per chat e (b) la garanzia che l'avviso
 * SUONI: con più di una chat in push la notifica per-chat finisce sul canale muto "other" con
 * {@code GROUP_ALERT_SUMMARY}, e il suono dipende dal canale dell'ULTIMO messaggio, non da quello della menzione.
 *
 * <p><b>Una categoria, non due.</b> Menzione e risposta a un mio messaggio sono lo STESSO bit:
 * {@code TLRPC.Message.mentioned} (dalla push è {@code custom.mention}, un bit solo; il payload non porta né
 * {@code reply_to} né le entities, e {@code replyMessageObject} nel percorso notifiche è sempre null). Separarle
 * sarebbe una promessa falsa, quindi la categoria è una: "menzioni e risposte", esattamente la frase del
 * committente. I <b>messaggi fissati</b> si distinguono ({@code TL_messageActionPinMessage}) ma hanno già il loro
 * interruttore in Telegram ({@code PinnedMessages}, schermata Notifiche): questa classe li lascia a upstream e la
 * pagina Morsegram si limita a rimandare là.
 *
 * <p><b>Tre livelli</b> ({@link #MODE_DEFAULT}, {@link #MODE_ALWAYS}, {@link #MODE_CHAT}), globali e con
 * eccezione per singola chat. Il predefinito non cambia NULLA: {@link #allowMention} restituisce esattamente
 * {@code messageOwner.mentioned}, bit per bit come upstream. Non esiste un livello "mai": una menzione è un
 * messaggio vero e in una chat che suona arriva con la chat; spegnerla vorrebbe dire perdere una notifica, che è
 * il difetto che questa funzione esiste per evitare. Il terzo livello è {@link #MODE_CHAT}, "segui la chat":
 * annulla la rimappatura di upstream, cioè in una chat silenziata la menzione non compare.
 *
 * <p><b>Il silenzioso di Telegram non viene toccato</b>: nessuna {@code muteDialog}, nessuna scrittura di
 * {@code notify2_}/{@code notifyuntil_}, nessuna {@code updateServerNotificationsSettings}. Si legge soltanto
 * {@link MessagesController#isDialogMuted(long, long)} e si decide su preferenze nostre: filtro locale come le
 * chat nascoste, il muto resta quello di Telegram su tutti i dispositivi (ToS 1.4).
 *
 * <p><b>Canale Android a mano</b>, come {@link it.iotatec.morsegram.AirplaneMode}: MAI da
 * {@code validateChannelId}, che ha una sincronizzazione INVERSA (legge l'importanza scelta dall'utente e, se
 * azzerata, scrive {@code notify2_} e la manda al server, silenziando la chat su tutti i dispositivi). L'id ha
 * UN SOLO underscore: {@code NotificationsDisabledReceiver} spezza l'id del canale bloccato su '_' e con tre
 * pezzi lo tratta come roba di Telegram, arrivando a scrivere {@code notify2_} e a sincronizzare col server.
 *
 * <p>Letture calde: {@link #allowMention} gira sulla {@code notificationsQueue} per ogni messaggio, anche a
 * telefono addormentato, e per un messaggio normale esce alla prima riga. Lo stato per slot è un array ordinato
 * immutabile in copy-on-write, come {@link HiddenChats}: lock-free, O(log n), zero allocazioni.
 */
public final class MorsegramAlerts {

    /* ================================================================================== categorie */

    /** Niente di speciale: notifica normale della chat. */
    public static final int CATEGORY_NONE = 0;
    /** Mi hanno nominato o hanno risposto a un mio messaggio (lo stesso bit, lato server). */
    public static final int CATEGORY_MENTION = 1;
    /** Messaggio fissato: distinguibile, ma lo governa l'interruttore PinnedMessages di Telegram. */
    public static final int CATEGORY_PINNED = 2;

    /* ===================================================================================== livelli */

    /** Come Telegram: la menzione passa il silenzio della chat come fa già oggi upstream. Predefinito. */
    public static final int MODE_DEFAULT = 0;
    /** Sempre: passa il silenzio della chat E suona sul canale Morsegram, anche dove upstream tacerebbe. */
    public static final int MODE_ALWAYS = 1;
    /** Segui la chat: nessuna eccezione, in una chat silenziata la menzione non compare. */
    public static final int MODE_CHAT = 2;
    /** Solo per l'eccezione di chat: "come l'impostazione generale". */
    public static final int MODE_INHERIT = -1;

    /** Un solo underscore, per NotificationsDisabledReceiver (vedi la nota di classe). */
    public static final String CHANNEL_PREFIX = "morsegram_mentions";

    private MorsegramAlerts() {
    }

    /* ======================================================================================= stato */

    private static final class State {
        final long userId;
        /** ordinato crescente, per Arrays.binarySearch. */
        final long[] ids;
        /** parallelo a {@link #ids}. */
        final int[] modes;

        State(long userId, long[] ids, int[] modes) {
            this.userId = userId;
            this.ids = ids;
            this.modes = modes;
        }

        int mode(long dialogId) {
            if (ids.length == 0) {
                return MODE_INHERIT;
            }
            final int i = Arrays.binarySearch(ids, dialogId);
            return i >= 0 ? modes[i] : MODE_INHERIT;
        }
    }

    private static final AtomicReferenceArray<State> states = new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);
    /** -1 = non ancora letto dalle prefs. */
    private static volatile int cachedGlobal = -1;

    /** Livello generale (file globale: una scelta per telefono, non per account). */
    public static int globalMode() {
        int mode = cachedGlobal;
        if (mode < 0) {
            if (ApplicationLoader.applicationContext == null) {
                return MODE_DEFAULT; // niente contesto: nessun valore da mettere in cache
            }
            mode = normalizeMode(MorsegramPrefs.global().getInt(MorsegramPrefs.KEY_ALERTS_MENTIONS, MODE_DEFAULT));
            cachedGlobal = mode;
        }
        return mode;
    }

    public static void setGlobalMode(int mode) {
        final int value = normalizeMode(mode);
        cachedGlobal = value;
        MorsegramPrefs.global().edit().putInt(MorsegramPrefs.KEY_ALERTS_MENTIONS, value).apply();
    }

    /**
     * Dimentica il livello generale in cache: serve dopo un ripristino delle impostazioni
     * ({@code it.iotatec.morsegram.backup}), che scrive {@code alerts.mentions} da sotto questa cache.
     */
    public static void ricaricaGenerale() {
        cachedGlobal = -1;
    }

    /**
     * Dimentica le eccezioni per singola chat dello slot. Come sopra: lo stato in cache si ricarica solo quando
     * cambia l'utente loggato, quindi dopo un ripristino va buttato a mano.
     */
    public static void ricarica(int account) {
        if (account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT) {
            states.set(account, null);
        }
    }

    private static int normalizeMode(int mode) {
        return mode == MODE_ALWAYS || mode == MODE_CHAT ? mode : MODE_DEFAULT;
    }

    /**
     * Stato dello slot, ricaricato se l'utente loggato è cambiato (come {@link HiddenChats}: legge il campo
     * pubblico {@code UserConfig.clientUserId}, non il getter synchronized). userId 0 → null, niente cache.
     */
    private static State state(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return null;
        }
        final long userId = UserConfig.getInstance(account).clientUserId;
        if (userId == 0) {
            return null;
        }
        State s = states.get(account);
        if (s == null || s.userId != userId) {
            s = parse(userId, MorsegramPrefs.account(account).getString(MorsegramPrefs.KEY_ALERTS_CHATS, ""));
            states.set(account, s);
        }
        return s;
    }

    /** CSV di coppie {@code dialogId:livello}. Le voci rotte o inutili (livello ereditato) si scartano. */
    private static State parse(long userId, String csv) {
        if (csv == null || csv.isEmpty()) {
            return new State(userId, new long[0], new int[0]);
        }
        final String[] parts = csv.split(",");
        final long[] ids = new long[parts.length];
        final int[] modes = new int[parts.length];
        int n = 0;
        for (String part : parts) {
            final int sep = part.indexOf(':');
            if (sep <= 0) {
                continue;
            }
            try {
                final long id = Long.parseLong(part.substring(0, sep).trim());
                final int mode = Integer.parseInt(part.substring(sep + 1).trim());
                if (id == 0 || (mode != MODE_DEFAULT && mode != MODE_ALWAYS && mode != MODE_CHAT)) {
                    continue;
                }
                int i = 0;
                while (i < n && ids[i] < id) {
                    i++;
                }
                if (i < n && ids[i] == id) {
                    modes[i] = mode;
                    continue;
                }
                System.arraycopy(ids, i, ids, i + 1, n - i);
                System.arraycopy(modes, i, modes, i + 1, n - i);
                ids[i] = id;
                modes[i] = mode;
                n++;
            } catch (NumberFormatException ignore) {
            }
        }
        return new State(userId, Arrays.copyOf(ids, n), Arrays.copyOf(modes, n));
    }

    private static String csv(State s) {
        final StringBuilder sb = new StringBuilder(s.ids.length * 16);
        for (int i = 0; i < s.ids.length; i++) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s.ids[i]).append(':').append(s.modes[i]);
        }
        return sb.toString();
    }

    /* ==================================================================================== letture */

    /** Eccezione di questa chat, o {@link #MODE_INHERIT} se segue l'impostazione generale. */
    public static int chatMode(int account, long dialogId) {
        final State s = state(account);
        return s == null ? MODE_INHERIT : s.mode(dialogId);
    }

    /** Livello che vale per questa chat: l'eccezione se c'è, altrimenti il generale. */
    public static int mode(int account, long dialogId) {
        final int chat = chatMode(account, dialogId);
        return chat == MODE_INHERIT ? globalMode() : chat;
    }

    /** Quante chat hanno un'eccezione (riga della pagina impostazioni). */
    public static int overrides(int account) {
        final State s = state(account);
        return s == null ? 0 : s.ids.length;
    }

    /**
     * Categoria di un messaggio nel percorso notifiche. Il fissato si riconosce dall'ACTION, non dal flag
     * {@code Message.pinned} (che significa un'altra cosa: che il messaggio sta nella lista dei fissati), e va
     * controllato PRIMA della menzione, perché dalla push arriva con {@code mentioned = true} anche lui.
     */
    public static int category(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) {
            return CATEGORY_NONE;
        }
        if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionPinMessage) {
            return CATEGORY_PINNED;
        }
        return messageObject.messageOwner.mentioned ? CATEGORY_MENTION : CATEGORY_NONE;
    }

    /* ======================================================================== il predicato (riga 1) */

    /**
     * Sostituisce {@code messageObject.messageOwner.mentioned} nelle righe di rimappatura del muto: true pilota
     * tutta la catena di bypass che upstream ha già (rimappatura su {@code getFromChatId},
     * {@code pushDialogsOverrideMention} e le due scappatoie a valle), false la annulla e la menzione segue il
     * silenzio della chat.
     *
     * <p>Restituisce true in ogni caso dubbio (una notifica persa è un difetto grave e silenzioso) e lascia
     * intatti i messaggi fissati, altrimenti si cambierebbe il comportamento dell'interruttore PinnedMessages di
     * Telegram: la riga subito dopo, in upstream, salta il fissato solo quando quell'interruttore è spento, e ci
     * arriva solo se qui si risponde true.
     *
     * @param dialogId il dialogo ORIGINALE (la chat), non quello già rimappato.
     */
    public static boolean allowMention(int account, MessageObject messageObject, long dialogId) {
        if (messageObject == null || messageObject.messageOwner == null || !messageObject.messageOwner.mentioned) {
            return false; // identico a upstream, e costo zero per un messaggio normale
        }
        if (messageObject.messageOwner.action instanceof TLRPC.TL_messageActionPinMessage) {
            return true; // fissati: decide PinnedMessages di Telegram, noi non entriamo
        }
        if (mode(account, dialogId) != MODE_CHAT) {
            return true;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("morsegram: mention follows chat mute for " + dialogId);
        }
        return false;
    }

    /* ========================================================================= l'udibilità (riga 2) */

    /**
     * Sostituisce {@code setNotificationChannel} per la notifica per-chat: è l'UNICO punto in cui una notifica
     * per-chat riceve il canale. Con il livello "sempre" e una chat silenziata, la menzione va sul canale
     * Morsegram (importanza alta, suono predefinito) e riprende {@code GROUP_ALERT_ALL}, altrimenti il
     * {@code GROUP_ALERT_SUMMARY} messo poco sopra la terrebbe muta anche su un canale con suono.
     *
     * <p>Le chat nascoste sono escluse: la maschera di {@link it.iotatec.morsegram.MorsegramNotifications} gira
     * DOPO l'assegnazione del canale, quindi un avviso forte su una chat nascosta sarebbe una notifica mascherata
     * che suona e tradisce la chat (regressione di privacy).
     *
     * @return true se il canale è stato assegnato qui; false → tocca a upstream, invariato.
     */
    public static boolean channel(int account, NotificationCompat.Builder builder, List<MessageObject> messageObjects, long dialogId, long topicId) {
        if (builder == null || messageObjects == null || messageObjects.isEmpty() || Build.VERSION.SDK_INT < 26) {
            return false;
        }
        if (mode(account, dialogId) != MODE_ALWAYS || HiddenChats.isHidden(account, dialogId)) {
            return false;
        }
        boolean mention = false;
        for (int i = 0, n = messageObjects.size(); i < n; i++) {
            final MessageObject m = messageObjects.get(i);
            // silent: l'ha deciso chi scrive (invio silenzioso), non la chat. Si rispetta.
            if (category(m) == CATEGORY_MENTION && !m.messageOwner.silent && !m.isReactionPush) {
                mention = true;
                break;
            }
        }
        if (!mention) {
            return false;
        }
        try {
            // in una chat che suona già il canale di Telegram va benissimo: qui si interviene solo dove tacerebbe
            if (!MessagesController.getInstance(account).isDialogMuted(dialogId, topicId)) {
                return false;
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
        final String id = ensureChannel(account);
        if (id == null) {
            return false;
        }
        builder.setChannelId(id);
        builder.setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_ALL);
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("morsegram: mention alert on own channel for " + dialogId);
        }
        return true;
    }

    /* ====================================================================================== canale */

    /** Id del canale per lo slot: prefisso + user id, UN solo underscore. null se lo slot è vuoto. */
    public static String channelId(int account) {
        final long userId = MorsegramPrefs.userId(account);
        return userId == 0 ? null : CHANNEL_PREFIX + userId;
    }

    /**
     * Crea il canale se manca, a mano (mai {@code validateChannelId}). Chiamata dalla notificationsQueue solo
     * quando serve davvero — livello "sempre", chat silenziata, menzione in arrivo — e dalla pagina impostazioni
     * quando l'utente sceglie "sempre", così il canale compare nelle impostazioni di Android prima della prima
     * menzione e si può regolare.
     *
     * <p>Restituisce null anche quando il canale ESISTE ma l'utente lo ha AZZERATO (importanza nessuna, cioè
     * canale bloccato: due tocchi dalla notifica, pressione lunga → "Disattiva notifiche"). Android scarta del
     * tutto una notifica postata su un canale bloccato, quindi assegnarlo farebbe SPARIRE la menzione in
     * silenzio, che è il difetto che questa funzione esiste per evitare. Con null {@link #channel} esce con
     * false, {@code setNotificationChannel} di upstream rimette il canale di Telegram e l'avviso si vede
     * comunque (muto sul canale "other", o perfino udibile sul canale del mittente quando la chat è l'unica in
     * push). Nessuna chiamata binder in più: la {@code getNotificationChannel} c'era già.
     */
    public static String ensureChannel(int account) {
        if (Build.VERSION.SDK_INT < 26) {
            return null; // sotto Android 8 i canali non esistono: vedi canSound()
        }
        final Context ctx = ApplicationLoader.applicationContext;
        final String id = channelId(account);
        if (ctx == null || id == null) {
            return null;
        }
        try {
            final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            if (nm == null) {
                return null;
            }
            final NotificationChannel existing = nm.getNotificationChannel(id);
            if (existing == null) {
                final NotificationChannel channel = new NotificationChannel(id, LocaleController.getString("MorsegramAlertsChannelName"), NotificationManager.IMPORTANCE_HIGH);
                channel.setDescription(LocaleController.getString("MorsegramAlertsChannelInfo"));
                channel.enableVibration(true);
                channel.enableLights(true);
                channel.setShowBadge(true);
                channel.setSound(Settings.System.DEFAULT_NOTIFICATION_URI, new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build());
                nm.createNotificationChannel(channel);
                return id;
            }
            if (existing.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                // canale azzerato dall'utente: Android scarterebbe la notifica, meglio il canale di Telegram che
                // nessuna notifica. null → channel() esce con false e upstream riassegna il canale suo.
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("morsegram: own alert channel blocked by user, falling back to telegram channel");
                }
                return null;
            }
            return id;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Il canale esiste già nel sistema (riga informativa della pagina impostazioni). */
    public static boolean channelExists(int account) {
        if (Build.VERSION.SDK_INT < 26) {
            return false;
        }
        final Context ctx = ApplicationLoader.applicationContext;
        final String id = channelId(account);
        if (ctx == null || id == null) {
            return false;
        }
        try {
            final NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            return nm != null && nm.getNotificationChannel(id) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * Sotto Android 8 il suono vive solo sul builder del RIEPILOGO e la notifica per-chat non riceve mai un
     * setSound: là il livello "sempre" può solo far COMPARIRE la notifica, non renderla udibile. Degrado
     * dichiarato nella pagina impostazioni, non nascosto.
     */
    public static boolean canSound() {
        return Build.VERSION.SDK_INT >= 26;
    }

    /* =================================================================================== modifiche */

    /**
     * Eccezione per una chat. {@link #MODE_INHERIT} la rimuove. Thread UI (la pagina impostazioni e il menu di
     * anteprima); lo stato è sostituito in copy-on-write, quindi le letture sulla notificationsQueue non vedono
     * mai uno stato a metà.
     */
    public static void setChatMode(int account, long dialogId, int mode) {
        final State s = state(account);
        if (s == null || dialogId == 0) {
            return;
        }
        final int current = s.mode(dialogId);
        if (current == mode) {
            return;
        }
        final State next;
        if (mode == MODE_INHERIT) {
            final int i = Arrays.binarySearch(s.ids, dialogId);
            if (i < 0) {
                return;
            }
            final long[] ids = new long[s.ids.length - 1];
            final int[] modes = new int[ids.length];
            System.arraycopy(s.ids, 0, ids, 0, i);
            System.arraycopy(s.modes, 0, modes, 0, i);
            System.arraycopy(s.ids, i + 1, ids, i, ids.length - i);
            System.arraycopy(s.modes, i + 1, modes, i, modes.length - i);
            next = new State(s.userId, ids, modes);
        } else if (current != MODE_INHERIT) {
            final int i = Arrays.binarySearch(s.ids, dialogId);
            final int[] modes = s.modes.clone();
            modes[i] = normalizeMode(mode);
            next = new State(s.userId, s.ids, modes);
        } else {
            final long[] ids = new long[s.ids.length + 1];
            final int[] modes = new int[ids.length];
            int i = 0;
            while (i < s.ids.length && s.ids[i] < dialogId) {
                i++;
            }
            System.arraycopy(s.ids, 0, ids, 0, i);
            System.arraycopy(s.modes, 0, modes, 0, i);
            ids[i] = dialogId;
            modes[i] = normalizeMode(mode);
            System.arraycopy(s.ids, i, ids, i + 1, s.ids.length - i);
            System.arraycopy(s.modes, i, modes, i + 1, s.modes.length - i);
            next = new State(s.userId, ids, modes);
        }
        states.set(account, next);
        MorsegramPrefs.account(account).edit().putString(MorsegramPrefs.KEY_ALERTS_CHATS, csv(next)).apply();
        if (next.mode(dialogId) == MODE_ALWAYS) {
            ensureChannel(account);
        }
    }

    /* ==================================================================================== etichette */

    /** Etichette dei tre livelli, nell'ordine dei valori {@code MODE_*}. */
    public static String[] modeLabels() {
        return new String[]{
                LocaleController.getString("MorsegramAlertsModeDefault"),
                LocaleController.getString("MorsegramAlertsModeAlways"),
                LocaleController.getString("MorsegramAlertsModeChat"),
        };
    }

    public static String modeLabel(int mode) {
        return modeLabels()[normalizeMode(mode)];
    }

    /** Etichette per una chat: la prima è "come l'impostazione generale". */
    public static String[] chatModeLabels() {
        final String[] modes = modeLabels();
        return new String[]{
                LocaleController.formatString("MorsegramAlertsModeInherit", 0, modeLabel(globalMode())),
                modes[MODE_DEFAULT], modes[MODE_ALWAYS], modes[MODE_CHAT],
        };
    }

    public static int chatChoiceIndex(int account, long dialogId) {
        final int mode = chatMode(account, dialogId);
        return mode == MODE_INHERIT ? 0 : mode + 1;
    }

    public static int chatModeForIndex(int index) {
        return index <= 0 ? MODE_INHERIT : normalizeMode(index - 1);
    }
}

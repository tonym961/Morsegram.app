/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.widget;

import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

/**
 * Stato per widget dei widget Morsegram: file SharedPreferences "morsegram_widget", chiavi suffissate con
 * l'appWidgetId (lo stesso schema di upstream con "shortcut_widget", ma file separato perché i nostri widget
 * non hanno righe nella tabella SQLite shortcut_widget e non devono finire nelle query di
 * {@code MessagesStorage.updateWidgets}).
 * <p>
 * Oltre alla configurazione (account, chat, tema, angoli, trasparenza) tiene una CACHE di contenuto
 * (nome chat, anteprima, ora, non letti) per i widget che mostrano dati: a processo freddo il launcher chiede
 * un {@code onUpdate} prima che MessagesController abbia caricato i dialoghi, e senza cache il widget
 * lampeggerebbe vuoto. La cache viene scritta SOLO dopo avere verificato che la chat non è nascosta e viene
 * cancellata ({@link #wipeContent}) appena la verifica non è possibile o la chat diventa nascosta: una voce di
 * cache non deve mai sopravvivere a un "nascondi".
 */
public final class WidgetConfig {

    public static final String FILE = "morsegram_widget";

    /* configurazione */
    private static final String KEY_TYPE = "type";
    private static final String KEY_ACCOUNT = "account";
    private static final String KEY_DIALOG = "dialog";
    private static final String KEY_THEME = "theme";
    private static final String KEY_CORNERS = "corners";
    private static final String KEY_OPACITY = "opacity";
    /* cache di contenuto */
    private static final String KEY_NAME = "name";
    private static final String KEY_PREVIEW = "preview";
    private static final String KEY_TIME = "time";
    private static final String KEY_UNREAD = "unread";
    /* contatore globale (widget NON LETTI): non è per widget, è per account */
    private static final String KEY_UNREAD_TOTAL = "unread.total.";
    private static final String KEY_UNREAD_CHATS = "unread.chats.";

    /** tema: segue il tema dell'app (che a sua volta può seguire il sistema). */
    public static final int THEME_AUTO = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    /** angoli: arrotondati come chiede il sistema (28dp su API 31+), quadrati, molto arrotondati. */
    public static final int CORNERS_ROUND = 0;
    public static final int CORNERS_SQUARE = 1;
    public static final int CORNERS_EXTRA = 2;

    private WidgetConfig() {
    }

    public static SharedPreferences prefs() {
        return it.iotatec.morsegram.MorsegramPrefs.file(FILE);
    }

    /* ============================================================================== configurazione */

    public static int type(int widgetId) {
        return prefs().getInt(KEY_TYPE + widgetId, 0);
    }

    public static void setType(int widgetId, int type) {
        prefs().edit().putInt(KEY_TYPE + widgetId, type).apply();
    }

    /**
     * Lo slot SALVATO in configurazione, grezzo, senza ripieghi: -1 se il widget non è mai stato configurato.
     * <p>
     * Esiste perché il ripiego di {@link #account(int)} è giusto per il widget NON LETTI (un contatore ha senso
     * su qualunque account attivo) e SBAGLIATO per i widget che mostrano UNA chat: dopo il logout dallo slot su cui
     * la chat era stata scelta, il ripiego porta a un altro account, {@code MorsegramDialogs.isHidden} finisce per
     * interrogare la lista delle nascoste di QUELL'altro account, e il nome, l'anteprima e il contatore dello slot
     * da cui l'utente è uscito restavano disegnati sulla schermata iniziale, leggibili senza sbloccare il telefono
     * (vedi {@link WidgetDialog#read}).
     */
    public static int storedAccount(int widgetId) {
        final int stored = prefs().getInt(KEY_ACCOUNT + widgetId, -1);
        return stored >= 0 && stored < UserConfig.MAX_ACCOUNT_COUNT ? stored : -1;
    }

    /**
     * slot account del widget; se lo slot non è più attivo ripiega su quello selezionato. Il ripiego serve al
     * widget NON LETTI; per i widget di UNA chat vale {@link #storedAccount(int)} (vedi il suo javadoc).
     */
    public static int account(int widgetId) {
        final int stored = prefs().getInt(KEY_ACCOUNT + widgetId, -1);
        if (stored >= 0 && stored < UserConfig.MAX_ACCOUNT_COUNT) {
            try {
                if (UserConfig.getInstance(stored).isClientActivated()) {
                    return stored;
                }
            } catch (Throwable ignore) {
            }
        }
        return UserConfig.selectedAccount;
    }

    public static void setAccount(int widgetId, int account) {
        prefs().edit().putInt(KEY_ACCOUNT + widgetId, account).apply();
    }

    public static long dialogId(int widgetId) {
        return prefs().getLong(KEY_DIALOG + widgetId, 0);
    }

    public static void setDialogId(int widgetId, long dialogId) {
        prefs().edit().putLong(KEY_DIALOG + widgetId, dialogId).apply();
        wipeContent(widgetId);
    }

    public static int theme(int widgetId) {
        return prefs().getInt(KEY_THEME + widgetId, THEME_AUTO);
    }

    public static void setTheme(int widgetId, int theme) {
        prefs().edit().putInt(KEY_THEME + widgetId, theme).apply();
    }

    public static int corners(int widgetId) {
        return prefs().getInt(KEY_CORNERS + widgetId, CORNERS_ROUND);
    }

    public static void setCorners(int widgetId, int corners) {
        prefs().edit().putInt(KEY_CORNERS + widgetId, corners).apply();
    }

    /** opacità dello sfondo in percento (100 = pieno, 0 = sfondo invisibile). */
    public static int opacity(int widgetId) {
        final int v = prefs().getInt(KEY_OPACITY + widgetId, 100);
        return v < 0 ? 0 : Math.min(v, 100);
    }

    public static void setOpacity(int widgetId, int percent) {
        prefs().edit().putInt(KEY_OPACITY + widgetId, percent).apply();
    }

    /* ================================================================================ cache dati */

    public static String cachedName(int widgetId) {
        return prefs().getString(KEY_NAME + widgetId, null);
    }

    public static String cachedPreview(int widgetId) {
        return prefs().getString(KEY_PREVIEW + widgetId, null);
    }

    public static String cachedTime(int widgetId) {
        return prefs().getString(KEY_TIME + widgetId, null);
    }

    public static int cachedUnread(int widgetId) {
        return prefs().getInt(KEY_UNREAD + widgetId, 0);
    }

    public static void putContent(int widgetId, String name, String preview, String time, int unread) {
        prefs().edit()
                .putString(KEY_NAME + widgetId, name)
                .putString(KEY_PREVIEW + widgetId, preview)
                .putString(KEY_TIME + widgetId, time)
                .putInt(KEY_UNREAD + widgetId, unread)
                .apply();
    }

    /** Nessun residuo leggibile dalla schermata iniziale: chiamata quando la chat è (o potrebbe essere) nascosta. */
    public static void wipeContent(int widgetId) {
        prefs().edit()
                .remove(KEY_NAME + widgetId)
                .remove(KEY_PREVIEW + widgetId)
                .remove(KEY_TIME + widgetId)
                .remove(KEY_UNREAD + widgetId)
                .apply();
    }

    /* ======================================================================== contatore non letti */

    public static int unreadTotal(int account) {
        return prefs().getInt(KEY_UNREAD_TOTAL + account, -1);
    }

    public static int unreadChats(int account) {
        return prefs().getInt(KEY_UNREAD_CHATS + account, 0);
    }

    public static void putUnread(int account, int total, int chats) {
        prefs().edit()
                .putInt(KEY_UNREAD_TOTAL + account, total)
                .putInt(KEY_UNREAD_CHATS + account, chats)
                .apply();
    }

    /** onDeleted del provider: via configurazione e cache, altrimenti restano per sempre. */
    public static void clear(int[] widgetIds) {
        if (widgetIds == null || widgetIds.length == 0) {
            return;
        }
        final SharedPreferences.Editor editor = prefs().edit();
        for (int id : widgetIds) {
            editor.remove(KEY_TYPE + id).remove(KEY_ACCOUNT + id).remove(KEY_DIALOG + id)
                    .remove(KEY_THEME + id).remove(KEY_CORNERS + id).remove(KEY_OPACITY + id)
                    .remove(KEY_NAME + id).remove(KEY_PREVIEW + id).remove(KEY_TIME + id).remove(KEY_UNREAD + id);
        }
        editor.apply();
    }
}

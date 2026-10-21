/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.theme;

import android.util.SparseArray;

import org.telegram.messenger.R;

/**
 * Rinomina le stringhe in cui "Telegram" è il nome dell'APPLICAZIONE, lasciando intatte quelle in
 * cui è il nome del SERVIZIO.
 *
 * <p>COSTO UPSTREAM: UNA riga, in {@code LocaleController.getStringV2}. Serve davvero, e non basta
 * riscrivere le stringhe in {@code values/strings.xml}: {@code BuildVars.USE_CLOUD_STRINGS} è
 * {@code true} e {@code getStringV2} interroga PRIMA il language pack scaricato dai server Telegram
 * ({@code localizationExternal}) e solo dopo le risorse locali, sia per nome di chiave sia per id
 * risorsa (la tabella {@code string_resource_ids.bin} è costruita da R.txt e contiene TUTTE le
 * stringhe, anche quelle escluse dagli stable id). Una traduzione locale di {@code NoChats} funziona
 * quindi a installazione pulita e si annulla da sola appena arriva il langpack. Deviando la chiave
 * su una nostra — che nel langpack non esiste — la ricerca esterna fallisce sempre e vince il nostro
 * valore, in inglese e in italiano.
 *
 * <p>La riga upstream fa tre cose in una: sostituisce la chiave, azzera l'id risorsa e azzera il
 * fallback, perché {@code getStringV2} prova tutti e tre e basta che uno colpisca il langpack per
 * rivedere "Telegram".
 *
 * <p>PERIMETRO: qui dentro ci sono SOLO i casi in cui il testo parla dell'app installata. Restano
 * volutamente fuori, e non vanno aggiunti, i riferimenti al servizio: inviti e link t.me, "%1$s
 * joined Telegram!", Telegram Premium / Stars / Gifts / Passport, i messaggi sponsorizzati, le FAQ,
 * "Logged in with Telegram", il nome della piattaforma nel langpack, le sessioni web. E non si tocca
 * nulla di ciò che dichiara la natura del fork: {@code MorsegramUnofficialClient}, la licenza GPL,
 * le attribuzioni a DrKLO/Telegram.
 *
 * <p>Costo a runtime: una ricerca binaria su ~25 voci per ogni {@code getString}, dopo un {@code
 * resId == 0} che taglia corto sulle chiamate per sola chiave.
 */
public final class MorsegramBrand {

    private static volatile SparseArray<String> map;

    private MorsegramBrand() {
    }

    /**
     * @param stringRes l'id risorsa che il chiamante ha chiesto
     * @return la chiave Morsegram da usare al suo posto, oppure {@code null} per lasciar fare a upstream
     */
    /**
     * Stringhe in cui "Telegram" e' il nome dell'APP installata su questo telefono (permessi,
     * aggiornamenti, schermata di blocco): li' la parola va scambiata con Morsegram. Sono scelte
     * per chiave e non per contenuto, quindi lo scambio vale anche quando il pacchetto lingua
     * scaricato dai server e' italiano o altro. Restano intatte le frasi sul SERVIZIO Telegram
     * (account, canali, server, Premium, carta di credito): sostituirle sarebbe falso.
     */
    private static final int[] SWAP = {
            R.string.AppNameBeta,
            R.string.NotificationsPermissionAlertSubtitle,
            R.string.TelegramVersion,
            R.string.AppUpdate,
            R.string.AppUpdateBeta,
            R.string.LocalDatabaseInfo,
            R.string.UpdateTelegram,
            R.string.UpdateAppAlert,
            R.string.AllowReadCall,
            R.string.AllowReadCallAndLog,
            R.string.AllowReadCallLog,
            R.string.AllowFillNumber,
            R.string.PermissionNoLocation,
            R.string.PermissionNoLocationStory,
            R.string.PermissionDrawAboveOtherApps,
            R.string.PermissionXiaomiLockscreen,
            R.string.PermissionFSILockscreen,
            R.string.PermissionBackgroundLocation,
            R.string.VoipNeedCameraPermission,
            R.string.AgeVerificationNeedCameraPermission,
            R.string.UnsupportedBlockMessage,
            R.string.UnsupportedMessageMessage,
    };

    private static volatile android.util.SparseBooleanArray swapSet;

    /** Applica lo scambio del nome app al testo gia' tradotto, se questa stringa e' fra quelle. */
    public static String brand(int stringRes, String value) {
        if (stringRes == 0 || value == null || value.indexOf("Telegram") < 0) {
            return value;
        }
        android.util.SparseBooleanArray set = swapSet;
        if (set == null) {
            set = new android.util.SparseBooleanArray(SWAP.length);
            for (int id : SWAP) {
                set.put(id, true);
            }
            swapSet = set;
        }
        return set.get(stringRes) ? value.replace("Telegram", "Morsegram") : value;
    }

    public static String rename(int stringRes) {
        if (stringRes == 0) {
            return null;
        }
        SparseArray<String> m = map;
        if (m == null) {
            m = build();
        }
        return m.get(stringRes);
    }

    private static synchronized SparseArray<String> build() {
        if (map != null) {
            return map;
        }
        final SparseArray<String> m = new SparseArray<>(32);
        try {
            /* --- il nome dell'app dove compare da solo --- */
            // mittente mostrato sul lock screen quando le anteprime sono spente
            m.put(R.string.NotificationHiddenName, "MorsegramBrandName");
            m.put(R.string.NotificationHiddenChatName, "MorsegramBrandName");
            // nome di servizio delle chat segrete
            m.put(R.string.SecretChatName, "MorsegramBrandName");
            // schermata del codice di blocco
            m.put(R.string.AppLocked, "MorsegramBrandLocked");
            // lista chat vuota, subito dopo il primo accesso
            m.put(R.string.NoChats, "MorsegramBrandWelcome");
            // piè di pagina delle impostazioni
            m.put(R.string.TelegramVersion, "MorsegramBrandVersion");
            // aggiornamento in-app (il nostro aggiornatore sideload ha stringhe proprie)
            m.put(R.string.AppUpdate, "MorsegramBrandUpdate");
            m.put(R.string.UpdateTelegram, "MorsegramBrandUpdate");
            m.put(R.string.AppUpdateBeta, "MorsegramBrandUpdateBeta");
            // richieste di permesso: parlano dell'app installata, non del servizio
            m.put(R.string.ContactsPermissionAlert, "MorsegramBrandContactsPermission");
            m.put(R.string.NotificationsPermissionAlertSubtitle, "MorsegramBrandNotificationsPermission");
            m.put(R.string.QRCodePermissionNoCameraWithHint, "MorsegramBrandCameraPermission");
            // "Condividi Telegram": l'app consigliata siamo noi, il servizio resta Telegram
            m.put(R.string.ShareTelegram2, "MorsegramBrandShare");

            /* --- schermata introduttiva, prima dell'accesso --- */
            m.put(R.string.Page1Title, "MorsegramIntroTitle1");
            m.put(R.string.Page2Title, "MorsegramIntroTitle2");
            m.put(R.string.Page3Title, "MorsegramIntroTitle3");
            m.put(R.string.Page4Title, "MorsegramIntroTitle4");
            m.put(R.string.Page5Title, "MorsegramIntroTitle5");
            m.put(R.string.Page6Title, "MorsegramIntroTitle6");
            m.put(R.string.Page1Message, "MorsegramIntroText1");
            m.put(R.string.Page2Message, "MorsegramIntroText2");
            m.put(R.string.Page3Message, "MorsegramIntroText3");
            m.put(R.string.Page4Message, "MorsegramIntroText4");
            m.put(R.string.Page5Message, "MorsegramIntroText5");
            m.put(R.string.Page6Message, "MorsegramIntroText6");
            m.put(R.string.StartMessaging, "MorsegramIntroStart");
        } catch (Throwable ignore) {
            // una chiave sparita dopo un rebase non deve impedire all'app di mostrare testi
        }
        map = m;
        return m;
    }
}

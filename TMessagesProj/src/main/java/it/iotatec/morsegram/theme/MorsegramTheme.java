/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.theme;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import it.iotatec.morsegram.MorsegramPrefs;

/**
 * Semina i due temi di Morsegram al PRIMO avvio e poi non tocca più niente.
 *
 * <p>Costo upstream: ZERO righe. Si usano solo API pubbliche di {@link Theme}
 * ({@code applyThemeFile}, {@code setCurrentNightTheme}, {@code saveAutoNightThemeConfig},
 * {@code getTheme}), le stesse con cui l'app installa un .attheme ricevuto in chat. I due temi
 * entrano nell'elenco delle impostazioni accanto ai cinque integrati, con il loro nome e la
 * loro miniatura, e da lì in poi vivono di vita propria: la persistenza la fa upstream
 * (SharedPreferences "themeconfig" → chiave {@code themes2}, più {@code theme} e {@code nighttheme}
 * in "mainconfig"), e al riavvio il blocco statico di Theme li ricostruisce da solo.
 *
 * <p>LIBERTÀ DELL'UTENTE — è il vincolo che governa questa classe: si scrive una volta sola, alla
 * prima esecuzione, e la chiave {@link MorsegramPrefs#KEY_THEME_APPLIED} lo registra. Dal secondo
 * avvio {@link #seedIfFirstRun()} esce alla prima riga senza nemmeno caricare {@link Theme}:
 * qualunque tema l'utente scelga dopo — uno dei nostri, uno di Telegram, uno scaricato — resta,
 * e se cancella i nostri temi non glieli rimettiamo. Non imponiamo: diamo un punto di partenza.
 *
 * <p>Perché due file e non un accento ambra sui temi blu: un accento è una rotazione di tinta
 * algoritmica a partire dal blu #328ACF, che lascia com'è tutto ciò che è poco saturo (le superfici,
 * cioè quasi tutto il tema scuro) e non tocca le 110 chiavi di {@code themeAccentExclusionKeys}.
 * I due .attheme fissano invece ~800 chiavi su valori scelti, e sono generati da
 * {@code Telegram/Tools/morsegram_assets_gen.py} a partire da {@link MorsegramPalette}.
 */
public final class MorsegramTheme {

    /** Nome del tema chiaro: compare così nelle impostazioni (upstream toglie il suffisso .attheme). */
    public static final String NAME_DAY = "Morsegram";
    /** Nome del tema scuro. */
    public static final String NAME_NIGHT = "Morsegram notte";

    private static final String ASSET_DAY = "morsegram.attheme";
    private static final String ASSET_NIGHT = "morsegram_night.attheme";

    /** Versione della semina: cambiarla è l'UNICO modo di riseminare, e va fatto solo se consapevoli. */
    private static final int SEED_VERSION = 1;

    private MorsegramTheme() {
    }

    /**
     * Chiamata da {@code MorsegramApp.onCreate} (nessun nuovo hook upstream). A questo punto
     * {@code ApplicationLoader.onCreate} ha finito: contesto, SharedPreferences e file dir ci sono,
     * e nessuna Activity è ancora stata creata, quindi il tema è già quello giusto alla prima vista.
     */
    public static void seedIfFirstRun() {
        final SharedPreferences prefs = MorsegramPrefs.global();
        if (prefs.getInt(MorsegramPrefs.KEY_THEME_APPLIED, 0) >= SEED_VERSION) {
            return; // niente Theme.class, niente I/O: costo zero a ogni avvio successivo
        }
        // si marca PRIMA di applicare: se qualcosa esplode a metà non si riprova a ogni avvio,
        // e l'utente resta con il tema di Telegram invece che con un ciclo di tentativi.
        prefs.edit().putInt(MorsegramPrefs.KEY_THEME_APPLIED, SEED_VERSION).apply();
        try {
            final Theme.ThemeInfo night = install(ASSET_NIGHT, NAME_NIGHT);
            final Theme.ThemeInfo day = install(ASSET_DAY, NAME_DAY);
            if (night != null) {
                // applyThemeFile(night) ha lasciato 'night' anche come tema diurno: install(day),
                // chiamata dopo, lo rimette a posto. Qui si registra solo il ruolo notturno.
                Theme.setCurrentNightTheme(night);
                Theme.saveAutoNightThemeConfig();
                // Theme.java:4099 scrive "lastDarkTheme" al suo avvio statico, prima che i nostri temi
                // esistano: resta "Dark Blue" e l'interruttore scuro (DialogsActivity, IntroActivity)
                // riporterebbe l'utente al tema di Telegram. Qui lo si riallinea al nostro tema notte.
                ApplicationLoader.applicationContext
                        .getSharedPreferences("themeconfig", android.content.Context.MODE_PRIVATE)
                        .edit().putString("lastDarkTheme", night.getKey()).apply();
            }
            if (day != null) {
                ApplicationLoader.applicationContext
                        .getSharedPreferences("themeconfig", android.content.Context.MODE_PRIVATE)
                        .edit().putString("lastDayTheme", day.getKey()).apply();
                // applyThemeFile() registra il tema fra quelli disponibili ma NON lo rende corrente:
                // senza questa riga la chiave "theme" non viene mai scritta e l'app resta sul tema
                // Blue di Telegram (verificato: fondo #ffffff invece del nostro avorio #faf6ef).
                Theme.applyTheme(day, true, false);
            }
            if (day == null && night == null) {
                FileLog.e("morsegram: nessun tema seminato, resta il tema di Telegram");
            }
            // Al primo avvio il blocco statico di Theme ha gia' deciso giorno/notte PRIMA che i nostri
            // temi esistessero, e needSwitchToTheme() e' privato: se il telefono e' in modalita' scura
            // si rifa' qui lo stesso passaggio della riga 4323 di Theme.java (applyTheme senza salvare,
            // con nightTheme=true), altrimenti il primo avvio al buio resterebbe sul tema chiaro.
            if (night != null && Theme.selectedAutoNightType == Theme.AUTO_NIGHT_TYPE_SYSTEM && isSystemDark()) {
                Theme.applyTheme(night, false, true);
            }
            AndroidUtilities.runOnUIThread(Theme::checkAutoNightThemeConditions);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Nome del tema diurno configurato (themeconfig."lastDayTheme"), altrimenti quello di Telegram. */
    public static String dayThemeName() {
        return configured("lastDayTheme", "Blue", false);
    }

    /** Nome del tema notturno configurato (themeconfig."lastDarkTheme"), altrimenti quello di Telegram. */
    public static String nightThemeName() {
        return configured("lastDarkTheme", "Night", true);
    }

    private static String configured(String key, String fallback, boolean wantDark) {
        try {
            final String name = ApplicationLoader.applicationContext
                    .getSharedPreferences("themeconfig", android.content.Context.MODE_PRIVATE)
                    .getString(key, fallback);
            final Theme.ThemeInfo info = Theme.getTheme(name);
            // stesso controllo di DialogsActivity: un tema mancante o del verso sbagliato non va usato
            if (info != null && info.isDark() == wantDark) {
                return name;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return fallback;
    }

    private static boolean isSystemDark() {
        final Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return false;
        }
        final int mode = ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private static Theme.ThemeInfo install(String assetName, String themeName) {
        try {
            final Theme.ThemeInfo existing = Theme.getTheme(themeName + ".attheme");
            final File file = copyAsset(assetName);
            if (file == null) {
                return existing;
            }
            final Theme.ThemeInfo info = Theme.applyThemeFile(file, themeName, null, false);
            file.delete();
            return info != null ? info : existing;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Gli asset non sono file: {@code Theme.applyThemeFile} vuole un File, quindi si passa dalla cache. */
    private static File copyAsset(String assetName) {
        final Context ctx = ApplicationLoader.applicationContext;
        if (ctx == null) {
            return null;
        }
        final File out = new File(ctx.getCacheDir(), assetName);
        InputStream in = null;
        OutputStream os = null;
        try {
            in = ctx.getAssets().open(assetName);
            os = new FileOutputStream(out);
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                os.write(buffer, 0, read);
            }
            os.flush();
            return out;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            close(in);
            close(os);
        }
    }

    private static void close(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignore) {
            }
        }
    }
}

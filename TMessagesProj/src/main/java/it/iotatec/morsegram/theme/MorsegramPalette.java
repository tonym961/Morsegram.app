/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.theme;

/**
 * UNICA fonte di verità dei colori di Morsegram. Nessun altro file, Java o XML, inventa un colore:
 * <ul>
 * <li>il codice Java legge queste costanti;</li>
 * <li>i due temi {@code morsegram.attheme} / {@code morsegram_night.attheme} in
 *     {@code TMessagesProj_App/src/main/assets/} sono GENERATI da {@code Telegram/Tools/morsegram_assets_gen.py},
 *     che parsa QUESTO file per i valori (sezione "palette") — mai valori ribattuti a mano;</li>
 * <li>le poche risorse XML che non possono leggere Java (splash screen, icona adattiva) ripetono i letterali
 *     con un commento che rimanda qui.</li>
 * </ul>
 *
 * <p>Derivazione: il logo di Morsegram ({@code docs/img/logo-1024.png}) è ambra #F0A020 per il 95% dei pixel
 * su nero #101010. {@link #AMBER} e {@link #BLACK} sono quindi misurati sul logo, non scelti; il resto è
 * derivato da quei due con un criterio dichiarato nel commento di ogni costante.
 *
 * <p>Regola di leggibilità (rapporti di contrasto WCAG, verificati sui valori qui sotto): l'ambra pura su
 * bianco sta a 2,15:1 — va bene per i riempimenti, non per il testo, e nemmeno il blu di Telegram ci stava
 * (#229AF0 su bianco è 3,01:1). Per il testo su fondo chiaro c'è {@link #AMBER_INK} (5,93:1), per le icone
 * {@link #AMBER_MID} (3,23:1, la soglia WCAG per i componenti non testuali), per il testo su fondo scuro
 * {@link #AMBER_LIGHT} (12,21:1 su {@link #BLACK}) o l'ambra piena (8,84:1 su {@link #BLACK}).
 * Sopra un riempimento ambra i glifi vanno scuri ({@link #ON_AMBER}, 8,84:1): il bianco sarebbe 2,15:1.
 */
public final class MorsegramPalette {

    /* ---------------------------------------------------------------- palette: ambra */

    /** Ambra primaria, misurata sul logo (95% dei pixel): accenti, riempimenti, FAB, interruttori attivi. */
    public static final int AMBER = 0xFFF0A020;
    /** Ambra scura: stati premuti e pressed ripple sopra {@link #AMBER}. */
    public static final int AMBER_DARK = 0xFFC87F10;
    /** Ambra chiara: dettagli, bordi, testo accentato su fondo scuro, evidenziazioni. */
    public static final int AMBER_LIGHT = 0xFFFFC658;
    /** Ambra inchiostro: ambra desaturata e scurita fino a 5,93:1 su bianco — l'UNICA ambra ammessa per il testo su chiaro. */
    public static final int AMBER_INK = 0xFF8A5A00;
    /** Ambra media: icone e glifi ambra su fondo chiaro (3,23:1, soglia icone WCAG 3:1), es. pulsante di invio. */
    public static final int AMBER_MID = 0xFFC87F10;
    /** Glifo/testo sopra un riempimento {@link #AMBER}: nero caldo, 8,84:1. Mai bianco. */
    public static final int ON_AMBER = 0xFF101010;

    /* ---------------------------------------------------------------- palette: scuri */

    /** Nero caldo del logo: fondo delle schermate e sfondo chat del tema notte. */
    public static final int BLACK = 0xFF101010;
    /** Grigio neutro 1: superfici (liste, schede, barra superiore) del tema notte. */
    public static final int DARK_1 = 0xFF1A1A1A;
    /** Grigio neutro 2: superfici elevate, intestazioni di sezione, fumetto ricevuto. */
    public static final int DARK_2 = 0xFF242424;
    /** Grigio neutro 3: selezione, pressioni, fumetto ricevuto selezionato. */
    public static final int DARK_3 = 0xFF2E2E2E;
    /** Grigio neutro 4: separatori e bordi del tema notte. */
    public static final int DARK_4 = 0xFF383838;
    /** Testo primario su fondo scuro. */
    public static final int DARK_TEXT = 0xFFFFFFFF;
    /** Testo secondario su fondo scuro (6,66:1 su {@link #DARK_1}). */
    public static final int DARK_TEXT_2 = 0xFFA0A0A0;
    /** Fumetto inviato del tema notte: ambra portata al 18% di luminosità, resta leggibile col testo bianco. */
    public static final int DARK_BUBBLE_OUT = 0xFF4A3410;
    /** Fumetto inviato selezionato (tema notte). */
    public static final int DARK_BUBBLE_OUT_SELECTED = 0xFF5E4315;

    /* ---------------------------------------------------------------- palette: chiari */

    /** Bianco: superfici del tema chiaro (liste, schede, barra superiore, fumetto ricevuto). */
    public static final int WHITE = 0xFFFFFFFF;
    /** Avorio: fondo delle schermate del tema chiaro (ambra al 4% su bianco). */
    public static final int IVORY = 0xFFFAF6EF;
    /** Avorio 2: intestazioni di sezione e superfici premute del tema chiaro. */
    public static final int IVORY_2 = 0xFFF3EDE2;
    /** Avorio 3: separatori e bordi del tema chiaro. */
    public static final int IVORY_3 = 0xFFE7DFD0;
    /** Sabbia calda: sfondo chat del tema chiaro (il blu #5390D1 di Telegram diventa ambra desaturata). */
    public static final int SAND = 0xFFF2E8D5;
    /** Testo primario su fondo chiaro. */
    public static final int INK = 0xFF1A1A1A;
    /** Testo secondario su fondo chiaro (4,78:1 su bianco). */
    public static final int INK_2 = 0xFF757270;
    /** Fumetto inviato del tema chiaro: ambra al 15% su bianco. */
    public static final int LIGHT_BUBBLE_OUT = 0xFFFFEBC4;
    /** Fumetto inviato selezionato (tema chiaro). */
    public static final int LIGHT_BUBBLE_OUT_SELECTED = 0xFFFFDFA3;

    /* ---------------------------------------------------------------- usi fuori dal tema */

    /**
     * Accento della striscia di notifica (Notification.Builder#setColor). Android lo usa come tinta dell'icona
     * piccola e del nome app nell'ombra: va l'ambra piena, perché il sistema la posa su fondo chiaro E scuro
     * e {@link #AMBER} è l'unico valore della palette che resta riconoscibile su entrambi.
     */
    public static final int NOTIFICATION_ACCENT = AMBER;

    private MorsegramPalette() {
    }
}

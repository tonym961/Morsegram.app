#!/usr/bin/env python3
# -*- coding: utf-8 -*-
#
# Morsegram - client Telegram non ufficiale.
# Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
#
"""Genera gli asset di identita' visiva di Morsegram.

PERCHE' UNO SCRIPT E NON FILE SCRITTI A MANO: i due temi coprono ~800 chiavi colore
ciascuno, derivate dalla tabella `defaultColors` di upstream. A ogni rebase mensile
upstream aggiunge, rinomina o ritocca chiavi: un .attheme scritto a mano invecchia in
silenzio (una chiave sconosciuta viene ignorata senza errori, una chiave nuova ricade
sul blu Telegram). Rigenerando si riallinea tutto in un comando e le chiavi sparite
diventano un errore visibile.

FONTI (nessun valore e' ribattuto qui dentro):
  - palette:        TMessagesProj/src/main/java/it/iotatec/morsegram/theme/MorsegramPalette.java
  - colori default: TMessagesProj/src/main/java/org/telegram/ui/ActionBar/ThemeColors.java
  - nomi chiave:    idem, createColorKeysMap()
  - esclusioni:     TMessagesProj/src/main/java/org/telegram/ui/ActionBar/Theme.java
                    (themeAccentExclusionKeys: le chiavi che upstream stesso tiene fuori
                     dalla ricolorazione - avatar, premium, storie, grafici, chiamate di gruppo)
  - basi:           TMessagesProj/src/main/assets/bluebubbles.attheme (chiaro)
                    TMessagesProj/src/main/assets/darkblue.attheme   (scuro)

USCITE:
  - TMessagesProj_App/src/main/assets/morsegram.attheme
  - TMessagesProj_App/src/main/assets/morsegram_night.attheme
  - TMessagesProj_App/src/main/res/drawable-{mdpi,hdpi,xhdpi,xxhdpi}/notification.png
  - TMessagesProj_App/src/main/res/drawable-{mdpi,hdpi,xhdpi,xxhdpi}/intro_tg_plane.png
  - TMessagesProj_App/src/main/res/drawable-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/book_logo.png
  - TMessagesProj_App/src/main/res/drawable-<5 densita'>/telegram_logo.png, telegram_logo_2.png
    (il logotipo "Morsegram" al posto del lettering "Telegram"; richiede Pillow, le altre uscite no)

  - TMessagesProj_App/src/main/res/{drawable,mipmap-*}/ic_launcher*, icon_{2..6}_launcher*,
    icon*_background_sa, icon*_foreground_sa, morsegram_icon_* (job launcher: l'icona dell'app e le
    cinque varianti alternative; le varianti sovrascrivono alla stessa configurazione le risorse che
    gli activity-alias di upstream indossano, cosi' nell'APK non entra nessun aeroplanino - ToS 2.4)

USO:   python3 Tools/morsegram_assets_gen.py [themes|icons|wordmarks|launcher|all] [--check]
       --check non scrive nulla e fallisce se l'uscita differisce da quella in repo.

TRASFORMAZIONE DEI COLORI (per le chiavi non elencate in OVERRIDES):
  1. alpha 0 o saturazione < 4%  -> invariato (neutri, trasparenti, bianchi, neri)
  2. tinta fuori da [150, 280]   -> invariato (verdi, rossi, arancioni: non sono marchio Telegram)
  3. tema scuro, colore scuro e poco saturo (V<50%, S<45%) -> grigio NEUTRO di pari luminanza
     (i blu-grigi #242D39 / #151E27 / #1D2733 di darkblue.attheme diventano grigi, non marroni)
  4. altrimenti -> tinta portata ad ambra e luminanza RELATIVA CONSERVATA.
     Conservare la luminanza e' il punto: l'ambra a parita' di HSV e' molto piu' chiara del blu
     (L=0.438 contro 0.299), quindi una semplice rotazione di tinta renderebbe illeggibile ogni
     testo accentato. Con la luminanza conservata ogni colore mantiene esattamente il peso visivo
     che aveva in upstream, e i contrasti restano quelli che i progettisti di Telegram avevano
     verificato.
"""

import argparse
import colorsys
import os
import re
import struct
import sys
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)  # .../Telegram

LIB = os.path.join(ROOT, "TMessagesProj", "src", "main")
APP = os.path.join(ROOT, "TMessagesProj_App", "src", "main")

PALETTE_JAVA = os.path.join(
    LIB, "java", "it", "iotatec", "morsegram", "theme", "MorsegramPalette.java"
)
THEME_COLORS_JAVA = os.path.join(LIB, "java", "org", "telegram", "ui", "ActionBar", "ThemeColors.java")
THEME_JAVA = os.path.join(LIB, "java", "org", "telegram", "ui", "ActionBar", "Theme.java")

HUE_BLUE_MIN, HUE_BLUE_MAX = 150.0, 280.0  # famiglia blu/ciano/indaco da ricolorare
SAT_NEUTRAL = 0.04                         # sotto questa saturazione il colore e' un grigio
HUE_SRC = 206.0                             # tinta del blu Telegram #229AF0
HUE_SPREAD = 0.22                           # quanto della varieta' di tinta originale si conserva


# --------------------------------------------------------------------------- parsing


def read(path):
    with open(path, "r", encoding="utf-8") as fh:
        return fh.read()


def parse_palette():
    """MorsegramPalette.java -> {NOME: argb}. Risolve anche gli alias (X = AMBER)."""
    text = read(PALETTE_JAVA)
    out = {}
    pattern = re.compile(
        r"public\s+static\s+final\s+int\s+([A-Z0-9_]+)\s*=\s*(0[xX][0-9a-fA-F]{8}|[A-Z0-9_]+)\s*;"
    )
    for name, value in pattern.findall(text):
        if value.lower().startswith("0x"):
            out[name] = int(value, 16) & 0xFFFFFFFF
        elif value in out:
            out[name] = out[value]
        else:
            die("MorsegramPalette.%s rimanda a %s, che non e' definita prima" % (name, value))
    if not out:
        die("nessuna costante trovata in MorsegramPalette.java")
    return out


def parse_key_names():
    """createColorKeysMap() -> {key_xxx: "xxx"}, cioe' i nomi validi in un .attheme."""
    text = read(THEME_COLORS_JAVA)
    pairs = re.findall(r'colorKeysMap\.put\((key_[A-Za-z0-9_]+),\s*"([A-Za-z0-9_]+)"\)', text)
    if not pairs:
        die("createColorKeysMap() non parsata: ThemeColors.java e' cambiato")
    return dict(pairs)


def parse_default_colors(palette):
    """createDefaultColors() -> {key_xxx: argb}. Le righe non letterali vengono saltate.

    Le costanti di testa (TELEGRAM_COLOR e compagnia) possono rimandare a MorsegramPalette:
    e' esattamente quello che fa il nostro hook di una riga su ThemeColors.java:14.
    """
    text = read(THEME_COLORS_JAVA)
    consts = {}
    for name, raw in re.findall(
        r"public\s+static\s+final\s+int\s+(TELEGRAM_COLOR|TELEGRAM_COLOR_TEXT|DEFAULT_BLACK_TEXT)\s*=\s*([^;]+);",
        text,
    ):
        raw = raw.strip()
        ref = re.fullmatch(r"(?:[A-Za-z0-9_.]*\.)?MorsegramPalette\.([A-Z0-9_]+)", raw)
        if re.fullmatch(r"0[xX][0-9a-fA-F]{8}", raw):
            consts[name] = int(raw, 16) & 0xFFFFFFFF
        elif ref and ref.group(1) in palette:
            consts[name] = palette[ref.group(1)]
        else:
            die("ThemeColors.%s = %s: valore non risolvibile" % (name, raw))
    out = {}
    skipped = []
    for key, raw in re.findall(r"defaultColors\[(key_[A-Za-z0-9_]+)\]\s*=\s*([^;]+);", text):
        raw = raw.strip()
        if re.fullmatch(r"0[xX][0-9a-fA-F]{6,8}", raw):
            out[key] = int(raw, 16) & 0xFFFFFFFF
        elif raw in consts:
            out[key] = consts[raw]
        elif re.fullmatch(r"-?\d+", raw):
            out[key] = int(raw) & 0xFFFFFFFF
        else:
            skipped.append(key)  # Color.WHITE, ColorUtils.setAlphaComponent(...): tutte premium*
    if len(out) < 700:
        die("solo %d colori di default parsati: ThemeColors.java e' cambiato" % len(out))
    return out, skipped


def parse_exclusions():
    """themeAccentExclusionKeys di Theme.java -> set di key_xxx (array keys_* risolti)."""
    text = read(THEME_JAVA)
    arrays = {}
    for name, body in re.findall(r"int\[\]\s+(keys_[A-Za-z0-9_]+)\s*=\s*\{([^}]*)\}", text):
        arrays[name] = re.findall(r"key_[A-Za-z0-9_]+", body)
    out = set()
    # due forme: add(key_xxx) e add(keys_xxx[i]) dentro un for
    for token in re.findall(r"themeAccentExclusionKeys\.add\(([A-Za-z0-9_]+)", text):
        if token.startswith("key_"):
            out.add(token)
        elif token in arrays:
            out.update(arrays[token])
        else:
            die("themeAccentExclusionKeys.add(%s): simbolo non risolto" % token)
    if len(out) < 100:
        die("solo %d chiavi di esclusione parsate: Theme.java e' cambiato" % len(out))
    return out


def parse_attheme(path):
    """bluebubbles.attheme / darkblue.attheme -> {"nomeChiave": argb}."""
    out = {}
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            line = line.strip()
            if not line or "=" not in line or line.startswith("WPS"):
                continue
            name, _, value = line.partition("=")
            name, value = name.strip(), value.strip()
            if name in ("WLS", "WPS"):
                continue
            # NB: 9 nomi di ThemeColors portano davvero il prefisso "key_" (incoerenza upstream),
            # quindi i nomi NON vanno normalizzati: si tengono verbatim e si validano dopo.
            if value.startswith("#"):
                digits = value[1:]
                if len(digits) == 6:
                    out[name] = 0xFF000000 | int(digits, 16)
                elif len(digits) == 8:
                    out[name] = int(digits, 16)
                continue
            try:
                out[name] = int(value) & 0xFFFFFFFF
            except ValueError:
                continue
    return out


def die(msg):
    sys.stderr.write("morsegram_assets_gen: %s\n" % msg)
    sys.exit(1)


# --------------------------------------------------------------------------- colore


def argb(value):
    return (value >> 24) & 0xFF, (value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF


def pack(a, r, g, b):
    return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF)


def srgb_to_linear(channel):
    c = channel / 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4


def luminance(r, g, b):
    return 0.2126 * srgb_to_linear(r) + 0.7152 * srgb_to_linear(g) + 0.0722 * srgb_to_linear(b)


def from_hsv(h, s, v):
    r, g, b = colorsys.hsv_to_rgb((h % 360.0) / 360.0, s, v)
    return int(round(r * 255)), int(round(g * 255)), int(round(b * 255))


def match_luminance(h, s, target):
    """Il colore (h, s, V) con la V che riproduce `target` di luminanza relativa."""
    lo, hi = 0.0, 1.0
    for _ in range(24):
        mid = (lo + hi) / 2.0
        if luminance(*from_hsv(h, s, mid)) < target:
            lo = mid
        else:
            hi = mid
    return from_hsv(h, s, (lo + hi) / 2.0)


def amberize(value, amber_hue, dark_theme):
    a, r, g, b = argb(value)
    if a == 0:
        return value
    h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
    h *= 360.0
    if s < SAT_NEUTRAL:
        return value
    if not (HUE_BLUE_MIN <= h <= HUE_BLUE_MAX):
        return value
    target = luminance(r, g, b)
    if dark_theme and v < 0.50 and s < 0.45:
        nr, ng, nb = match_luminance(0.0, 0.0, target)  # superficie blu-grigia -> grigio neutro
    else:
        nr, ng, nb = match_luminance(amber_hue + (h - HUE_SRC) * HUE_SPREAD, s, target)
    return pack(a, nr, ng, nb)


# --------------------------------------------------------------------------- temi

# Chiavi scritte a mano: tutto cio' che l'utente vede nei primi tre secondi, piu' ogni
# superficie dove la trasformazione automatica non puo' sapere che ruolo ha il colore.
# Il valore e' il nome di una costante di MorsegramPalette.
OVERRIDES_LIGHT = {
    # Voci non selezionate: dal 60% all 80% di opacita, da 4,90:1 a 8,70:1.
    "glass_defaultText": "#CC25201A",
    "glass_defaultIcon": "#CC25201A",

    # Barra a quattro voci: sul fondo bianco l'ambra scura si legge, quindi resta.
    "glass_tabSelectedText": "#FFAC6E0B",

    # barra superiore e schede
    "actionBarDefault": "WHITE",
    "actionBarDefaultTitle": "INK",
    "actionBarDefaultSubtitle": "INK_2",
    "actionBarDefaultIcon": "INK",
    "actionBarDefaultSelector": "IVORY_2",
    "actionBarDefaultSearch": "INK",
    "actionBarDefaultSearchPlaceholder": "INK_2",
    "actionBarWhiteSelector": "IVORY_2",
    "actionBarActionModeDefault": "WHITE",
    "actionBarActionModeDefaultIcon": "INK",
    "actionBarActionModeDefaultSelector": "IVORY_2",
    "actionBarTabActiveText": "AMBER_INK",
    "actionBarTabUnactiveText": "INK_2",
    "actionBarTabLine": "AMBER",
    "actionBarTabSelector": "IVORY_2",
    # colore di marca (anche la scritta-logo in cima alla lista chat)
    "telegram_color": "AMBER",
    "telegram_color_text": "AMBER_INK",
    "telegram_color_dialogsLogo": "AMBER_DARK",
    # fondi
    "windowBackgroundWhite": "WHITE",
    "windowBackgroundWhiteBlackText": "INK",
    "windowBackgroundWhiteGrayText": "INK_2",
    "windowBackgroundGray": "IVORY",
    "windowBackgroundGrayShadow": "IVORY_3",
    "graySection": "IVORY_2",
    "key_graySectionText": "INK_2",
    "divider": "IVORY_3",
    # voci accentate delle impostazioni
    "windowBackgroundWhiteBlueText": "AMBER_INK",
    "windowBackgroundWhiteBlueText2": "AMBER_INK",
    "windowBackgroundWhiteBlueText3": "AMBER_INK",
    "windowBackgroundWhiteBlueText4": "AMBER_INK",
    "windowBackgroundWhiteBlueText5": "AMBER_INK",
    "windowBackgroundWhiteBlueText6": "AMBER_INK",
    "windowBackgroundWhiteBlueText7": "AMBER_INK",
    "windowBackgroundWhiteBlueButton": "AMBER_INK",
    "windowBackgroundWhiteBlueIcon": "AMBER_MID",
    "windowBackgroundWhiteBlueHeader": "AMBER_INK",
    "windowBackgroundWhiteLinkText": "AMBER_INK",
    "windowBackgroundWhiteValueText": "AMBER_INK",
    "windowBackgroundChecked": "AMBER",
    "windowBackgroundCheckText": "ON_AMBER",
    # interruttori, radio, caselle
    "switchTrackChecked": "AMBER",
    "switch2TrackChecked": "AMBER",
    "switchTrackBlueChecked": "AMBER",
    "radioBackgroundChecked": "AMBER",
    "checkbox": "AMBER",
    "checkboxCheck": "ON_AMBER",
    "checkboxSquareBackground": "AMBER",
    "checkboxSquareCheck": "ON_AMBER",
    # lista chat
    "chats_name": "INK",
    "chats_message": "INK_2",
    "chats_date": "INK_2",
    "chats_actionBackground": "AMBER",
    "chats_actionPressedBackground": "AMBER_DARK",
    "chats_actionIcon": "ON_AMBER",
    "chats_unreadCounter": "AMBER",
    "chats_unreadCounterText": "ON_AMBER",
    # pannello laterale (drawer): l'intestazione blu piena di Telegram diventa il blocco ambra del marchio
    "chats_menuTopBackgroundCats": "AMBER",
    "chats_menuName": "ON_AMBER",
    "chats_menuPhone": "ON_AMBER",
    "chats_menuPhoneCats": "ON_AMBER",
    "chats_menuBackground": "WHITE",
    "chats_menuItemText": "INK",
    "chats_menuItemIcon": "INK_2",
    # pulsanti pieni accentati (compreso "Inizia a messaggiare" della schermata introduttiva)
    "featuredStickers_addButton": "AMBER",
    "featuredStickers_addButton2": "AMBER_DARK",
    "featuredStickers_addButtonPressed": "AMBER_DARK",
    "featuredStickers_buttonText": "ON_AMBER",
    # conversazione
    "chat_wallpaper": "SAND",
    "chat_inBubble": "WHITE",
    "chat_inBubbleSelected": "IVORY_2",
    "chat_outBubble": "LIGHT_BUBBLE_OUT",
    "chat_outBubbleGradient": "LIGHT_BUBBLE_OUT",
    "chat_outBubbleGradient2": "LIGHT_BUBBLE_OUT",
    "chat_outBubbleGradient3": "LIGHT_BUBBLE_OUT",
    "chat_outBubbleSelected": "LIGHT_BUBBLE_OUT_SELECTED",
    "chat_messagePanelBackground": "WHITE",
    "chat_messagePanelText": "INK",
    "chat_messagePanelHint": "INK_2",
    "chat_messagePanelIcons": "INK_2",
    "chat_messagePanelSend": "AMBER_MID",
    "chat_messagePanelVoiceBackground": "AMBER",
    "chat_goDownButtonCounterBackground": "AMBER",
    "chat_goDownButtonCounter": "ON_AMBER",
    "chat_messageLinkIn": "AMBER_INK",
    "chat_messageLinkOut": "AMBER_INK",
    # finestre di dialogo
    "dialogTextLink": "AMBER_INK",
    "dialogTextBlue": "AMBER_INK",
    "dialogTextBlue2": "AMBER_INK",
    "dialogTextBlue4": "AMBER_INK",
    "dialogButton": "AMBER_INK",
    "dialogTopBackground": "AMBER",
    "dialogRadioBackgroundChecked": "AMBER",
    "dialogCheckboxSquareBackground": "AMBER",
    "dialogLineProgress": "AMBER",
    "dialogInputFieldActivated": "AMBER",
    # profilo e avatar dei Messaggi salvati
    "profile_actionBackground": "AMBER",
    "profile_actionPressedBackground": "AMBER_DARK",
    "profile_actionIcon": "ON_AMBER",
    "avatar_backgroundSaved": "AMBER",
    "avatar_background2Saved": "AMBER_DARK",
}

OVERRIDES_NIGHT = {
    # LA chiave che disegna davvero le voci non selezionate della barra (GlassTabView usa
    # key_glass_tabUnselected per testo e icona, NON glass_defaultText). Ereditata scura dal
    # tema di base, su una barra scura dava 1,10:1 di contrasto: nero su nero. Con #B8B8B8
    # sale a 7,83:1 e resta comunque secondaria rispetto all ambra della voce scelta.
    "glass_tabUnselected": "#FFB8B8B8",

    # Voci NON selezionate della barra: upstream le disegna al 63% di opacita e insieme alle
    # icone altrettanto spente l'insieme risulta sbiadito (segnalato il 22/10/2026).
    # Al 90% il testo passa da 6,92:1 a 12,90:1 e la voce scelta resta comunque distinta,
    # perche quella e ambra piena mentre queste sono bianche.
    "glass_defaultText": "#E6FFFFFF",
    "glass_defaultIcon": "#E6FFFFFF",

    # Barra a quattro voci in basso: fondo NEUTRO, ambra solo sulla voce scelta.
    # Il fondo brunastro ereditato impastava la barra (vedi la foto del 22/10/2026) e portava
    # il testo selezionato a 3,27:1 invece dei 4,5 richiesti. Neutro: 7,21:1.
    "glass_targetMainTabs": "#FF242424",
    "glass_tabSelectedText": "#FFF0A020",

    "actionBarDefault": "DARK_1",
    "actionBarDefaultTitle": "DARK_TEXT",
    "actionBarDefaultSubtitle": "DARK_TEXT_2",
    "actionBarDefaultIcon": "DARK_TEXT",
    "actionBarDefaultSelector": "DARK_3",
    "actionBarDefaultSearch": "DARK_TEXT",
    "actionBarDefaultSearchPlaceholder": "DARK_TEXT_2",
    "actionBarWhiteSelector": "DARK_3",
    "actionBarActionModeDefault": "DARK_1",
    "actionBarActionModeDefaultIcon": "DARK_TEXT",
    "actionBarActionModeDefaultSelector": "DARK_3",
    "actionBarTabActiveText": "AMBER",
    "actionBarTabUnactiveText": "DARK_TEXT_2",
    "actionBarTabLine": "AMBER",
    "actionBarTabSelector": "DARK_3",
    "telegram_color": "AMBER",
    "telegram_color_text": "AMBER_LIGHT",
    "telegram_color_dialogsLogo": "AMBER",
    "windowBackgroundWhite": "DARK_1",
    "windowBackgroundWhiteBlackText": "DARK_TEXT",
    "windowBackgroundWhiteGrayText": "DARK_TEXT_2",
    "windowBackgroundGray": "BLACK",
    "windowBackgroundGrayShadow": "BLACK",
    "graySection": "DARK_2",
    "key_graySectionText": "DARK_TEXT_2",
    "divider": "DARK_4",
    "windowBackgroundWhiteBlueText": "AMBER",
    "windowBackgroundWhiteBlueText2": "AMBER",
    "windowBackgroundWhiteBlueText3": "AMBER",
    "windowBackgroundWhiteBlueText4": "AMBER",
    "windowBackgroundWhiteBlueText5": "AMBER",
    "windowBackgroundWhiteBlueText6": "AMBER",
    "windowBackgroundWhiteBlueText7": "AMBER",
    "windowBackgroundWhiteBlueButton": "AMBER",
    "windowBackgroundWhiteBlueIcon": "AMBER",
    "windowBackgroundWhiteBlueHeader": "AMBER",
    "windowBackgroundWhiteLinkText": "AMBER_LIGHT",
    "windowBackgroundWhiteValueText": "AMBER",
    "windowBackgroundChecked": "AMBER",
    "windowBackgroundCheckText": "ON_AMBER",
    "switchTrackChecked": "AMBER",
    "switch2TrackChecked": "AMBER",
    "switchTrackBlueChecked": "AMBER",
    "radioBackgroundChecked": "AMBER",
    "checkbox": "AMBER",
    "checkboxCheck": "ON_AMBER",
    "checkboxSquareBackground": "AMBER",
    "checkboxSquareCheck": "ON_AMBER",
    "chats_name": "DARK_TEXT",
    "chats_message": "DARK_TEXT_2",
    "chats_date": "DARK_TEXT_2",
    "chats_actionBackground": "AMBER",
    "chats_actionPressedBackground": "AMBER_DARK",
    "chats_actionIcon": "ON_AMBER",
    "chats_unreadCounter": "AMBER",
    "chats_unreadCounterText": "ON_AMBER",
    "chats_menuTopBackgroundCats": "AMBER",
    "chats_menuName": "ON_AMBER",
    "chats_menuPhone": "ON_AMBER",
    "chats_menuPhoneCats": "ON_AMBER",
    "chats_menuBackground": "DARK_1",
    "chats_menuItemText": "DARK_TEXT",
    "chats_menuItemIcon": "DARK_TEXT_2",
    "featuredStickers_addButton": "AMBER",
    "featuredStickers_addButton2": "AMBER_DARK",
    "featuredStickers_addButtonPressed": "AMBER_DARK",
    "featuredStickers_buttonText": "ON_AMBER",
    "chat_wallpaper": "BLACK",
    "chat_inBubble": "DARK_2",
    "chat_inBubbleSelected": "DARK_3",
    "chat_outBubble": "DARK_BUBBLE_OUT",
    "chat_outBubbleGradient": "DARK_BUBBLE_OUT",
    "chat_outBubbleGradient2": "DARK_BUBBLE_OUT",
    "chat_outBubbleGradient3": "DARK_BUBBLE_OUT",
    "chat_outBubbleSelected": "DARK_BUBBLE_OUT_SELECTED",
    "chat_outTimeText": "AMBER_LIGHT",
    "chat_outSentCheck": "AMBER_LIGHT",
    "chat_outSentCheckRead": "AMBER_LIGHT",
    "chat_messagePanelBackground": "DARK_1",
    "chat_messagePanelText": "DARK_TEXT",
    "chat_messagePanelHint": "DARK_TEXT_2",
    "chat_messagePanelIcons": "DARK_TEXT_2",
    "chat_messagePanelSend": "AMBER",
    "chat_messagePanelVoiceBackground": "AMBER",
    "chat_goDownButtonCounterBackground": "AMBER",
    "chat_goDownButtonCounter": "ON_AMBER",
    "chat_messageLinkIn": "AMBER_LIGHT",
    "chat_messageLinkOut": "AMBER_LIGHT",
    "dialogBackground": "DARK_1",
    "dialogBackgroundGray": "BLACK",
    "dialogTextBlack": "DARK_TEXT",
    "dialogTextLink": "AMBER_LIGHT",
    "dialogTextBlue": "AMBER",
    "dialogTextBlue2": "AMBER",
    "dialogTextBlue4": "AMBER",
    "dialogButton": "AMBER",
    "dialogTopBackground": "AMBER",
    "dialogRadioBackgroundChecked": "AMBER",
    "dialogCheckboxSquareBackground": "AMBER",
    "dialogLineProgress": "AMBER",
    "dialogInputFieldActivated": "AMBER",
    "profile_actionBackground": "AMBER",
    "profile_actionPressedBackground": "AMBER_DARK",
    "profile_actionIcon": "ON_AMBER",
    "avatar_backgroundSaved": "AMBER",
    "avatar_background2Saved": "AMBER_DARK",
}

HEADER = """\
# Morsegram - %(title)s
# GENERATO da Telegram/Tools/morsegram_assets_gen.py: NON modificare a mano.
# Palette: it/iotatec/morsegram/theme/MorsegramPalette.java
# Base upstream: assets/%(base)s + ThemeColors.createDefaultColors()
# Rigenerare dopo ogni rebase: python3 Tools/morsegram_assets_gen.py themes
"""


def build_theme(title, base_asset, overrides, dark, palette, key_names, defaults, exclusions):
    base = parse_attheme(os.path.join(LIB, "assets", base_asset))
    name_of = dict(key_names)
    valid = set(name_of.values())

    for name in overrides:
        if name not in valid:
            die('override "%s" non e\' una chiave di tema valida (ThemeColors.createColorKeysMap)' % name)

    amber_a, amber_r, amber_g, amber_b = argb(palette["AMBER"])
    amber_hue = colorsys.rgb_to_hsv(amber_r / 255.0, amber_g / 255.0, amber_b / 255.0)[0] * 360.0

    lines = [HEADER % {"title": title, "base": base_asset}]
    written = 0
    # si scorrono TUTTE le chiavi note al parser di upstream, non solo quelle con un default:
    # chat_wallpaper, chat_serviceBackground e altre esistono solo nel .attheme di partenza.
    for key in sorted(name_of, key=lambda k: name_of[k]):
        name = name_of[key]
        if name == "wallpaperFileOffset":
            continue
        if name in overrides:
            scelto = overrides[name]
            # di norma e' un nome della palette; si accetta anche un colore scritto a mano
            # (#AARRGGBB) per i pochi casi misurati sul contrasto, come la barra a quattro voci
            value = int(scelto[1:], 16) if scelto.startswith("#") else palette[scelto]
        else:
            if name in base:
                value = base[name]
            elif key in defaults:
                value = defaults[key]
            else:
                continue  # nessun valore noto (premium* non letterali): resta il default upstream
            if key not in exclusions:  # avatar, premium, storie, grafici, chiamate: invariati
                value = amberize(value, amber_hue, dark)
        a, r, g, b = argb(value)
        lines.append("%s=#%02X%02X%02X%02X" % (name, a, r, g, b))
        written += 1
    if written < 700:
        die("solo %d chiavi scritte per %s" % (written, title))
    return "\n".join(lines) + "\n"


def gen_themes(check):
    palette = parse_palette()
    key_names = parse_key_names()
    defaults, skipped = parse_default_colors(palette)
    exclusions = parse_exclusions()

    jobs = [
        ("morsegram.attheme", "Morsegram (tema chiaro)", "bluebubbles.attheme", OVERRIDES_LIGHT, False),
        ("morsegram_night.attheme", "Morsegram notte (tema scuro)", "darkblue.attheme", OVERRIDES_NIGHT, True),
    ]
    changed = []
    for out_name, title, base, overrides, dark in jobs:
        text = build_theme(title, base, overrides, dark, palette, key_names, defaults, exclusions)
        path = os.path.join(APP, "assets", out_name)
        if check:
            if not os.path.exists(path) or read(path) != text:
                changed.append(path)
            continue
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(text)
        # controllo di sicurezza: windowBackgroundWhite decide isDark() lato app (Theme.checkIsDark)
        bg = palette[overrides["windowBackgroundWhite"]]
        _, r, g, b = argb(bg)
        is_dark = luminance(r, g, b) < 0.5
        if is_dark != dark:
            die("%s: windowBackgroundWhite non e' coerente con isDark()=%s" % (out_name, dark))
        print("%s: %d chiavi (%d default non letterali ignorati)" % (out_name, text.count("\n") - 5, len(skipped)))
    return changed


# --------------------------------------------------------------------------- icone

DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def build_png(width, height, pixels):
    """Byte di una PNG RGBA senza dipendenze: pixels e' una lista di righe di tuple (r,g,b,a)."""
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for r, g, b, a in row:
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        return out + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    return bytes(png)


def write_png(path, blob):
    """La sola scrittura dei byte resi da build_png (la modalita' check li confronta invece)."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as fh:
        fh.write(blob)


def png_pixel_data(blob):
    """(IHDR, IDAT decompresso) di una PNG; None se i byte non sono una PNG leggibile.

    Si confrontano i dati DECOMPRESSI e non i byte del file perche' zlib.compress rende byte
    diversi a parita' di pixel al variare della versione di zlib da una macchina all'altra:
    un confronto sui byte compressi direbbe "non aggiornato" su mezzo repo a ogni cambio di zlib.
    """
    if not blob.startswith(b"\x89PNG\r\n\x1a\n"):
        return None
    ihdr, idat, pos = None, bytearray(), 8
    while pos + 8 <= len(blob):
        length = struct.unpack(">I", blob[pos:pos + 4])[0]
        tag = blob[pos + 4:pos + 8]
        data = blob[pos + 8:pos + 8 + length]
        if len(data) != length:
            return None
        if tag == b"IHDR":
            ihdr = data
        elif tag == b"IDAT":
            idat += data
        elif tag == b"IEND":
            break
        pos += 12 + length
    if ihdr is None:
        return None
    try:
        return ihdr, zlib.decompress(bytes(idat))
    except zlib.error:
        return None


def png_matches(path, blob):
    """Vero se la PNG su disco ha gli stessi pixel di `blob`. E' QUESTO il controllo che serve alla
    modalita' check: os.path.exists non si accorge di una PNG rimasta a un disegno vecchio, ed e'
    esattamente cosi' che le PNG legacy di ic_launcher erano divergite in silenzio (ambra vecchia
    nelle bitmap, ambra nuova nei vettori)."""
    if not os.path.exists(path):
        return False
    with open(path, "rb") as fh:
        return png_pixel_data(fh.read()) == png_pixel_data(blob)


# Geometria del segno "punto linea" (lettera A in Morse), misurata su docs/img/logo-1024.png
# e riespressa in unita' di raggio: raggio 3, punto centrato in x=3, spazio 2,3, linea lunga 9,7.
# Riquadro del segno: x 0..18, y 9..15 su una griglia alta 24. Stesse proporzioni del logo e di
# drawable/morsegram_icon_fg.xml, ma con lo spazio fra punto e linea che il logo ha davvero.
MARK_R = 3.0
MARK_DOT_CX = 3.0
MARK_DASH_X0 = 8.3 + MARK_R   # centro della calotta sinistra della linea
MARK_DASH_X1 = 18.0 - MARK_R  # centro della calotta destra
MARK_W = 18.0
MARK_H = 2.0 * MARK_R
MARK_CY = 12.0


def mark_coverage(px, py):
    """1 se il punto (px, py), in unita' del segno, cade dentro il segno."""
    if (px - MARK_DOT_CX) ** 2 + (py - MARK_CY) ** 2 <= MARK_R * MARK_R:
        return True
    if MARK_CY - MARK_R <= py <= MARK_CY + MARK_R:
        if MARK_DASH_X0 <= px <= MARK_DASH_X1:
            return True
        if px < MARK_DASH_X0 and (px - MARK_DASH_X0) ** 2 + (py - MARK_CY) ** 2 <= MARK_R * MARK_R:
            return True
        if px > MARK_DASH_X1 and (px - MARK_DASH_X1) ** 2 + (py - MARK_CY) ** 2 <= MARK_R * MARK_R:
            return True
    return False


def render_mark(width, height, fg, bg=None, circle=False, fill=0.85, ss=4):
    """Disegna il segno Morsegram, centrato, su fondo trasparente / pieno / cerchio pieno.

    fill = frazione del lato occupata dal segno. ss = campioni per lato (antialias).
    """
    unit = min(width * fill / MARK_W, height * fill / MARK_H)
    ox = (width - MARK_W * unit) / 2.0
    oy = (height - MARK_H * unit) / 2.0 - (MARK_CY - MARK_R) * unit
    fr, fgc, fb = fg
    cx, cy, rad = width / 2.0, height / 2.0, min(width, height) / 2.0
    pixels = []
    for y in range(height):
        row = []
        for x in range(width):
            hits = 0
            inside = 0
            for sy in range(ss):
                for sx in range(ss):
                    gx, gy = x + (sx + 0.5) / ss, y + (sy + 0.5) / ss
                    in_shape = True
                    if circle:
                        in_shape = (gx - cx) ** 2 + (gy - cy) ** 2 <= rad * rad
                    if in_shape:
                        inside += 1
                        if mark_coverage((gx - ox) / unit, (gy - oy) / unit):
                            hits += 1
            total = float(ss * ss)
            cov, cover_shape = hits / total, inside / total
            if bg is None:
                row.append((fr, fgc, fb, int(round(255 * cov * (1.0 if not circle else cover_shape)))))
            else:
                br, bgc, bb = bg
                row.append((
                    int(round(br + (fr - br) * (cov / cover_shape if cover_shape else 0))),
                    int(round(bgc + (fgc - bgc) * (cov / cover_shape if cover_shape else 0))),
                    int(round(bb + (fb - bb) * (cov / cover_shape if cover_shape else 0))),
                    int(round(255 * cover_shape)),
                ))
        pixels.append(row)
    return pixels


# Logotipo "Morsegram": sostituisce il lettering "Telegram" (drawable/telegram_logo.xml, usato come
# titolo della pagina 1 della schermata introduttiva, e drawable/telegram_logo_2.xml, usato come
# titolo della lista chat e dell'intestazione storie). Upstream li disegna in bianco e li tinge a
# runtime in MULTIPLY, quindi anche i nostri sono bianchi su trasparente.
# PROPORZIONI: le due viewport upstream (249x57 e 269x61) vengono rispettate al pixel, perche' i
# chiamanti impongono i bounds calcolati su quelle (IntroActivity:157 usa 115x26,3 dp = 249:57, e
# DialogsActivity:3516 usa getIntrinsicWidth/Height). Il testo viene centrato dentro quel riquadro.
# FONT: rmedium.ttf / rextrabold.ttf sono gia' nel repo (TMessagesProj/src/main/assets/fonts),
# quindi la generazione e' riproducibile su qualunque macchina, senza font di sistema.
WORDMARK = "Morsegram"
WORDMARK_FONT = os.path.join(LIB, "assets", "fonts", "rextrabold.ttf")
WORDMARK_JOBS = [
    # (nome risorsa NOSTRA, larghezza dp, altezza dp, drawable upstream che la indossa)
    ("morsegram_wordmark", 83.0, 19.0, "telegram_logo"),
    ("morsegram_wordmark_2", 90.0, 20.408922, "telegram_logo_2"),
]

# IL PUNTO DELICATO (verificato smontando l'APK, non dedotto): TMessagesProj/.../telegram_logo.xml usa
# android:fillType, che e' API 24, quindi AGP PRIMA del merge lo riscrive in
#   drawable-anydpi-v24/telegram_logo.xml  +  drawable-{mdpi..xxhdpi}-v4/telegram_logo.png
# Un nostro PNG di densita' perde comunque, perche' "anydpi" ha la precedenza su QUALSIASI densita', e un
# nostro drawable/telegram_logo.xml perde pure, perche' ha un set di qualificatori diverso da entrambi.
# Si mette quindi lo stesso <bitmap> in TUTTI i contenitori che AGP ha prodotto: anydpi-v24 per API 24+,
# le cinque densita' per API 23 (il nostro minSdk), piu' il contenitore base. Il PNG vero sta una volta
# sola, sotto il nostro nome, e getIntrinsicWidth/Height continuano a funzionare (DialogsActivity li usa).
WORDMARK_BUCKETS = ["drawable", "drawable-anydpi-v24"] + ["drawable-" + d for d in DENSITIES]
WORDMARK_SHIM = """<?xml version="1.0" encoding="utf-8"?>
<!-- <morsegram> GENERATO da Tools/morsegram_assets_gen.py: prende il posto del lettering "Telegram" di
     TMessagesProj/src/main/res/drawable/%(upstream)s.xml (titolo della lista chat, intestazione storie e
     titolo della pagina 1 della schermata introduttiva). Bitmap e non vettore di proposito: il vettore
     upstream usa android:fillType, quindi AGP lo riscrive in drawable-anydpi-v24 piu' PNG di densita':
     lo stesso file va replicato in tutti quei contenitori, altrimenti vince il lettering di Telegram.
     Bianco su trasparente, perche' i chiamanti lo tingono a runtime in PorterDuff.MULTIPLY. </morsegram> -->
<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
    android:src="@drawable/%(ours)s"
    android:gravity="fill" />
"""


def render_wordmark(width, height):
    """Il logotipo bianco su trasparente, centrato, alto quanto il riquadro lo consente."""
    from PIL import Image, ImageDraw, ImageFont  # solo per i logotipi: il resto non ha dipendenze

    size = max(8, int(height * 1.6))
    for _ in range(80):
        font = ImageFont.truetype(WORDMARK_FONT, size)
        box = font.getbbox(WORDMARK)
        tw, th = box[2] - box[0], box[3] - box[1]
        if tw <= width * 0.98 and th <= height * 0.86:
            break
        size -= 1
    img = Image.new("RGBA", (width, height), (255, 255, 255, 0))
    draw = ImageDraw.Draw(img)
    box = font.getbbox(WORDMARK)
    draw.text(
        ((width - (box[2] - box[0])) / 2.0 - box[0], (height - (box[3] - box[1])) / 2.0 - box[1]),
        WORDMARK,
        font=font,
        fill=(255, 255, 255, 255),
    )
    return img


def wordmark_matches(path, img):
    """Vero se la PNG su disco ha gli stessi pixel del logotipo appena reso. I logotipi li scrive
    Pillow con optimize=True, che sceglie i filtri riga per riga: qui si confrontano i pixel
    DECODIFICATI e non l'IDAT decompresso come per le PNG disegnate da noi."""
    from PIL import Image

    if not os.path.exists(path):
        return False
    try:
        with Image.open(path) as on_disk:
            return (on_disk.size == img.size
                    and on_disk.convert("RGBA").tobytes() == img.convert("RGBA").tobytes())
    except OSError:
        return False


def gen_wordmarks(check):
    changed = []
    for name, dp_w, dp_h, upstream in WORDMARK_JOBS:
        for density, factor in sorted(DENSITIES.items()):
            path = os.path.join(APP, "res", "drawable-" + density, name + ".png")
            img = render_wordmark(int(round(dp_w * factor)), int(round(dp_h * factor)))
            if check:
                if not wordmark_matches(path, img):
                    changed.append(path)
                continue
            os.makedirs(os.path.dirname(path), exist_ok=True)
            img.save(path, "PNG", optimize=True)
        shim = WORDMARK_SHIM % {"upstream": upstream, "ours": name}
        for bucket in WORDMARK_BUCKETS:
            shim_path = os.path.join(APP, "res", bucket, upstream + ".xml")
            if check:
                if not os.path.exists(shim_path) or read(shim_path) != shim:
                    changed.append(shim_path)
                continue
            os.makedirs(os.path.dirname(shim_path), exist_ok=True)
            with open(shim_path, "w", encoding="utf-8") as fh:
                fh.write(shim)
        if not check:
            print("%s.png (%s) + %s.xml in %d contenitori"
                  % (name, ", ".join(sorted(DENSITIES)), upstream, len(WORDMARK_BUCKETS)))
    return changed


def gen_icons(check):
    palette = parse_palette()
    _, ar, ag, ab = argb(palette["AMBER"])
    _, kr, kg, kb = argb(palette["BLACK"])

    # (nome, densita' -> lato in px, primo piano, fondo, cerchio, scala del segno)
    jobs = [
        # icona di stato: silhouette BIANCA su trasparente, il sistema la usa come maschera
        ("notification", {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72}, (255, 255, 255), None, False, 0.86),
        # pastiglia sulle scorciatoie chat della home: ambra su nero caldo, al posto dell'aeroplanino
        ("book_logo", {"mdpi": 15, "hdpi": 22, "xhdpi": 30, "xxhdpi": 45, "xxxhdpi": 60}, (ar, ag, ab), (kr, kg, kb), True, 0.66),
    ]
    changed = []
    for name, sizes, fg, bg, circle, fill in jobs:
        for density, side in sizes.items():
            path = os.path.join(APP, "res", "drawable-" + density, name + ".png")
            blob = build_png(side, side, render_mark(side, side, fg, bg, circle, fill))
            if check:
                if not png_matches(path, blob):
                    changed.append(path)
                continue
            write_png(path, blob)
        if not check:
            print("%s.png: %s" % (name, ", ".join(sorted(sizes))))

    # texture dell'introduzione: il segno ambra sostituisce l'aeroplanino, stesse dimensioni upstream
    plane = {"mdpi": (82, 74), "hdpi": (123, 111), "xhdpi": (164, 148), "xxhdpi": (246, 222)}
    for density, (w, h) in plane.items():
        path = os.path.join(APP, "res", "drawable-" + density, "intro_tg_plane.png")
        blob = build_png(w, h, render_mark(w, h, (kr, kg, kb), None, False, 0.92))
        if check:
            if not png_matches(path, blob):
                changed.append(path)
            continue
        write_png(path, blob)
    if not check:
        print("intro_tg_plane.png: %s" % ", ".join(sorted(plane)))
    return changed


# --------------------------------------------------------------------- icone del launcher

# Geometria condivisa da TUTTE le icone (predefinita e varianti): un solo posto da ritoccare se il
# logo cambia. Prima di questo job le PNG legacy di ic_launcher erano fatte a mano e avevano ancora
# l'ambra vecchia #F5A623 e il nero #1D1D1F, mentre i vettori erano gia' #F0A020 / #101010: era il
# solo pezzo dell'identita' fuori dal generatore, e infatti divergeva in silenzio.
LAUNCHER_FILL_ADAPTIVE = 0.43   # frazione dei 108 occupata dal segno (zona sicura: 66 su 108)
LAUNCHER_FILL_LEGACY = 0.58     # PNG API 24-25: si vede tutto il quadrato, il segno sta piu' largo
LAUNCHER_RADIUS = 0.20          # raggio degli angoli delle PNG legacy, in frazione del lato
LAUNCHER_STROKE_108 = 4.3       # spessore del contorno della variante "Contorno", in unita' di 108

# Il segno in unita' proprie: riquadro (larghezza, altezza) e primitive, con l'origine in alto a
# sinistra del riquadro. "dot" = cerchio (cx, cy, r); "dash" = capsula (centri delle calotte x0 e
# x1, poi cy, r). Le misure vengono da MARK_* (misurate su docs/img/logo-1024.png).
MARK_A = (MARK_W, MARK_H, (("dot", MARK_DOT_CX, MARK_R, MARK_R),
                           ("dash", MARK_DASH_X0, MARK_DASH_X1, MARK_R, MARK_R)))
# "M" in Morse: due linee. Stessa unita' delle altre (stesso raggio, stesso spessore), quindi il
# riquadro e' piu' largo: due linee da 9,7 piu' lo spazio di 2,3 = 21,7.
MARK_M = (21.7, MARK_H, (("dash", MARK_R, 9.7 - MARK_R, MARK_R, MARK_R),
                         ("dash", 12.0 + MARK_R, 21.7 - MARK_R, MARK_R, MARK_R)))

# Le sei icone. slot = base del nome delle risorse upstream che la variante indossa: gli
# activity-alias del manifest upstream le puntano (DefaultIcon non ha android:icon e eredita quella
# dell'applicazione, VintageIcon -> icon_6_launcher, AquaIcon -> icon_4, PremiumIcon -> icon_3,
# TurboIcon -> icon_5, NoxIcon -> icon_2). Sovrascrivere la risorsa alla STESSA configurazione
# (stessa cartella, stesso nome) e' deterministico: il modulo app vince in fase di merge e il file
# upstream non entra nemmeno nell'APK, quindi gli aeroplanini non ci sono piu' (ToS 2.4) e si
# risparmiano i 443 KB delle loro PNG senza dipendere da shrinkResources.
# campi: chiave, slot, alias, segno, ruotato, contorno, fondo, segno, anteprima fondo, anteprima segno
LAUNCHER_VARIANTS = (
    ("default", "ic_launcher",     "DefaultIcon", MARK_A, False, False, "AMBER", "BLACK",
     ("xml", "icon_background_sa"), "icon_foreground_sa"),
    ("night",   "icon_6_launcher", "VintageIcon", MARK_A, False, False, "BLACK", "AMBER",
     ("xml", "icon_6_background_sa"), "icon_6_foreground_sa"),
    ("ivory",   "icon_4_launcher", "AquaIcon",    MARK_A, False, False, "IVORY", "BLACK",
     ("xml", "icon_4_background_sa"), None),
    ("outline", "icon_3_launcher", "PremiumIcon", MARK_A, False, True,  "BLACK", "AMBER",
     ("xml", "icon_3_background_sa"), "icon_3_foreground_sa"),
    ("key",     "icon_5_launcher", "TurboIcon",   MARK_A, True,  False, "SAND",  "BLACK",
     ("xml", "icon_5_background_sa"), "icon_5_foreground_sa"),
    ("em",      "icon_2_launcher", "NoxIcon",     MARK_M, False, False, "AMBER", "BLACK",
     ("png", "icon_2_background_sa"), None),
)

# Il monocromatico (icona a tema di Android 13+) dipende solo dalla SILHOUETTE: chi ha lo stesso
# segno condivide il file, perche' il sistema lo ricolora comunque.
LAUNCHER_MONO = {"default": "morsegram_icon_mono", "night": "morsegram_icon_mono",
                 "ivory": "morsegram_icon_mono", "outline": "morsegram_icon_outline_mono",
                 "key": "morsegram_icon_key_mono", "em": "morsegram_icon_em_mono"}

# dimensioni delle PNG: launcher legacy (API 24-25) e anteprime _sa (griglia 108, come upstream)
LAUNCHER_PNG = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
PREVIEW_PNG = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

UNIT108 = 108.0 * LAUNCHER_FILL_ADAPTIVE / MARK_W          # 1 unita' di segno in viewport 108
STROKE_UNITS = LAUNCHER_STROKE_108 / UNIT108               # spessore del contorno in unita' di segno

XML_HEAD = '<?xml version="1.0" encoding="utf-8"?>\n'
# niente "--" nel testo: un commento XML non lo ammette e aapt2 rifiuta il file
GEN_NOTE = ("<!-- <morsegram> GENERATO da Tools/morsegram_assets_gen.py (job launcher): %s.\n"
            "     Non si modifica a mano: la modalita' check del generatore fallisce. </morsegram> -->\n")


def _cov(parts, px, py, inset=0.0):
    """Vero se il punto (px, py), in unita' del segno, cade dentro il segno ridotto di `inset`."""
    for part in parts:
        if part[0] == "dot":
            _, cx, cy, r = part
            rr = r - inset
            if rr > 0 and (px - cx) ** 2 + (py - cy) ** 2 <= rr * rr:
                return True
        else:
            _, x0, x1, cy, r = part
            rr = r - inset
            if rr <= 0:
                continue
            if cy - rr <= py <= cy + rr:
                if x0 <= px <= x1:
                    return True
                if px < x0 and (px - x0) ** 2 + (py - cy) ** 2 <= rr * rr:
                    return True
                if px > x1 and (px - x1) ** 2 + (py - cy) ** 2 <= rr * rr:
                    return True
    return False


def _hit(parts, rot, hollow, px, py):
    if rot:
        px, py = py, px
    if not _cov(parts, px, py):
        return False
    return not (hollow and _cov(parts, px, py, STROKE_UNITS))


def render_icon(side, shape, rot, hollow, fg, bg, fill, kind, ss=4):
    """Icona quadrata di `side` px. kind: 'square' (angoli arrotondati), 'circle', 'mark' (segno su
    trasparente, per le anteprime), 'flat' (solo fondo pieno). fg/bg = (r, g, b)."""
    parts = shape[2]
    unit = side * fill / MARK_W                  # stessa unita' per tutte: stesso raggio, stesso tratto
    box_w, box_h = (shape[1] * unit, shape[0] * unit) if rot else (shape[0] * unit, shape[1] * unit)
    ox, oy = (side - box_w) / 2.0, (side - box_h) / 2.0
    rad = LAUNCHER_RADIUS * side
    fr, fgc, fb = fg
    br, bgc, bb = bg if bg else (0, 0, 0)
    pixels = []
    for y in range(side):
        row = []
        for x in range(side):
            hits = inside = 0
            for sy in range(ss):
                for sx in range(ss):
                    gx, gy = x + (sx + 0.5) / ss, y + (sy + 0.5) / ss
                    if kind == "square":
                        dx = max(rad - gx, gx - (side - rad), 0.0)
                        dy = max(rad - gy, gy - (side - rad), 0.0)
                        in_shape = dx * dx + dy * dy <= rad * rad
                    elif kind == "circle":
                        in_shape = (gx - side / 2.0) ** 2 + (gy - side / 2.0) ** 2 <= (side / 2.0) ** 2
                    else:
                        in_shape = True
                    if not in_shape:
                        continue
                    inside += 1
                    if kind != "flat" and _hit(parts, rot, hollow, (gx - ox) / unit, (gy - oy) / unit):
                        hits += 1
            total = float(ss * ss)
            cov, shp = hits / total, inside / total
            if kind == "mark":
                row.append((fr, fgc, fb, int(round(255 * cov))))
            else:
                k = cov / shp if shp else 0.0
                row.append((int(round(br + (fr - br) * k)), int(round(bgc + (fgc - bgc) * k)),
                            int(round(bb + (fb - bb) * k)), int(round(255 * shp))))
        pixels.append(row)
    return pixels


def _num(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


def _paths(shape, rot, hollow):
    """pathData dei vettori, viewport 108. Col contorno il tracciato e' la mezzeria del tratto."""
    inset = STROKE_UNITS / 2.0 if hollow else 0.0
    box_w = (shape[1] if rot else shape[0]) * UNIT108
    box_h = (shape[0] if rot else shape[1]) * UNIT108
    ox, oy = (108.0 - box_w) / 2.0, (108.0 - box_h) / 2.0
    out = []
    for part in shape[2]:
        if part[0] == "dot":
            _, cx, cy, r = part
            r = (r - inset) * UNIT108
            px, py = (cy, cx) if rot else (cx, cy)
            x, y = ox + px * UNIT108, oy + py * UNIT108
            out.append("M%s,%s m-%s,0 a%s,%s 0 1,0 %s,0 a%s,%s 0 1,0 -%s,0"
                       % (_num(x), _num(y), _num(r), _num(r), _num(r), _num(2 * r),
                          _num(r), _num(r), _num(2 * r)))
        else:
            _, a0, a1, cy, r = part
            r = (r - inset) * UNIT108
            if rot:
                cx = ox + cy * UNIT108
                y0, y1 = oy + a0 * UNIT108, oy + a1 * UNIT108
                out.append("M%s,%s A%s,%s 0 0 1 %s,%s V%s A%s,%s 0 0 1 %s,%s Z"
                           % (_num(cx - r), _num(y0), _num(r), _num(r), _num(cx + r), _num(y0),
                              _num(y1), _num(r), _num(r), _num(cx - r), _num(y1)))
            else:
                yc = oy + cy * UNIT108
                x0, x1 = ox + a0 * UNIT108, ox + a1 * UNIT108
                out.append("M%s,%s H%s A%s,%s 0 0 1 %s,%s H%s A%s,%s 0 0 1 %s,%s Z"
                           % (_num(x0), _num(yc - r), _num(x1), _num(r), _num(r), _num(x1),
                              _num(yc + r), _num(x0), _num(r), _num(r), _num(x0), _num(yc - r)))
    return out


def _vector(note, paths, color, hollow):
    body = "".join(
        ('    <path android:fillColor="#00000000" android:strokeColor="%s" android:strokeWidth="%s"\n'
         '        android:pathData="%s" />\n' % (color, _num(LAUNCHER_STROKE_108), p)) if hollow
        else '    <path android:fillColor="%s" android:pathData="%s" />\n' % (color, p)
        for p in paths)
    return (XML_HEAD + GEN_NOTE % note +
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n' + body + '</vector>\n')


def _flat_vector(note, color):
    return (XML_HEAD + GEN_NOTE % note +
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'
            '    <path android:fillColor="%s" android:pathData="M0,0h108v108h-108z" />\n'
            '</vector>\n' % color)


def _adaptive(note, base, mono):
    return (XML_HEAD + GEN_NOTE % note +
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/%s_bg" />\n'
            '    <foreground android:drawable="@drawable/%s_fg" />\n'
            '    <monochrome android:drawable="@drawable/%s" />\n'
            '</adaptive-icon>\n' % (base, base, mono))


def _solid_shape(note, color):
    return (XML_HEAD + GEN_NOTE % note +
            '<shape xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <solid android:color="%s" />\n'
            '</shape>\n' % color)


def _write_xml(path, text, check, changed):
    if check:
        if not os.path.exists(path) or read(path) != text:
            changed.append(path)
        return
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def _write_png(path, side, pixels_fn, check, changed):
    blob = build_png(side, side, pixels_fn())
    if check:
        if not png_matches(path, blob):
            changed.append(path)
        return
    write_png(path, blob)


def gen_launcher(check):
    """Icona dell'app e cinque varianti: vettori adattivi (fondo, segno, monocromatico), PNG legacy
    per API 24-25 e anteprime _sa del selettore upstream. Le varianti sovrascrivono, alla stessa
    configurazione, le risorse che gli activity-alias di upstream indossano."""
    palette = parse_palette()
    changed = []
    for key, slot, alias, shape, rot, hollow, bgname, fgname, prev_bg, prev_fg in LAUNCHER_VARIANTS:
        bg_hex = "#%06X" % (palette[bgname] & 0xFFFFFF)
        fg_hex = "#%06X" % (palette[fgname] & 0xFFFFFF)
        bg, fg = argb(palette[bgname])[1:], argb(palette[fgname])[1:]
        base = "morsegram_icon" if key == "default" else "morsegram_icon_" + key
        mono = LAUNCHER_MONO[key]

        _write_xml(os.path.join(APP, "res", "drawable", base + "_bg.xml"),
                   _flat_vector("fondo della variante %s (%s)" % (key, bgname), bg_hex), check, changed)
        _write_xml(os.path.join(APP, "res", "drawable", base + "_fg.xml"),
                   _vector("segno della variante %s: %s su %s" % (key, fgname, bgname),
                           _paths(shape, rot, hollow), fg_hex, hollow), check, changed)
        if mono == base + "_mono":
            _write_xml(os.path.join(APP, "res", "drawable", mono + ".xml"),
                       _vector("silhouette per l'icona a tema di Android 13+ (variante %s)" % key,
                               _paths(shape, rot, hollow), "#000000", hollow), check, changed)

        for suffix in ("", "_round"):
            _write_xml(os.path.join(APP, "res", "mipmap-anydpi-v26", slot + suffix + ".xml"),
                       _adaptive("icona adattiva dello slot %s (alias %s)" % (slot, alias), base, mono),
                       check, changed)

        for density, side in LAUNCHER_PNG.items():
            for suffix, kind in (("", "square"), ("_round", "circle")):
                _write_png(os.path.join(APP, "res", "mipmap-" + density, slot + suffix + ".png"), side,
                           lambda s=side, k=kind: render_icon(s, shape, rot, hollow, fg, bg,
                                                              LAUNCHER_FILL_LEGACY, k),
                           check, changed)

        kind_bg, name_bg = prev_bg
        if kind_bg == "xml":
            _write_xml(os.path.join(APP, "res", "drawable", name_bg + ".xml"),
                       _solid_shape("anteprima: fondo della variante %s" % key, bg_hex), check, changed)
        else:
            for density, side in PREVIEW_PNG.items():
                _write_png(os.path.join(APP, "res", "mipmap-" + density, name_bg + ".png"), side,
                           lambda s=side: render_icon(s, shape, rot, hollow, fg, bg, 0.43, "flat", ss=1),
                           check, changed)
        if prev_fg:
            for density, side in PREVIEW_PNG.items():
                _write_png(os.path.join(APP, "res", "mipmap-" + density, prev_fg + ".png"), side,
                           lambda s=side: render_icon(s, shape, rot, hollow, fg, None,
                                                      LAUNCHER_FILL_ADAPTIVE, "mark",
                                                      ss=(4 if s <= 216 else 2)),
                           check, changed)
        if not check:
            print("icona %s -> %s (%s): vettori + PNG %s" % (key, slot, alias, ", ".join(sorted(LAUNCHER_PNG))))
    return changed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("what", nargs="?", default="all", choices=["all", "themes", "icons", "wordmarks", "launcher"])
    parser.add_argument("--check", action="store_true", help="non scrive, esce != 0 se l'uscita cambierebbe")
    args = parser.parse_args()

    changed = []
    if args.what in ("all", "themes"):
        changed += gen_themes(args.check)
    if args.what in ("all", "icons"):
        changed += gen_icons(args.check)
    if args.what in ("all", "launcher"):
        changed += gen_launcher(args.check)
    if args.what in ("all", "wordmarks"):
        changed += gen_wordmarks(args.check)
    if args.what in ("all", "themes") and not args.check:
        # I temi generati non reggevano il contrasto: nel tema chiaro otto coppie sotto
        # soglia, l'ora sui messaggi a 2,09 contro i 4,5 richiesti, cioe' illeggibile.
        # La correzione va rifatta a ogni rigenerazione, altrimenti torna il difetto.
        import subprocess
        import glob
        temi = sorted(glob.glob(os.path.join(ROOT, "TMessagesProj_App/src/main/assets", "morsegram*.attheme")))
        if temi:
            subprocess.run([sys.executable, "-I", os.path.join(ROOT, "tools/contrasto_fix.py"), *temi], check=False)
            subprocess.run([sys.executable, "-I", os.path.join(ROOT, "tools/contrasto.py"), *temi], check=False)

    if args.check and changed:
        for path in changed:
            sys.stderr.write("non aggiornato: %s\n" % path)
        sys.exit(2)


if __name__ == "__main__":
    main()

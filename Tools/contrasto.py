#!/usr/bin/env python3
"""Misura il contrasto delle coppie testo/fondo dei temi Morsegram.

Il criterio e' quello delle linee guida di accessibilita': 4.5:1 per il testo normale,
3:1 per i titoli grandi e per gli elementi non testuali (icone, bordi).
Nasce da una segnalazione: 'non si vede bene l'UI'. Meglio misurare che indovinare.
"""
import re, sys, pathlib

COPPIE = [
    ("testo principale",        "windowBackgroundWhite", "windowBackgroundWhiteBlackText", 4.5),
    ("testo secondario",        "windowBackgroundWhite", "windowBackgroundWhiteGrayText", 4.5),
    ("testo secondario 2",      "windowBackgroundWhite", "windowBackgroundWhiteGrayText2", 4.5),
    ("testo secondario 3",      "windowBackgroundWhite", "windowBackgroundWhiteGrayText3", 4.5),
    ("testo secondario 4",      "windowBackgroundWhite", "windowBackgroundWhiteGrayText4", 4.5),
    ("link",                    "windowBackgroundWhite", "windowBackgroundWhiteLinkText", 4.5),
    ("testo blu (accento)",     "windowBackgroundWhite", "windowBackgroundWhiteBlueText", 4.5),
    ("titolo barra",            "actionBarDefault", "actionBarDefaultTitle", 3.0),
    ("sottotitolo barra",       "actionBarDefault", "actionBarDefaultSubtitle", 4.5),
    ("icone barra",             "actionBarDefault", "actionBarDefaultIcon", 3.0),
    ("ricerca nella barra",     "actionBarDefaultSearch", "actionBarDefaultSearchPlaceholder", 4.5),
    ("nome chat",               "windowBackgroundWhite", "chats_name", 4.5),
    ("anteprima messaggio",     "windowBackgroundWhite", "chats_message", 4.5),
    ("ora nella lista",         "windowBackgroundWhite", "chats_date", 4.5),
    ("badge non letti",         "chats_unreadCounter", "chats_unreadCounterText", 4.5),
    ("messaggio ricevuto",      "chat_inBubble", "chat_messageTextIn", 4.5),
    ("messaggio inviato",       "chat_outBubble", "chat_messageTextOut", 4.5),
    ("ora sul messaggio",       "chat_outBubble", "chat_outTimeText", 4.5),
    ("ora sul ricevuto",        "chat_inBubble", "chat_inTimeText", 4.5),
    ("testo dialoghi",          "dialogBackground", "dialogTextBlack", 4.5),
    ("testo grigio dialoghi",   "dialogBackground", "dialogTextGray2", 4.5),
    ("pulsante dialoghi",       "dialogBackground", "dialogTextBlue", 4.5),
    # la fascia in alto dei dialoghi ospita un'animazione, non testo: non e' una coppia reale
    ("testo su pulsante",       "featuredStickers_addButton", "featuredStickers_buttonText", 4.5),
    ("campo di scrittura",      "chat_messagePanelBackground", "chat_messagePanelText", 4.5),
    ("suggerimento scrittura",  "chat_messagePanelBackground", "chat_messagePanelHint", 4.5),
    ("voce impostazioni",       "windowBackgroundWhite", "windowBackgroundWhiteValueText", 4.5),
    ("intestazione sezione",    "windowBackgroundGray", "windowBackgroundWhiteBlueHeader", 4.5),
    ("testo su grigio",         "windowBackgroundGray", "windowBackgroundWhiteGrayText4", 4.5),
]

def leggi(path):
    t = {}
    for riga in pathlib.Path(path).read_text().splitlines():
        m = re.match(r"([A-Za-z_0-9]+)=(#?[0-9A-Fa-fx-]+)\s*$", riga.strip())
        if not m: continue
        chiave, val = m.group(1), m.group(2)
        if val.startswith("#"):
            v = int(val[1:], 16)
        else:
            try: v = int(val) & 0xFFFFFFFF
            except ValueError: continue
        t[chiave] = ((v >> 16) & 255, (v >> 8) & 255, v & 255)
    return t

def luminanza(c):
    def canale(x):
        x /= 255
        return x / 12.92 if x <= 0.03928 else ((x + 0.055) / 1.055) ** 2.4
    r, g, b = (canale(v) for v in c)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b

def rapporto(a, b):
    la, lb = luminanza(a), luminanza(b)
    chiaro, scuro = max(la, lb), min(la, lb)
    return (chiaro + 0.05) / (scuro + 0.05)

for path in sys.argv[1:]:
    t = leggi(path)
    print(f"\n=== {pathlib.Path(path).name} ({len(t)} colori) ===")
    bocciati = []
    for nome, kf, kt, soglia in COPPIE:
        if kf not in t or kt not in t:
            print(f"  {nome:26} chiave mancante ({kf if kf not in t else kt})")
            continue
        r = rapporto(t[kf], t[kt])
        esito = "ok" if r >= soglia else "SOTTO SOGLIA"
        if r < soglia: bocciati.append((nome, r, soglia, kf, kt))
        print(f"  {nome:26} {r:5.2f}:1  (serve {soglia})  {esito}")
    if bocciati:
        print(f"\n  {len(bocciati)} coppie da correggere:")
        for nome, r, s, kf, kt in bocciati:
            print(f"    {nome}: {r:.2f}:1 invece di {s} — {kt} su {kf}")

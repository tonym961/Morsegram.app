#!/usr/bin/env python3
"""Scurisce (o schiarisce) il minimo necessario i colori di testo che non reggono il contrasto.

Conserva la tinta: si muove solo la luminosita', a passi piccoli, finche' la coppia passa.
Cosi' l'ambra resta ambra e i grigi restano grigi, ma si leggono.
"""
import re, sys, pathlib, colorsys

def lum(c):
    def ch(x):
        x /= 255
        return x/12.92 if x <= 0.03928 else ((x+0.055)/1.055)**2.4
    r,g,b = (ch(v) for v in c)
    return 0.2126*r + 0.7152*g + 0.0722*b

def ratio(a,b):
    la,lb = lum(a), lum(b)
    return (max(la,lb)+0.05)/(min(la,lb)+0.05)

def aggiusta(testo, fondo, soglia):
    """Muove il testo verso il nero o il bianco, secondo quale fondo ha."""
    verso_scuro = lum(fondo) > lum(testo) or lum(fondo) > 0.5
    h,l,s = colorsys.rgb_to_hls(*[v/255 for v in testo])
    passo = -0.01 if verso_scuro else 0.01
    for _ in range(100):
        l = max(0.0, min(1.0, l + passo))
        nuovo = tuple(round(v*255) for v in colorsys.hls_to_rgb(h,l,s))
        if ratio(nuovo, fondo) >= soglia:
            return nuovo
        if l in (0.0, 1.0):
            break
    return None

def leggi(path):
    t, righe = {}, pathlib.Path(path).read_text().splitlines(keepends=True)
    for i, riga in enumerate(righe):
        m = re.match(r"([A-Za-z_0-9]+)=#([0-9A-Fa-f]{8})\s*$", riga.strip())
        if m:
            v = int(m.group(2), 16)
            t[m.group(1)] = (i, ((v>>16)&255, (v>>8)&255, v&255), m.group(2)[:2])
    return t, righe

DA_CORREGGERE = [
    ("windowBackgroundWhiteGrayText2", "windowBackgroundWhite", 4.5),
    ("windowBackgroundWhiteGrayText3", "windowBackgroundWhite", 4.5),
    ("windowBackgroundWhiteGrayText4", "windowBackgroundGray", 4.5),
    ("actionBarDefaultSearchPlaceholder", "actionBarDefaultSearch", 4.5),
    ("chat_outTimeText", "chat_outBubble", 4.5),
    ("chat_inTimeText", "chat_inBubble", 4.5),
    ("dialogTextGray2", "dialogBackground", 4.5),
    ("dialogTextBlack", "dialogTopBackground", 3.0),
]

for path in sys.argv[1:]:
    t, righe = leggi(path)
    cambi = 0
    for chiave, fondo_k, soglia in DA_CORREGGERE:
        if chiave not in t or fondo_k not in t: continue
        i, col, alpha = t[chiave]
        fondo = t[fondo_k][1]
        if ratio(col, fondo) >= soglia: continue
        nuovo = aggiusta(col, fondo, soglia)
        if not nuovo:
            print(f"  {chiave}: impossibile senza stravolgere la tinta"); continue
        prima = f"#{col[0]:02X}{col[1]:02X}{col[2]:02X}"
        dopo = f"#{alpha}{nuovo[0]:02X}{nuovo[1]:02X}{nuovo[2]:02X}"
        righe[i] = f"{chiave}={dopo}\n"
        print(f"  {chiave:36} {prima} -> #{nuovo[0]:02X}{nuovo[1]:02X}{nuovo[2]:02X}  ({ratio(col,fondo):.2f} -> {ratio(nuovo,fondo):.2f})")
        cambi += 1
    if cambi:
        pathlib.Path(path).write_text("".join(righe))
    print(f"  {pathlib.Path(path).name}: {cambi} colori corretti\n")

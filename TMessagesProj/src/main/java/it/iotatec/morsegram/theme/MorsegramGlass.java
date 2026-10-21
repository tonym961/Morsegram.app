/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.theme;

import androidx.core.graphics.ColorUtils;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

/**
 * Fondo OPACO della barra a quattro voci in basso (Chat, Contatti, Impostazioni, Profilo) quando è attivo
 * un tema Morsegram. Un solo aggancio upstream: {@code MainTabsActivity.createView} (riga della
 * {@code BlurredBackgroundDrawable} della {@code tabsView}).
 *
 * <p><b>Il problema.</b> Il fondo della barra non è un colore ma il risultato di una composizione:
 * {@code BlurredBackgroundProviderImpl.mainTabs()} prende il colore desiderato
 * ({@link Theme#key_glass_targetMainTabs}), lo riscrive con
 * {@code solveSrcColor(windowBackgroundWhite, target, alpha)} e lo dipinge con {@code alpha} 0,85 (vetro liquido)
 * o 0,76 SOPRA una copia sfocata della lista chat. L'algebra torna solo se dietro c'è davvero
 * {@code windowBackgroundWhite}: con i nostri temi dietro ci sono gli accenti caldi delle celle (ambra #F0A020,
 * pill dei pin, avatar), la sfocatura li media e il 15-24% che resta trasparente li fa salire nel fondo. La barra
 * vira al marrone-oliva e i testi — {@code glass_defaultText}, {@code glass_tabSelectedText} — perdono contrasto.
 * Le chiavi di colore sono giuste: è la composizione a non essere prevedibile.
 *
 * <p><b>La correzione.</b> Si lascia a upstream tutto il resto (bordi, ombra, raggio, padding) e si rende opaco
 * il solo colore di fondo: {@code key_glass_targetMainTabs} con alpha 255, cioè <i>esattamente</i> il colore che
 * il provider upstream dichiara di voler ottenere. Nessuna tinta inventata qui: se un giorno il tema cambia
 * quella chiave, cambia anche la barra. Day #FFFFFF sulla lista avorio #FAF6EF, notte #352C1B sul fondo #1A1A1A.
 *
 * <p><b>Quanto costa in disegno (misurato sul codice, non a occhio).</b> L'uscita anticipata su
 * {@code Color.alpha(backgroundColor) == 255} di {@code BlurredBackgroundDrawable.drawSource} vale SOLO per il
 * ripiego software: {@code BlurredBackgroundDrawableRenderNode.draw()} chiama {@code drawSource} nel solo ramo
 * {@code !canvas.isHardwareAccelerated()}. Sul percorso vero — {@code updateDisplayList()} — la sorgente sfocata
 * viene registrata SEMPRE in {@code renderNodeFill} ({@code source.draw(...)}, e prima ancora
 * {@code liquidGlassEffect.update(...)}), e solo dopo il ramo {@code if (Color.alpha(backgroundColor) == 255)}
 * dipinge il colore pieno: il lavoro viene fatto e scartato. L'aspetto finale è giusto (il renderNode ha
 * {@code setClipToOutline(true)} con i raggi, quindi pastiglia, bordi e ombra restano), ma il costo NON è zero:
 * si paga a ogni ricostruzione della display list, cioè a ogni {@code invalidateBlur} mentre la lista scorre.
 * <p>Azzerarlo sarebbe possibile — {@code MainTabsActivity.blur3_invalidateBlur()} tocca il solo
 * {@code iBlur3SourceTabGlass}, che oggi serve alla sola barra a quattro voci (il fade usa
 * {@code iBlur3SourceColor}) — ma costerebbe una SECONDA riga upstream in {@code MainTabsActivity} per un
 * risparmio che qui non si può misurare: servono un dispositivo con un tema Morsegram attivo e un profilo dei
 * frame, e l'emulatore non li dà. Si rimanda a quella misura, invece di spendere perimetro su una stima.
 *
 * <p><b>Perché un involucro e non una copia del builder.</b> La decisione sta in {@link #getBackgroundColor()},
 * che la {@code BlurredBackgroundDrawable} richiama a ogni {@code updateColors()}: così il passaggio da un tema
 * Morsegram a uno di Telegram (e il cambio giorno/notte) si vede subito, senza ricostruire la barra. Tutti gli
 * altri valori sono inoltrati al provider upstream, quindi se upstream ritocca bordi od ombra li ereditiamo.
 * Gli altri due usi di {@code mainTabs()} (StatisticActivity, ChatAttachAlert) restano vetro: lì la sfocatura ha
 * senso, perché dietro scorre il contenuto.
 */
public final class MorsegramGlass {

    private MorsegramGlass() {
    }

    /**
     * Provider del fondo della barra in basso: upstream avvolto, opaco solo con un tema Morsegram attivo.
     * Chiamato da {@code MainTabsActivity.createView}.
     */
    public static BlurredBackgroundProvider mainTabs(Theme.ResourcesProvider resourcesProvider) {
        return new OpaqueOnMorsegramTheme(BlurredBackgroundProviderImpl.mainTabs(resourcesProvider), resourcesProvider);
    }

    /** Inoltra tutto a upstream tranne il colore di fondo, deciso a ogni lettura. */
    private static final class OpaqueOnMorsegramTheme implements BlurredBackgroundProvider {

        private final BlurredBackgroundProvider upstream;
        private final Theme.ResourcesProvider resourcesProvider;

        OpaqueOnMorsegramTheme(BlurredBackgroundProvider upstream, Theme.ResourcesProvider resourcesProvider) {
            this.upstream = upstream;
            this.resourcesProvider = resourcesProvider;
        }

        @Override
        public int getBackgroundColor() {
            if (!MorsegramTheme.isActive()) {
                return upstream.getBackgroundColor();
            }
            // lo stesso colore che upstream dichiara come obiettivo della composizione, senza trasparenza
            return ColorUtils.setAlphaComponent(Theme.getColor(Theme.key_glass_targetMainTabs, resourcesProvider), 255);
        }

        @Override public int getShadowColor() { return upstream.getShadowColor(); }
        @Override public int getStrokeColorTop() { return upstream.getStrokeColorTop(); }
        @Override public int getStrokeColorBottom() { return upstream.getStrokeColorBottom(); }
        @Override public float getStrokeWidthTop() { return upstream.getStrokeWidthTop(); }
        @Override public float getStrokeWidthBottom() { return upstream.getStrokeWidthBottom(); }
        @Override public float getShadowRadius() { return upstream.getShadowRadius(); }
        @Override public float getShadowDx() { return upstream.getShadowDx(); }
        @Override public float getShadowDy() { return upstream.getShadowDy(); }
    }
}

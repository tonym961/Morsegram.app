/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import android.app.Activity;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import it.iotatec.morsegram.theme.MorsegramPalette;

/**
 * Segnalazione di un risultato generato dall'assistente, DENTRO l'app.
 *
 * <p>È il requisito di Google Play (in vigore dal 31 gennaio 2024, ribadito nel 2026) per ogni app che genera
 * contenuti con l'AI: l'utente deve poter segnalare un risultato offensivo senza uscire dall'app. Senza questa
 * finestra l'app viene rifiutata.</p>
 *
 * <p>La finestra mostra tre cose e niente di più: una RIGA di motivi fra cui scegliere, un campo di commento
 * FACOLTATIVO, e il testo segnalato COSÌ COM'È (selezionabile, perché l'utente deve poter rileggere esattamente
 * ciò che sta segnalando). Il "Segnala" registra in locale ({@link AiReports}) e conferma.</p>
 *
 * <p><b>Onestà dei testi.</b> Non c'è moderazione: nessuno legge le segnalazioni in tempo reale, nessun
 * contenuto viene bloccato per effetto di una segnalazione, e i modelli sono di Groq e Google, non nostri. I
 * testi lo dicono. E non promettono una consegna: la segnalazione resta sul telefono e basta, perché dentro
 * l'app non vive nessun indirizzo a cui spedirla -- un indirizzo nel sorgente finirebbe dentro l'APK, che è
 * pubblico. Google Play chiede che il percorso di segnalazione esista dentro l'app, non che l'app spedisca.</p>
 */
public final class AiReportSheet {

    private AiReportSheet() {
    }

    /**
     * Apre la finestra di segnalazione.
     *
     * @param origin chi ha generato il testo (il fornitore che ha RISPOSTO, non quello configurato)
     * @param text   il testo generato, come è stato mostrato all'utente
     */
    public static void open(BaseFragment fragment, AiReports.Origin origin, String text) {
        final Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null || origin == null || TextUtils.isEmpty(text)) {
            return;
        }

        final int[] reason = {AiReports.REASON_OFFENSIVE};

        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), 0);

        final TextView intro = new TextView(activity);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        intro.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        intro.setText(LocaleController.getString("MorsegramAiReportIntro"));
        box.addView(intro, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // RIGA dei motivi: tre pastiglie, una sola scelta per volta
        final LinearLayout reasons = new LinearLayout(activity);
        reasons.setOrientation(LinearLayout.HORIZONTAL);
        final TextView[] chips = new TextView[3];
        for (int i = 0; i < chips.length; i++) {
            final int value = i;
            final TextView chip = new TextView(activity);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            chip.setGravity(Gravity.CENTER);
            chip.setMaxLines(2);
            chip.setText(LocaleController.getString(AiReports.reasonKey(i)));
            chip.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
            chip.setOnClickListener(v -> {
                reason[0] = value;
                for (int k = 0; k < chips.length; k++) {
                    paintChip(chips[k], k == value);
                }
            });
            chips[i] = chip;
            paintChip(chip, i == reason[0]);
            reasons.addView(chip, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, 0, 0, i == chips.length - 1 ? 0 : 6, 0));
        }
        box.addView(reasons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        final EditTextBoldCursor comment = new EditTextBoldCursor(activity);
        comment.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        comment.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        comment.setHintText(LocaleController.getString("MorsegramAiReportCommentHint"));
        comment.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        comment.setMaxLines(3);
        box.addView(comment, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        final TextView quotedTitle = new TextView(activity);
        quotedTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        quotedTitle.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        quotedTitle.setText(LocaleController.getString("MorsegramAiReportTextTitle"));
        box.addView(quotedTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        // il testo segnalato COSÌ COM'È: selezionabile, senza tagli e senza riformattazioni
        final TextView quoted = new TextView(activity);
        quoted.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        quoted.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        quoted.setTextIsSelectable(true);
        quoted.setText(text);
        final ScrollView scroll = new ScrollView(activity);
        scroll.addView(quoted, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        box.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("MorsegramAiReport"));
        builder.setView(box, Math.min(AndroidUtilities.dp(460), (int) (AndroidUtilities.displaySize.y * 0.55f)));
        builder.setPositiveButton(LocaleController.getString("MorsegramAiReport"), (d, which) ->
                save(fragment, origin, reason[0], comment.getText() == null ? "" : comment.getText().toString(), text));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(builder.create());
    }

    /** Pastiglia scelta: riempimento ambra con il glifo scuro sopra (MorsegramPalette: mai bianco su ambra). */
    private static void paintChip(TextView chip, boolean selected) {
        chip.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(14),
                selected ? MorsegramPalette.AMBER : Theme.getColor(Theme.key_dialogBackgroundGray),
                selected ? MorsegramPalette.AMBER_DARK : Theme.getColor(Theme.key_dialogButtonSelector)));
        chip.setTextColor(selected ? MorsegramPalette.ON_AMBER : Theme.getColor(Theme.key_dialogTextBlack));
    }

    /**
     * Scrive la segnalazione su un thread di fondo e torna sulla UI per l'esito. Se il file non si è potuto
     * scrivere lo si DICE: una conferma per una segnalazione mai registrata sarebbe falsa.
     */
    private static void save(BaseFragment fragment, AiReports.Origin origin, int reason, String comment, String text) {
        Utilities.globalQueue.postRunnable(() -> {
            final AiReports.Report saved = AiReports.add(origin, reason, comment, text);
            AndroidUtilities.runOnUIThread(() -> {
                if (saved == null) {
                    bulletin(fragment, LocaleController.getString("MorsegramAiReportFailed"), null, null);
                    return;
                }
                confirm(fragment, saved);
            });
        });
    }

    /**
     * Conferma che la segnalazione è registrata. Resta sul telefono: nessun indirizzo a cui spedirla vive
     * dentro l'app, per non esporre una casella dentro un APK pubblico.
     */
    private static void confirm(BaseFragment fragment, AiReports.Report report) {
        final Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("MorsegramAiReportSaved"));
        builder.setMessage(LocaleController.getString("MorsegramAiReportSavedText"));
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        fragment.showDialog(builder.create());
    }

    /* ==================================================================================== scorciatoie */

    /**
     * Il bulletin di conferma di una bozza inserita, con il pulsante di segnalazione accanto. La bozza non passa
     * da una finestra (finisce nel campo di scrittura), quindi è l'unico punto in cui l'azione può stare senza
     * aggiungere un passaggio a una funzione che deve restare di un tocco.
     */
    public static void bulletinWithReport(BaseFragment fragment, String text, AiReports.Origin origin, String generated) {
        bulletin(fragment, text, LocaleController.getString("MorsegramAiReport"), () -> open(fragment, origin, generated));
    }

    private static void bulletin(BaseFragment fragment, String text, CharSequence button, Runnable onClick) {
        try {
            if (fragment == null || !BulletinFactory.canShowBulletin(fragment)) {
                return;
            }
            if (button == null || onClick == null) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, text).show();
            } else {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, text, button, onClick).show();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}

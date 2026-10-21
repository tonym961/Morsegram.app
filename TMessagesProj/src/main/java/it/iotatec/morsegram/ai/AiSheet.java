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
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.List;

import it.iotatec.morsegram.MorsegramApp;

/**
 * Tutta la UI dell'assistente dentro una chat: menu delle quattro funzioni, ANTEPRIMA della prima attivazione,
 * attesa annullabile, risultato, errori leggibili.
 *
 * <p>L'anteprima è la garanzia centrale di questo lavoro e non si salta: alla prima attivazione su una chat
 * l'utente vede {@link AiPayload#preview()}, cioè il testo ESATTO che uscirebbe dal dispositivo, con gli
 * oscuramenti già applicati, e decide. Prima di quel sì nessun byte parte.</p>
 *
 * <p>La bozza di risposta finisce nel campo di scrittura e si ferma lì: non esiste in questa classe, né altrove
 * nel package, una chiamata che invii un messaggio. L'invio automatico da un account utente è il motivo più comune
 * di limitazione di un account da parte di Telegram, e il vincolo vale senza eccezioni.</p>
 */
public final class AiSheet {

    private interface TextCallback {
        void run(String text);
    }

    private AiSheet() {
    }

    /* ======================================================================================== menu */

    /** Punto d'ingresso unico dalla chat. Gestisce da sé ogni stato: spento, senza chiave, chat nascosta. */
    public static void open(ChatActivity chat) {
        if (chat == null || chat.getParentActivity() == null) {
            return;
        }
        final int account = chat.getCurrentAccount();
        final long dialogId = chat.getDialogId();
        if (!AiGate.canEnable(account, dialogId)) {
            // chat nascosta: l'assistente non ci arriva mai, e lo si dice invece di tacere
            info(chat, "MorsegramAiTitle", "MorsegramAiHiddenChat");
            return;
        }
        if (!AiSettings.enabled()) {
            askOpenSettings(chat, "MorsegramAiDisabled");
            return;
        }
        if (!AiSettings.hasAnyKey()) {
            askOpenSettings(chat, "MorsegramAiNoKeyYet");
            return;
        }
        if (!AiGate.isEnabled(account, dialogId)) {
            askActivate(chat, account, dialogId);
            return;
        }
        actions(chat, account, dialogId);
    }

    /** Attivazione per singola chat: si dice cosa comporta prima di accendere. */
    private static void askActivate(ChatActivity chat, int account, long dialogId) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(chat.getParentActivity());
        builder.setTitle(LocaleController.getString("MorsegramAiActivateTitle"));
        builder.setMessage(LocaleController.formatString("MorsegramAiActivateText", 0, chatName(account, dialogId)));
        builder.setPositiveButton(LocaleController.getString("MorsegramAiActivate"), (d, which) -> {
            if (AiGate.enable(account, dialogId)) {
                actions(chat, account, dialogId);
            }
        });
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        chat.showDialog(builder.create());
    }

    /** Le quattro funzioni, più lo spegnimento su questa chat. */
    private static void actions(ChatActivity chat, int account, long dialogId) {
        final CharSequence[] items = {
                LocaleController.getString("MorsegramAiSummarize"),
                LocaleController.getString("MorsegramAiDraft"),
                LocaleController.getString("MorsegramAiSearch"),
                LocaleController.getString("MorsegramAiTurnOffHere")
        };
        final int[] icons = {
                R.drawable.msg_viewreplies,
                R.drawable.menu_reply,
                R.drawable.msg_search,
                R.drawable.msg_clear
        };
        final AlertDialog.Builder builder = new AlertDialog.Builder(chat.getParentActivity());
        builder.setTitle(LocaleController.getString("MorsegramAiTitle"));
        builder.setItems(items, icons, (d, which) -> {
            if (which == 0) {
                askRange(chat, account, dialogId);
            } else if (which == 1) {
                askText(chat, "MorsegramAiDraft", "MorsegramAiDraftHint", hint ->
                        run(chat, account, dialogId, AiTasks.draft(account, dialogId, messages(chat), hint), "MorsegramAiDraft", true));
            } else if (which == 2) {
                askText(chat, "MorsegramAiSearch", "MorsegramAiSearchHint", question -> {
                    if (TextUtils.isEmpty(question)) {
                        return;
                    }
                    run(chat, account, dialogId, AiTasks.search(account, dialogId, messages(chat), question), "MorsegramAiSearch", false);
                });
            } else if (which == 3) {
                AiGate.disable(account, dialogId);
                bulletin(chat, LocaleController.getString("MorsegramAiTurnedOffHere"));
            }
        });
        chat.showDialog(builder.create());
    }

    /** Intervallo del riassunto: i non letti (se ce ne sono) oppure le ultime N ore. */
    private static void askRange(ChatActivity chat, int account, long dialogId) {
        final int unread = unreadCount(account, dialogId);
        final CharSequence[] labels = new CharSequence[AiTasks.RANGE_HOURS.length + 1];
        labels[0] = unread > 0
                ? LocaleController.formatString("MorsegramAiRangeUnread", 0, unread)
                : LocaleController.getString("MorsegramAiRangeNoUnread");
        for (int i = 0; i < AiTasks.RANGE_HOURS.length; i++) {
            labels[i + 1] = LocaleController.formatString("MorsegramAiRangeHours", 0, AiTasks.RANGE_HOURS[i]);
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(chat.getParentActivity());
        builder.setTitle(LocaleController.getString("MorsegramAiSummarize"));
        builder.setItems(labels, (d, which) -> {
            final int count = which == 0 ? (unread > 0 ? unread : 0) : 0;
            final int hours = which == 0 ? 0 : AiTasks.RANGE_HOURS[which - 1];
            if (which == 0 && unread <= 0) {
                bulletin(chat, LocaleController.getString("MorsegramAiNothingToSummarize"));
                return;
            }
            run(chat, account, dialogId, AiTasks.summary(account, dialogId, messages(chat), count, hours), "MorsegramAiSummarize", false);
        });
        chat.showDialog(builder.create());
    }

    /* ================================================================================== esecuzione */

    /**
     * Il percorso unico di ogni funzione: niente payload → si dice e si esce; prima volta su questa chat →
     * ANTEPRIMA; poi invio con attesa annullabile.
     *
     * @param intoField true solo per la bozza: il risultato va nel campo di scrittura, non in una finestra
     */
    static void run(ChatActivity chat, int account, long dialogId, AiPayload payload, String titleKey, boolean intoField) {
        if (chat == null || chat.getParentActivity() == null) {
            return;
        }
        if (payload == null || payload.isEmpty()) {
            bulletin(chat, LocaleController.getString("MorsegramAiNothingToSend"));
            return;
        }
        if (!AiGate.isEnabled(account, dialogId)) {
            return;
        }
        if (AiGate.previewAccepted(account, dialogId)) {
            execute(chat, payload, titleKey, intoField);
        } else {
            preview(chat, payload, () -> {
                AiGate.markPreviewAccepted(account, dialogId);
                execute(chat, payload, titleKey, intoField);
            });
        }
    }

    /**
     * ANTEPRIMA: il testo esatto che uscirebbe dal dispositivo, oscuramenti già applicati, con il conteggio delle
     * sostituzioni e il nome di OGNI fornitore che lo potrebbe ricevere. Annulla = niente parte.
     *
     * <p>Con un ripiego configurato i destinatari sono DUE, non uno: {@link AiClient} manda lo stesso testo al
     * ripiego su 429, 5xx, timeout e rete, quindi un semplice rate limit lo farebbe arrivare a un'azienda che il
     * consenso non aveva mai nominato. L'intestazione li nomina entrambi, e
     * {@link AiSettings#setPrimary}/{@link AiSettings#setFallback} dimenticano le accettazioni quando l'insieme
     * cambia, così il sì raccolto riguarda sempre i destinatari di adesso.</p>
     */
    private static void preview(ChatActivity chat, AiPayload payload, Runnable onAccept) {
        final Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final String header = destinationLine()
                + (payload.redactions > 0
                ? "\n" + LocaleController.formatString("MorsegramAiPreviewRedacted", 0, payload.redactions)
                : "\n" + LocaleController.getString("MorsegramAiPreviewNoRedaction"));

        final LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);

        final TextView intro = new TextView(activity);
        intro.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        intro.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        intro.setText(header);
        box.addView(intro, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        final TextView body = new TextView(activity);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        body.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        body.setTextIsSelectable(true);
        body.setText(payload.preview());
        final ScrollView scroll = new ScrollView(activity);
        scroll.addView(body, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        box.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        box.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), 0);

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("MorsegramAiPreviewTitle"));
        builder.setView(box, Math.min(AndroidUtilities.dp(380), (int) (AndroidUtilities.displaySize.y * 0.45f)));
        builder.setPositiveButton(LocaleController.getString("MorsegramAiPreviewSend"), (d, which) -> onAccept.run());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        chat.showDialog(builder.create());
    }

    /**
     * "Va a X" oppure "Va a X; se X non risponde, a Y". La seconda forma SOLO quando il ripiego potrebbe davvero
     * ricevere il testo, cioè quando è configurato e ha una chiave: è la stessa condizione che
     * {@link AiClient#run} verifica prima di riprovare, e prometterne uno che non c'è sarebbe falso.
     * {@link AiKeys#hasKey} legge solo una preferenza, nessuna crittografia: si può chiamare da qui.
     */
    private static String destinationLine() {
        final String primary = AiProvider.labelOf(AiSettings.primary());
        final int fallbackId = AiSettings.fallback();
        if (fallbackId == AiProvider.NONE || !AiKeys.hasKey(fallbackId)) {
            return LocaleController.formatString("MorsegramAiPreviewHeader", 0, primary);
        }
        return LocaleController.formatString("MorsegramAiPreviewHeaderFallback", 0, primary, AiProvider.labelOf(fallbackId));
    }

    /** Invio con attesa annullabile: il risultato arriva sul thread UI (vedi {@link AiClient}). */
    private static void execute(ChatActivity chat, AiPayload payload, String titleKey, boolean intoField) {
        final Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(true);
        final AiClient.Call[] call = new AiClient.Call[1];
        progress.setOnCancelListener(d -> {
            if (call[0] != null) {
                call[0].cancel();
            }
        });
        progress.show();
        call[0] = AiClient.run(payload, titleKey, new AiClient.Callback() {
            @Override
            public void onAiResult(String text, AiReports.Origin origin) {
                dismiss(progress);
                if (intoField) {
                    insertDraft(chat, text, origin);
                } else {
                    showResult(chat, LocaleController.getString(titleKey), text, origin);
                }
            }

            @Override
            public void onAiError(AiException error) {
                dismiss(progress);
                showError(chat, error);
            }
        });
    }

    private static void dismiss(AlertDialog dialog) {
        try {
            dialog.dismiss();
        } catch (Throwable ignore) {
        }
    }

    /* =================================================================================== risultati */

    /**
     * Risultato in una finestra con testo selezionabile, un pulsante per copiarlo e uno per SEGNALARLO.
     *
     * <p>La segnalazione sta qui perché qui passano tre delle quattro funzioni (riassunto, traduzione, risposta
     * della ricerca): è il punto in cui un testo generato arriva all'utente. È nel posto discreto della finestra,
     * accanto a Copia, non un pulsante gridato -- ma c'è ogni volta, e Google Play lo pretende dal 31 gennaio
     * 2024 per ogni app che genera contenuti con l'AI. La bozza, che non passa da una finestra, ha la sua
     * (vedi {@link #insertDraft}).</p>
     *
     * @param origin chi ha generato il testo; se null il pulsante di segnalazione non compare (non sapremmo cosa
     *               registrare, e una segnalazione senza fornitore né modello non dice niente a nessuno)
     */
    static void showResult(BaseFragment fragment, String title, String text, AiReports.Origin origin) {
        final Activity activity = fragment != null ? fragment.getParentActivity() : null;
        if (activity == null || TextUtils.isEmpty(text)) {
            return;
        }
        final TextView body = new TextView(activity);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        body.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        body.setTextIsSelectable(true);
        body.setText(text);
        final ScrollView scroll = new ScrollView(activity);
        scroll.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), 0);
        scroll.addView(body, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(title);
        builder.setView(scroll, Math.min(AndroidUtilities.dp(420), (int) (AndroidUtilities.displaySize.y * 0.5f)));
        builder.setPositiveButton(LocaleController.getString(R.string.Close), null);
        builder.setNeutralButton(LocaleController.getString(R.string.Copy), (d, which) -> {
            if (AndroidUtilities.addToClipboard(text)) {
                bulletin(fragment, LocaleController.getString("MorsegramAiCopied"));
            }
        });
        if (origin != null) {
            builder.setNegativeButton(LocaleController.getString("MorsegramAiReport"), (d, which) ->
                    AiReportSheet.open(fragment, origin, text));
        }
        fragment.showDialog(builder.create());
    }

    /**
     * La bozza va NEL CAMPO DI SCRITTURA. Se il campo contiene già qualcosa si chiede prima: il testo che l'utente
     * stava scrivendo non si butta via senza avvertire. L'app non invia: lo fa la persona, dopo aver letto.
     */
    private static void insertDraft(ChatActivity chat, String text, AiReports.Origin origin) {
        final org.telegram.ui.Components.ChatActivityEnterView enter = chat.getChatActivityEnterView();
        if (enter == null || TextUtils.isEmpty(text)) {
            bulletin(chat, LocaleController.getString("MorsegramAiDraftNoField"));
            return;
        }
        final CharSequence current = enter.getFieldText();
        if (TextUtils.isEmpty(current)) {
            setField(chat, enter, text, origin);
            return;
        }
        final Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("MorsegramAiDraft"));
        builder.setMessage(LocaleController.getString("MorsegramAiDraftReplace"));
        builder.setPositiveButton(LocaleController.getString("MorsegramAiDraftReplaceOk"), (d, which) -> setField(chat, enter, text, origin));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        chat.showDialog(builder.create());
    }

    private static void setField(ChatActivity chat, org.telegram.ui.Components.ChatActivityEnterView enter, String text, AiReports.Origin origin) {
        try {
            enter.setFieldText(text);
        } catch (Throwable e) {
            FileLog.e(e);
            showResult(chat, LocaleController.getString("MorsegramAiDraft"), text, origin);
            return;
        }
        // il promemoria è parte della garanzia: il testo è una bozza, l'invio resta un gesto della persona.
        // Accanto c'è 'Segnala': la bozza non passa da una finestra, quindi senza questo pulsante sarebbe la sola
        // delle quattro funzioni da cui un risultato offensivo non si potrebbe segnalare. Il testo è anche nel
        // campo di scrittura, quindi se il bulletin sparisce la bozza resta comunque sotto gli occhi.
        final String done = LocaleController.getString("MorsegramAiDraftInserted");
        if (origin == null) {
            bulletin(chat, done);
        } else {
            AiReportSheet.bulletinWithReport(chat, done, origin, text);
        }
    }

    private static void showError(ChatActivity chat, AiException error) {
        final Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final String detail = LocaleController.getString(error.messageKey());
        if (error.kind() == AiException.KIND_NO_KEY || error.kind() == AiException.KIND_AUTH) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(LocaleController.getString("MorsegramAiTitle"));
            builder.setMessage(detail);
            builder.setPositiveButton(LocaleController.getString("MorsegramAiOpenSettings"), (d, which) -> MorsegramApp.openSettings());
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            chat.showDialog(builder.create());
            return;
        }
        bulletin(chat, detail);
    }

    /* ==================================================================================== supporto */

    private static void askText(ChatActivity chat, String titleKey, String hintKey, TextCallback onOk) {
        final Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final EditTextBoldCursor field = new EditTextBoldCursor(activity);
        field.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        field.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        field.setHintText(LocaleController.getString(hintKey));
        field.setSingleLine(true);
        field.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        field.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8), AndroidUtilities.dp(24), AndroidUtilities.dp(8));

        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(titleKey));
        builder.setView(field);
        builder.setPositiveButton(LocaleController.getString("MorsegramAiGo"), (d, which) ->
                onOk.run(field.getText() == null ? "" : field.getText().toString()));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        chat.showDialog(builder.create());
    }

    private static void askOpenSettings(ChatActivity chat, String messageKey) {
        final Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("MorsegramAiTitle"));
        builder.setMessage(LocaleController.getString(messageKey));
        builder.setPositiveButton(LocaleController.getString("MorsegramAiOpenSettings"), (d, which) -> MorsegramApp.openSettings());
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        chat.showDialog(builder.create());
    }

    private static void info(ChatActivity chat, String titleKey, String messageKey) {
        final Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString(titleKey));
        builder.setMessage(LocaleController.getString(messageKey));
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        chat.showDialog(builder.create());
    }

    private static void bulletin(BaseFragment fragment, String text) {
        try {
            if (fragment != null && BulletinFactory.canShowBulletin(fragment)) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, text).show();
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** La cronologia caricata nella chat aperta: niente richieste al server per costruire la richiesta AI. */
    private static List<org.telegram.messenger.MessageObject> messages(ChatActivity chat) {
        return chat.messages;
    }

    private static int unreadCount(int account, long dialogId) {
        try {
            final TLRPC.Dialog dialog = MessagesController.getInstance(account).getDialog(dialogId);
            return dialog != null ? Math.max(0, dialog.unread_count) : 0;
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private static String chatName(int account, long dialogId) {
        try {
            if (dialogId > 0) {
                final TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
                final String name = org.telegram.messenger.UserObject.getFirstName(user);
                if (!TextUtils.isEmpty(name)) {
                    return name;
                }
            } else {
                final TLRPC.Chat chatInfo = MessagesController.getInstance(account).getChat(-dialogId);
                if (chatInfo != null && !TextUtils.isEmpty(chatInfo.title)) {
                    return chatInfo.title;
                }
            }
        } catch (Throwable ignore) {
        }
        return LocaleController.getString("MorsegramAiThisChat");
    }
}

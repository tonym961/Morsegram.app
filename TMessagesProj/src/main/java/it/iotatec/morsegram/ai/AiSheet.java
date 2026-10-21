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
import it.iotatec.morsegram.MorsegramGates;

/**
 * Tutta la UI dell'assistente dentro una chat: menu delle quattro funzioni, ANTEPRIMA della prima attivazione,
 * attesa annullabile, risultato, errori leggibili.
 *
 * <p>L'anteprima è la garanzia centrale del lavoro di RETE e non si salta: alla prima attivazione su una chat
 * l'utente vede {@link AiPayload#preview()}, cioè il testo ESATTO che uscirebbe dal dispositivo, con gli
 * oscuramenti già applicati, e decide. Prima di quel sì nessun byte parte.</p>
 *
 * <p>Con il modello SUL DISPOSITIVO non c'è anteprima, e non è una scorciatoia: non esiste nessun destinatario da
 * nominare, quindi la finestra non avrebbe niente da mostrare se non il prompt. In cambio c'è l'altra metà della
 * stessa regola: dal dispositivo non si passa alla rete da sé. Quando il modello locale non può fare un lavoro,
 * la finestra di errore offre un pulsante che manda quel lavoro a Groq o a Gemini — e quel pulsante ricostruisce
 * il payload CON l'oscuramento e passa dall'anteprima, come ogni invio.</p>
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
        if (!AiSettings.primaryIsLocal() && !AiSettings.hasAnyKey()) {
            // il modello sul dispositivo non vuole nessuna chiave: chiederla sarebbe un ostacolo inventato
            askOpenSettings(chat, "MorsegramAiNoKeyYet");
            return;
        }
        if (AiGate.needsChatActivation(account, dialogId)) {
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

    /**
     * Le funzioni, più lo spegnimento su questa chat. Lo spegnimento per chat c'è solo con un fornitore di rete:
     * con il modello sul dispositivo non c'è niente da limitare, quindi la voce sparisce invece di mentire.
     */
    private static void actions(ChatActivity chat, int account, long dialogId) {
        final boolean perChat = !AiSettings.primaryIsLocal();
        final CharSequence[] items = perChat
                ? new CharSequence[]{
                LocaleController.getString("MorsegramAiSummarize"),
                LocaleController.getString("MorsegramAiDraft"),
                LocaleController.getString("MorsegramAiSearch"),
                LocaleController.getString("MorsegramAiTurnOffHere")}
                : new CharSequence[]{
                LocaleController.getString("MorsegramAiSummarize"),
                LocaleController.getString("MorsegramAiDraft"),
                LocaleController.getString("MorsegramAiSearch")};
        final int[] icons = perChat
                ? new int[]{R.drawable.msg_viewreplies, R.drawable.menu_reply, R.drawable.msg_search, R.drawable.msg_clear}
                : new int[]{R.drawable.msg_viewreplies, R.drawable.menu_reply, R.drawable.msg_search};
        final AlertDialog.Builder builder = new AlertDialog.Builder(chat.getParentActivity());
        builder.setTitle(LocaleController.getString("MorsegramAiTitle"));
        builder.setItems(items, icons, (d, which) -> {
            if (which == 0) {
                askRange(chat, account, dialogId);
            } else if (which == 1) {
                askText(chat, "MorsegramAiDraft", "MorsegramAiDraftHint", hint ->
                        run(chat, account, dialogId,
                                destination -> AiTasks.draft(account, dialogId, messages(chat), hint, destination),
                                "MorsegramAiDraft", true));
            } else if (which == 2) {
                askText(chat, "MorsegramAiSearch", "MorsegramAiSearchHint", question -> {
                    if (TextUtils.isEmpty(question)) {
                        return;
                    }
                    run(chat, account, dialogId,
                            destination -> AiTasks.search(account, dialogId, messages(chat), question, destination),
                            "MorsegramAiSearch", false);
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
            run(chat, account, dialogId,
                    destination -> AiTasks.summary(account, dialogId, messages(chat), count, hours, destination),
                    "MorsegramAiSummarize", false);
        });
        chat.showDialog(builder.create());
    }

    /* ================================================================================== esecuzione */

    /**
     * Da dove nasce il testo della richiesta. Non è un {@link AiPayload} già fatto ma una funzione che lo
     * costruisce per una DESTINAZIONE: lo stesso lavoro diventa un payload oscurato se va in rete e un payload
     * intero se resta sul telefono, e il payload si costruisce quando si sa dove andrà. È anche ciò che permette
     * al pulsante "fai questo lavoro con Groq" di rifare la richiesta oscurata senza che il chiamante ripassi di
     * qui.
     */
    interface Source {
        AiPayload build(int destination);
    }

    /** Un lavoro dell'assistente: tutto ciò che serve per farlo, rifarlo altrove e mostrarne il risultato. */
    private static final class Job {

        final ChatActivity chat;
        final int account;
        final long dialogId;
        final Source source;
        final String titleKey;
        /** true solo per la bozza: il risultato va nel campo di scrittura, non in una finestra. */
        final boolean intoField;

        Job(ChatActivity chat, int account, long dialogId, Source source, String titleKey, boolean intoField) {
            this.chat = chat;
            this.account = account;
            this.dialogId = dialogId;
            this.source = source;
            this.titleKey = titleKey;
            this.intoField = intoField;
        }
    }

    /**
     * Il percorso unico di ogni funzione: niente testo → si dice e si esce; modello locale da scaricare → si
     * SPIEGA e si chiede; fornitore di rete alla prima volta su questa chat → ANTEPRIMA; poi lavoro con attesa
     * annullabile e avanzamento.
     */
    static void run(ChatActivity chat, int account, long dialogId, Source source, String titleKey, boolean intoField) {
        start(new Job(chat, account, dialogId, source, titleKey, intoField), AiProvider.NONE);
    }

    /**
     * @param chosen fornitore scelto dall'utente per questo lavoro, o {@link AiProvider#NONE} per quello delle
     *               impostazioni. Una scelta esplicita di un fornitore di rete passa SEMPRE dall'anteprima: il
     *               consenso dato quando il lavoro restava sul telefono non è un consenso a farlo uscire.
     */
    private static void start(Job job, int chosen) {
        // non basta che la ChatActivity esista: un ritorno asincrono (sonda AICore, fine di uno scaricamento)
        // può arrivare dopo minuti, e una chat messa in pausa NON è distrutta — getParentActivity() resta non
        // nullo. Senza isPaused() la finestra si aprirebbe sopra la schermata su cui l'utente si trova adesso
        // per un'azione che aveva chiesto altrove.
        if (job.chat == null || job.chat.getParentActivity() == null || job.chat.isPaused()) {
            return;
        }
        if (!AiGate.isEnabled(job.account, job.dialogId)) {
            return;
        }
        final int providerId = chosen != AiProvider.NONE ? chosen : AiSettings.primary();
        final boolean local = AiProvider.isLocal(providerId);
        if (local && !AiSettings.localReady()) {
            if (AiSettings.localChecking()) {
                // la sonda verso AICore è partita e non è ancora tornata: si aspetta il suo esito invece di
                // dire all'utente una cosa che in questo istante non sappiamo. Torna qui da sé.
                final AiGate.Listener[] once = new AiGate.Listener[1];
                once[0] = () -> {
                    // si toglie SEMPRE, anche se la chat non è più davanti: altrimenti resterebbe appeso a
                    // tenere vivo il Job (e con lui la ChatActivity). Il ritorno in start() è filtrato dal
                    // controllo del ciclo di vita in testa a start().
                    AiGate.removeListener(once[0]);
                    start(job, chosen);
                };
                AiGate.addListener(once[0]);
                return;
            }
            if (AiSettings.localSupported()) {
                askDownload(job);
            } else {
                // telefono senza modello locale: non si passa alla rete di nascosto, si dice e si offre la scelta
                localUnavailable(job);
            }
            return;
        }
        final AiPayload payload = job.source.build(local ? AiTasks.LOCAL : AiTasks.NETWORK);
        if (payload == null || payload.isEmpty()) {
            bulletin(job.chat, LocaleController.getString("MorsegramAiNothingToSend"));
            return;
        }
        if (!local && (AiGate.previewRequired(job.account, job.dialogId) || chosen != AiProvider.NONE)) {
            preview(job.chat, payload, providerId, chosen == AiProvider.NONE, () -> {
                if (chosen == AiProvider.NONE) {
                    AiGate.markPreviewAccepted(job.account, job.dialogId);
                }
                execute(job, payload, providerId, chosen);
            });
            return;
        }
        execute(job, payload, providerId, chosen);
    }

    /* ============================================================== scaricamento del modello locale */

    /**
     * Il primo scaricamento del modello va spiegato e mostrato, non fatto di nascosto: lo fa il sistema (AICore),
     * pesa nell'ordine dei gigabyte e il numero vero lo dice il dispositivo solo quando parte. Se l'utente dice no
     * lo ricordiamo noi, perché nessuna finestra di sistema glielo richiederà.
     */
    private static void askDownload(Job job) {
        final Activity activity = job.chat.getParentActivity();
        if (activity == null) {
            return;
        }
        if (MorsegramGates.LOCAL_AI && OnDeviceModel.isDownloading()) {
            bulletin(job.chat, LocaleController.getString("MorsegramAiLocalDownloadingNow"));
            return;
        }
        if (MorsegramGates.LOCAL_AI && OnDeviceModel.declined()) {
            // il no si ricorda: senza questo la finestra con il peso in gigabyte ricomparirebbe identica a ogni
            // riassunto, traduzione, bozza e ricerca, cioè esattamente la pressione che voleva evitare. Lo
            // scaricamento resta raggiungibile dalla riga "Modello sul telefono", che azzera il no.
            declinedBefore(job);
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("MorsegramAiLocalTitle"));
        builder.setMessage(LocaleController.getString("MorsegramAiLocalExplain"));
        builder.setPositiveButton(LocaleController.getString("MorsegramAiLocalDownloadNow"), (d, which) -> download(job));
        final int remote = AiSettings.remoteWithKey();
        if (remote != AiProvider.NONE) {
            builder.setNeutralButton(LocaleController.formatString("MorsegramAiUseProvider", 0, AiProvider.labelOf(remote)),
                    (d, which) -> start(job, remote));
        }
        builder.setNegativeButton(LocaleController.getString("MorsegramAiLocalNotNow"), (d, which) -> {
            if (MorsegramGates.LOCAL_AI) {
                OnDeviceModel.setDeclined(true);
            }
        });
        job.chat.showDialog(builder.create());
    }

    /**
     * L'utente ha già detto no allo scaricamento: non gli si ripropone la spiegazione con i gigabyte, ma la
     * forma breve — qui non c'è niente con cui lavorare, il fornitore di rete è una scelta, lo scaricamento sta
     * nelle impostazioni. Non si cancella il no da qui: lo cancella solo chi chiede il download da quella riga.
     */
    private static void declinedBefore(Job job) {
        final Activity activity = job.chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("MorsegramAiLocalTitle"));
        builder.setMessage(LocaleController.getString("MorsegramAiLocalDeclined"));
        final int remote = AiSettings.remoteWithKey();
        if (remote != AiProvider.NONE) {
            builder.setPositiveButton(LocaleController.formatString("MorsegramAiUseProvider", 0, AiProvider.labelOf(remote)),
                    (d, which) -> start(job, remote));
            builder.setNeutralButton(LocaleController.getString("MorsegramAiOpenSettings"),
                    (d, which) -> MorsegramApp.openSettings());
        } else {
            builder.setPositiveButton(LocaleController.getString("MorsegramAiOpenSettings"),
                    (d, which) -> MorsegramApp.openSettings());
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        job.chat.showDialog(builder.create());
    }

    /** Lo scaricamento, con il peso vero e l'avanzamento. A fine scaricamento il lavoro riparte da sé. */
    private static void download(Job job) {
        if (!MorsegramGates.LOCAL_AI) {
            return;
        }
        final Activity activity = job.chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final Waiting waiting = new Waiting(activity, LocaleController.getString("MorsegramAiLocalTitle"),
                LocaleController.getString("MorsegramAiLocalDownloadStarting"), true, null);
        waiting.show(job.chat);
        // Il modello pesa gigabyte: questo ascoltatore resta vivo per minuti o decine di minuti, e tiene il Job,
        // cioè la ChatActivity con la sua lista di messaggi. Non lo si può tenere debole (il Job non ha nessun
        // altro proprietario: sparirebbe subito), quindi alla prima callback utile dopo la morte della chat si
        // fa DIMENTICARE: lo scaricamento va avanti — lo fa Android — e la riga delle impostazioni ne mostra
        // comunque lo stato. L'avanzamento arriva spesso, quindi la finestra di trattenimento è breve.
        OnDeviceModel.download(new OnDeviceModel.DownloadListener() {
            /** La chat c'è ancora? Se no, questo ascoltatore non serve più a nessuno e si toglie di mezzo. */
            private boolean gone() {
                if (job.chat.isFinished || job.chat.getParentActivity() == null) {
                    OnDeviceModel.forgetDownloadListener(this);
                    waiting.dismiss();
                    return true;
                }
                return false;
            }

            @Override
            public void onStarted(long totalBytes) {
                if (gone()) {
                    return;
                }
                waiting.setLine(LocaleController.formatString("MorsegramAiLocalDownloadSize", 0, size(totalBytes)));
            }

            @Override
            public void onProgress(long bytes, long totalBytes) {
                if (gone()) {
                    return;
                }
                waiting.setLine(totalBytes > 0
                        ? LocaleController.formatString("MorsegramAiLocalDownloadProgress", 0, size(bytes), size(totalBytes))
                        : LocaleController.formatString("MorsegramAiLocalDownloadSize", 0, size(bytes)));
            }

            @Override
            public void onCompleted() {
                waiting.dismiss();
                if (gone()) {
                    return;
                }
                bulletin(job.chat, LocaleController.getString("MorsegramAiLocalDownloadDone"));
                start(job, AiProvider.NONE);
            }

            @Override
            public void onFailed(String detail) {
                waiting.dismiss();
                if (gone()) {
                    return;
                }
                bulletin(job.chat, LocaleController.getString("MorsegramAiLocalDownloadFailed"));
            }
        });
    }

    /** Il telefono non ha il modello locale: lo si dice, e si offre il cloud come scelta, non come scivolata. */
    private static void localUnavailable(Job job) {
        final Activity activity = job.chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle(LocaleController.getString("MorsegramAiLocalTitle"));
        builder.setMessage(LocaleController.getString("MorsegramAiErrorLocalMissing"));
        final int remote = AiSettings.remoteWithKey();
        if (remote != AiProvider.NONE) {
            builder.setPositiveButton(LocaleController.formatString("MorsegramAiUseProvider", 0, AiProvider.labelOf(remote)),
                    (d, which) -> start(job, remote));
        } else {
            builder.setPositiveButton(LocaleController.getString("MorsegramAiOpenSettings"),
                    (d, which) -> MorsegramApp.openSettings());
        }
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        job.chat.showDialog(builder.create());
    }

    private static String size(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        return org.telegram.messenger.AndroidUtilities.formatFileSize(bytes);
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
    private static void preview(ChatActivity chat, AiPayload payload, int providerId, boolean fromSettings, Runnable onAccept) {
        final Activity activity = chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final String header = destinationLine(chat, providerId, fromSettings)
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
     * ricevere il testo, cioè quando è configurato, è remoto e ha una chiave: è la stessa condizione che
     * {@link AiClient#run} verifica prima di riprovare, e prometterne uno che non c'è sarebbe falso.
     * {@link AiKeys#hasKey} legge solo una preferenza, nessuna crittografia: si può chiamare da qui.
     *
     * @param fromSettings false quando il fornitore è stato scelto a mano per questo lavoro: allora il
     *                     destinatario è lui e lui solo, e non si nomina quello delle impostazioni
     */
    private static String destinationLine(ChatActivity chat, int providerId, boolean fromSettings) {
        final String primary = AiProvider.labelOf(providerId);
        if (!fromSettings) {
            // Fornitore scelto a mano per questo lavoro: il destinatario è uno solo, e nominare quello delle
            // impostazioni sarebbe falso (è anche il caso in cui si esce dal modello sul telefono).
            // Qui si nomina anche la CHAT: con il modello locale come principale non esiste nessuna accensione
            // per chat (AiGate.needsChatActivation è falso), quindi questa è la via per cui il testo di una chat
            // che l'utente non ha mai acceso per la rete può uscire. Il consenso dice sorgente E destinatario,
            // non il solo destinatario.
            return LocaleController.formatString("MorsegramAiPreviewHeaderChat", 0, primary,
                    chatName(chat.getCurrentAccount(), chat.getDialogId()));
        }
        final int fallbackId = AiSettings.fallback();
        if (fallbackId == AiProvider.NONE || AiProvider.isLocal(fallbackId) || !AiKeys.hasKey(fallbackId)) {
            // un ripiego sul modello del telefono non è un secondo destinatario: non esce niente, quindi non si
            // nomina come se fosse un'altra azienda
            return LocaleController.formatString("MorsegramAiPreviewHeader", 0, primary);
        }
        return LocaleController.formatString("MorsegramAiPreviewHeaderFallback", 0, primary, AiProvider.labelOf(fallbackId));
    }

    /**
     * Lavoro con attesa annullabile e AVANZAMENTO: il risultato arriva sul thread UI (vedi {@link AiClient}).
     *
     * <p>Lo spinner muto andava bene per una richiesta HTTP. Sul dispositivo un arretrato lungo diventa più
     * inferenze in fila, cioè decine di secondi: la finestra dice a che blocco siamo e mostra il testo mentre
     * arriva, perché un'attesa senza segni di vita è indistinguibile da un guasto.</p>
     */
    private static void execute(Job job, AiPayload payload, int providerId, int chosen) {
        final Activity activity = job.chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final boolean local = AiProvider.isLocal(providerId);
        final AiClient.Call[] call = new AiClient.Call[1];
        final Waiting waiting = new Waiting(activity, LocaleController.getString(job.titleKey),
                local
                        ? LocaleController.getString("MorsegramAiWorkingLocal")
                        : LocaleController.formatString("MorsegramAiWorkingRemote", 0, AiProvider.labelOf(providerId)),
                false,
                () -> {
                    if (call[0] != null) {
                        call[0].cancel();
                    }
                });
        waiting.show(job.chat);
        // si inoltra il chosen ricevuto da start(), non si rideduce dal confronto con AiSettings.primary(): un
        // fornitore scelto a mano resta SENZA ripiego anche quando coincide con quello delle impostazioni,
        // altrimenti l'anteprima avrebbe nominato un destinatario e AiClient potrebbe usarne due.
        call[0] = AiClient.run(payload, job.titleKey, chosen, new AiClient.Callback() {
            @Override
            public void onAiResult(String text, AiReports.Origin origin) {
                waiting.dismiss();
                if (job.intoField) {
                    insertDraft(job.chat, text, origin);
                } else {
                    showResult(job.chat, LocaleController.getString(job.titleKey), text, origin);
                }
            }

            @Override
            public void onAiError(AiException error) {
                waiting.dismiss();
                showError(job, error, providerId);
            }

            @Override
            public void onAiProgress(int step, int total, String partial) {
                waiting.update(step, total, partial);
            }
        });
    }

    /**
     * L'attesa: una riga che dice cosa sta succedendo e, sotto, il testo che arriva. Non si chiude da sé e si
     * annulla da un pulsante, così un tocco fuori non butta via mezzo minuto di lavoro.
     */
    private static final class Waiting {

        private final AlertDialog dialog;
        private final TextView line;
        private final TextView body;
        private final ScrollView scroll;

        Waiting(Activity activity, String title, String firstLine, boolean closeOnly, Runnable onCancel) {
            final LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(4), AndroidUtilities.dp(22), 0);

            line = new TextView(activity);
            line.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            line.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
            line.setText(firstLine);
            box.addView(line, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

            body = new TextView(activity);
            body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            body.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            scroll = new ScrollView(activity);
            scroll.addView(body, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            box.addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

            final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(title);
            builder.setView(box, Math.min(AndroidUtilities.dp(220), (int) (AndroidUtilities.displaySize.y * 0.28f)));
            if (closeOnly) {
                // lo scaricamento del modello lo fa il sistema: chiudere la finestra non lo ferma, e prometterlo
                // sarebbe falso (cancellare un download di AICore non è documentato)
                builder.setNegativeButton(LocaleController.getString(R.string.Close), null);
            } else {
                builder.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, which) -> {
                    if (onCancel != null) {
                        onCancel.run();
                    }
                });
            }
            dialog = builder.create();
            dialog.setCanCancel(false);
        }

        void show(BaseFragment fragment) {
            try {
                fragment.showDialog(dialog);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        void setLine(String text) {
            try {
                line.setText(text);
            } catch (Throwable ignore) {
            }
        }

        /** "parte 3 di 8" e il parziale: la riga cambia solo se i blocchi sono più di uno. */
        void update(int step, int total, String partial) {
            try {
                if (total > 1) {
                    line.setText(LocaleController.formatString("MorsegramAiProgressStep", 0, step, total));
                }
                if (!TextUtils.isEmpty(partial)) {
                    body.setText(partial);
                    scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
                }
            } catch (Throwable ignore) {
            }
        }

        void dismiss() {
            try {
                dialog.dismiss();
            } catch (Throwable ignore) {
            }
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

    /**
     * Gli errori. Due famiglie con due forme diverse: quelli di una chiave mancante aprono le impostazioni, e
     * quelli del modello sul dispositivo offrono di rifare il lavoro con un fornitore di rete — che è l'UNICO
     * modo di uscire dal telefono, perché il ripiego automatico non lo fa (vedi {@link AiClient}).
     */
    private static void showError(Job job, AiException error, int providerId) {
        final Activity activity = job.chat.getParentActivity();
        if (activity == null) {
            return;
        }
        final String detail = errorText(error);
        if (error.kind() == AiException.KIND_NO_KEY || error.kind() == AiException.KIND_AUTH) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(LocaleController.getString("MorsegramAiTitle"));
            builder.setMessage(detail);
            builder.setPositiveButton(LocaleController.getString("MorsegramAiOpenSettings"), (d, which) -> MorsegramApp.openSettings());
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            job.chat.showDialog(builder.create());
            return;
        }
        if (AiProvider.isLocal(providerId) && (error.isLocalKind() || error.kind() == AiException.KIND_TIMEOUT)) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(LocaleController.getString("MorsegramAiLocalTitle"));
            builder.setMessage(detail);
            if (error.kind() == AiException.KIND_LOCAL_DOWNLOAD) {
                builder.setPositiveButton(LocaleController.getString("MorsegramAiLocalDownloadNow"), (d, which) -> download(job));
            }
            final int remote = AiSettings.remoteWithKey();
            if (remote != AiProvider.NONE) {
                builder.setNeutralButton(LocaleController.formatString("MorsegramAiUseProvider", 0, AiProvider.labelOf(remote)),
                        (d, which) -> start(job, remote));
            } else if (error.kind() != AiException.KIND_LOCAL_DOWNLOAD) {
                builder.setPositiveButton(LocaleController.getString("MorsegramAiOpenSettings"),
                        (d, which) -> MorsegramApp.openSettings());
            }
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            job.chat.showDialog(builder.create());
            return;
        }
        bulletin(job.chat, detail);
    }

    /**
     * Il testo di un errore. Per l'arretrato troppo lungo si dice anche QUANTO costerebbe: il fornitore mette il
     * numero di blocchi nel dettaglio, perché "troppo lungo" senza una misura non aiuta a decidere.
     */
    private static String errorText(AiException error) {
        if (error.kind() == AiException.KIND_LOCAL_TOO_LONG) {
            int blocks = 0;
            try {
                blocks = Integer.parseInt(String.valueOf(error.getMessage()).trim());
            } catch (Throwable ignore) {
            }
            if (blocks > 1) {
                return LocaleController.formatString("MorsegramAiErrorLocalTooLongCount", 0, blocks);
            }
        }
        return LocaleController.getString(error.messageKey());
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

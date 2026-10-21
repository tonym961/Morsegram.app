/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import android.os.SystemClock;
import android.text.TextUtils;

import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.common.StreamingCallback;
import com.google.mlkit.genai.prompt.Candidate;
import com.google.mlkit.genai.prompt.CountTokensResponse;
import com.google.mlkit.genai.prompt.GenerateContentRequest;
import com.google.mlkit.genai.prompt.GenerateContentResponse;
import com.google.mlkit.genai.prompt.PromptPrefix;
import com.google.mlkit.genai.prompt.SystemInstruction;
import com.google.mlkit.genai.prompt.TextPart;
import com.google.mlkit.genai.prompt.java.GenerativeModelFutures;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;

import java.util.ArrayList;
import java.util.List;

/**
 * Il fornitore SUL DISPOSITIVO: ML Kit Prompt API su Gemini Nano via AICore.
 *
 * <p>Nessuna chiave, nessun account, nessuna rete e nessun destinatario: "Input, inference, and output data is
 * processed locally", e dopo il primo scaricamento del modello funziona anche senza connessione. Per questo non
 * c'è anteprima del testo in uscita: non c'è un testo in uscita da mostrare, ed è il punto di forza, non una
 * dimenticanza. Lo stato del dispositivo, lo scaricamento e la mappa degli errori stanno in
 * {@link OnDeviceModel}.</p>
 *
 * <p><b>Perché la Prompt API e non la Summarization API.</b> La GenAI Summarization API dichiara inglese,
 * giapponese e coreano, quindi su una chat italiana non è utilizzabile, e il suo formato è fissato a uno, due o
 * tre punti elenco. La Prompt API non pubblica un elenco di lingue, la lunghezza la decide chi chiama con
 * {@code setMaxOutputTokens} e il modello di base l'italiano lo sa (su quello stesso modello girano gli adattatori
 * italiani di Rewriting e Proofreading). La QUALITÀ di un riassunto italiano su un dispositivo reale non è
 * documentata da nessuna parte: si vede sul telefono, non qui.</p>
 *
 * <p><b>Arretrato lungo: riduzione sequenziale con riporto, non map-reduce.</b> La finestra del dispositivo
 * ({@code getTokenLimit()}, 4000 token sui dispositivi documentati) non tiene sempre un arretrato intero. Si
 * spezza SEMPRE sui confini dei messaggi, preferendo come punto di taglio lo stacco temporale più grande fra due
 * messaggi vicini: una conversazione si spezza male a metà scambio e bene fra due sessioni. Ogni blocco riceve il
 * riporto del precedente — cosa è ancora aperto, chi aspetta una risposta, cosa è stato deciso — e l'ultimo
 * blocco produce il riassunto vero. Un riassunto indipendente del terzo blocco non saprebbe che la domanda a cui
 * si sta rispondendo stava nel primo, e nessuna fusione finale potrebbe più ricostruirlo. I blocchi non si
 * parallelizzano, ma AICore serializza comunque (quota per app, {@code BUSY}), quindi non si perde niente.</p>
 *
 * <p><b>Tre gabbie del sistema trattate come esiti normali</b>, non come guasti: la quota di inferenza per app
 * ({@code BUSY}, con attesa e nuovo tentativo), la quota a lunga durata
 * ({@code PER_APP_BATTERY_USE_QUOTA_EXCEEDED}) e il divieto di lavorare fuori dal primo piano
 * ({@code BACKGROUND_USE_BLOCKED}): il lavoro a blocchi aspetta il ritorno in primo piano invece di schiantarsi.
 * Niente riassunti precalcolati in sottofondo, nessun WorkManager, e niente di tutto questo finisce su disco.</p>
 */
public final class OnDeviceProvider extends AiProvider {

    /**
     * Tetto ai blocchi: oltre, non si avvia. Dodici blocchi sono decine di secondi di attesa e possono toccare la
     * quota giornaliera del sistema: meglio dirlo e offrire un intervallo più corto o un fornitore remoto.
     */
    private static final int MAX_BLOCKS = 12;
    /** Tetto al riporto fra blocchi: deve stare nella finestra insieme al blocco, non raccontare la chat. */
    private static final int CARRY_TOKENS = 160;
    /** Margine sul budget del blocco: il riporto cresce e il conteggio dei token non è gratis a ogni tentativo. */
    private static final float MARGIN = 0.88f;
    private static final int MIN_BUDGET_TOKENS = 200;
    /** Quante volte si riprova dopo un BUSY prima di arrendersi (senza mai uscire dal dispositivo). */
    private static final int BUSY_RETRIES = 3;
    /** Ogni quanto si pubblica il parziale in streaming: più spesso è sfarfallio, non informazione. */
    private static final long STREAM_THROTTLE_MS = 250;
    /** Quanto si aspetta il ritorno in primo piano fra due blocchi, prima di provarci comunque. */
    private static final long FOREGROUND_WAIT_MS = 60_000;
    private static final long FOREGROUND_STEP_MS = 250;
    /** Stima di ripiego dei caratteri per token, usata SOLO se countTokens non risponde. */
    private static final float CHARS_PER_TOKEN_FALLBACK = 2.6f;

    @Override
    public int id() {
        return ON_DEVICE;
    }

    @Override
    public String label() {
        return LocaleController.getString("MorsegramAiOnDevice");
    }

    /**
     * Il nome vero del modello letto da AICore, p.es. {@code nano-v2}: così {@link AiReports.Origin#model} dice di
     * chi è il testo segnalato anche quando il testo non è stato prodotto da nessuna azienda. L'obbligo di
     * segnalazione dei contenuti generati guarda il contenuto, non il posto dove è stato prodotto.
     */
    @Override
    public String model() {
        final String name = OnDeviceModel.baseModelName();
        return name.length() > 0 ? name : "on-device";
    }

    @Override
    public boolean local() {
        return true;
    }

    /* ================================================================================== esecuzione */

    @Override
    public String complete(AiPayload payload, Abort abort, Progress progress) throws AiException {
        if (payload == null || payload.isEmpty()) {
            throw new AiException(AiException.KIND_EMPTY, label(), "no payload");
        }
        final GenerativeModelFutures futures = futures();
        // la documentazione è esplicita: checkStatus() prima di qualunque altra cosa, così l'utente non vede mai
        // un errore di AICore al posto di una spiegazione
        final Integer status = OnDeviceModel.await(futures.checkStatus(), OnDeviceModel.SERVICE_TIMEOUT_MS, abort, label());
        final int state = status != null ? status : FeatureStatus.UNAVAILABLE;
        if (state == FeatureStatus.DOWNLOADABLE || state == FeatureStatus.DOWNLOADING) {
            throw new AiException(AiException.KIND_LOCAL_DOWNLOAD, label(), "status " + state);
        }
        if (state != FeatureStatus.AVAILABLE) {
            throw new AiException(AiException.KIND_LOCAL_MISSING, label(), "status " + state);
        }
        try {
            // scalda il modello senza aspettare: la prima inferenza lo caricherebbe comunque
            futures.warmup();
        } catch (Throwable ignore) {
        }
        OnDeviceModel.ensureLimits(futures);

        final boolean systemPrompt = OnDeviceModel.hasSystemPrompt();
        final int limit = OnDeviceModel.tokenLimitOrDefault();

        // un colpo solo, se ci sta: si MISURA, non si stima
        final GenerateContentRequest single = request(payload.system, payload.user, payload.maxTokens, systemPrompt, false);
        final int wholeTokens = count(futures, single, abort);
        if (wholeTokens > 0 && wholeTokens + payload.maxTokens <= limit) {
            if (progress != null) {
                progress.onStep(1, 1, "");
            }
            awaitForeground(abort);
            return generate(futures, single, abort, progress, 1, 1, true);
        }

        // altrimenti: blocchi e riporto. Il rapporto caratteri/token viene dalla misura appena fatta.
        final float charsPerToken = wholeTokens > 0
                ? Math.max(1f, (float) (payload.system.length() + payload.user.length()) / wholeTokens)
                : CHARS_PER_TOKEN_FALLBACK;
        final int fixed = (int) Math.ceil((payload.system.length() + NOTES_PROMPT_CHARS) / charsPerToken);
        final int budget = (int) ((limit - fixed - CARRY_TOKENS - Math.max(payload.maxTokens, CARRY_TOKENS)) * MARGIN);
        if (budget < MIN_BUDGET_TOKENS) {
            throw new AiException(AiException.KIND_LOCAL_TOO_LONG, label(), "1");
        }
        final boolean[] truncated = new boolean[1];
        final ArrayList<String> blocks = split(payload.user, (int) (budget * charsPerToken), truncated);
        if (blocks.isEmpty()) {
            throw new AiException(AiException.KIND_EMPTY, label(), "no blocks");
        }
        if (blocks.size() > MAX_BLOCKS) {
            throw new AiException(AiException.KIND_LOCAL_TOO_LONG, label(), String.valueOf(blocks.size()));
        }
        // la prefix caching paga solo quando il prefisso si ripete, e NON va usata insieme al prompt di sistema:
        // su Nano v2, dove il prompt di sistema non esiste, è la scelta naturale
        boolean prefix = !systemPrompt && blocks.size() > 1;

        String carry = "";
        for (int i = 0; i < blocks.size(); i++) {
            final boolean last = i == blocks.size() - 1;
            awaitForeground(abort);
            if (progress != null) {
                progress.onStep(i + 1, blocks.size(), "");
            }
            final String system = last ? payload.system : payload.system + "\n\n" + notesPrompt();
            final StringBuilder body = new StringBuilder();
            if (carry.length() > 0) {
                body.append(last ? "Notes from the earlier parts of this conversation:\n" : "Notes so far:\n")
                        .append(carry).append("\n\n--\n");
            }
            body.append(blocks.get(i));
            final int out = last ? payload.maxTokens : CARRY_TOKENS;
            String text;
            try {
                text = block(futures, system, body.toString(), out, systemPrompt, prefix, abort, progress, i + 1, blocks.size(), last);
            } catch (AiException e) {
                if (OnDeviceModel.isCacheError(e) && prefix) {
                    // niente prefix caching su questo dispositivo: le istruzioni tornano dentro il testo
                    prefix = false;
                    i--;
                    continue;
                }
                if (OnDeviceModel.isTooLarge(e) && blocks.get(i).length() > 600 && blocks.size() < MAX_BLOCKS) {
                    // il budget era nostro e l'abbiamo sbagliato: si spezza in due e si rifà, senza dire niente
                    final String current = blocks.get(i);
                    final int at = cutPoint(current);
                    blocks.set(i, current.substring(0, at));
                    blocks.add(i + 1, current.substring(at));
                    i--;
                    continue;
                }
                throw e;
            }
            if (last) {
                return truncated[0]
                        ? text + "\n\n" + LocaleController.getString("MorsegramAiLocalTruncatedNote")
                        : text;
            }
            carry = text;
        }
        throw new AiException(AiException.KIND_EMPTY, label(), "no last block");
    }

    /** Un blocco, con i nuovi tentativi previsti per la quota di inferenza del sistema. */
    private String block(GenerativeModelFutures futures, String system, String user, int maxTokens,
                         boolean systemPrompt, boolean prefix, Abort abort, Progress progress,
                         int step, int total, boolean publish) throws AiException {
        AiException last = null;
        for (int attempt = 0; attempt <= BUSY_RETRIES; attempt++) {
            if (abort != null && abort.isAborted()) {
                throw new AiException(AiException.KIND_CANCELLED, label(), "cancelled");
            }
            try {
                final GenerateContentRequest request = request(system, user, maxTokens, systemPrompt, prefix);
                return generate(futures, request, abort, progress, step, total, publish);
            } catch (AiException e) {
                if (e.kind() != AiException.KIND_LOCAL_BUSY || attempt == BUSY_RETRIES) {
                    throw e;
                }
                last = e;
                final long wait = e.retryDelayMs() > 0 ? e.retryDelayMs() : 1200L * (attempt + 1);
                sleep(wait, abort);
            }
        }
        throw last != null ? last : new AiException(AiException.KIND_LOCAL_BUSY, label(), "busy");
    }

    /** Una inferenza, in streaming: il parziale va alla finestra appena arriva. */
    private String generate(GenerativeModelFutures futures, GenerateContentRequest request, Abort abort,
                            Progress progress, int step, int total, boolean publish) throws AiException {
        final StringBuilder streamed = new StringBuilder();
        final long[] lastAt = new long[1];
        final StreamingCallback callback = chunk -> {
            if (chunk == null || chunk.length() == 0) {
                return;
            }
            final String partial;
            synchronized (streamed) {
                streamed.append(chunk);
                partial = streamed.toString();
            }
            if (!publish || progress == null) {
                return;
            }
            final long now = SystemClock.elapsedRealtime();
            if (now - lastAt[0] < STREAM_THROTTLE_MS) {
                return;
            }
            lastAt[0] = now;
            progress.onStep(step, total, partial);
        };
        final GenerateContentResponse response = OnDeviceModel.await(
                futures.generateContent(request, callback), OnDeviceModel.INFERENCE_TIMEOUT_MS, abort, label());
        String text = "";
        Integer finish = null;
        final List<Candidate> candidates = response != null ? response.getCandidates() : null;
        if (candidates != null && !candidates.isEmpty()) {
            final Candidate candidate = candidates.get(0);
            if (candidate != null) {
                text = candidate.getText() != null ? candidate.getText() : "";
                finish = candidate.getFinishReason();
            }
        }
        if (TextUtils.isEmpty(text.trim())) {
            synchronized (streamed) {
                text = streamed.toString();
            }
        }
        if (TextUtils.isEmpty(text.trim())) {
            // non esiste un codice di rifiuto per contenuto nella Prompt API: un candidato vuoto o un
            // finishReason OTHER sono "non ha risposto", non "ha rifiutato"
            throw new AiException(AiException.KIND_EMPTY, label(), "finish " + finish);
        }
        return text.trim();
    }

    /** Conteggio dei token della richiesta, o -1 se il dispositivo non lo dà. */
    private int count(GenerativeModelFutures futures, GenerateContentRequest request, Abort abort) throws AiException {
        try {
            final CountTokensResponse response = OnDeviceModel.await(
                    futures.countTokens(request), OnDeviceModel.SERVICE_TIMEOUT_MS, abort, label());
            return response != null ? response.getTotalTokens() : -1;
        } catch (AiException e) {
            if (e.kind() == AiException.KIND_CANCELLED) {
                throw e;
            }
            // una richiesta troppo grande non si lascia nemmeno contare: si passa ai blocchi
            return -1;
        }
    }

    private GenerativeModelFutures futures() throws AiException {
        try {
            return OnDeviceModel.client();
        } catch (Throwable e) {
            throw OnDeviceModel.toAiException(e, label(), null);
        }
    }

    /* ==================================================================================== richiesta */

    /**
     * Tre forme, scelte dal dispositivo e non dal nome del telefono: con il prompt di sistema (Nano v3+), con la
     * prefix caching, oppure tutto dentro il testo (Nano v2, dove il prompt di sistema non esiste).
     */
    private static GenerateContentRequest request(String system, String user, int maxTokens, boolean systemPrompt, boolean prefix) {
        final GenerateContentRequest.Builder builder;
        if (systemPrompt && system.length() > 0) {
            builder = new GenerateContentRequest.Builder(new SystemInstruction(system), new TextPart(user));
        } else if (prefix && system.length() > 0) {
            builder = new GenerateContentRequest.Builder(new TextPart(user));
            builder.setPromptPrefix(new PromptPrefix(system + "\n\n"));
        } else {
            builder = new GenerateContentRequest.Builder(new TextPart(system.length() > 0 ? system + "\n\n" + user : user));
        }
        builder.setMaxOutputTokens(maxTokens);
        builder.setTemperature(0.3f);
        return builder.build();
    }

    /** Istruzione dei blocchi intermedi: il loro prodotto è il riporto, non un riassunto. */
    private static String notesPrompt() {
        return "This is one part of a longer conversation, and more parts follow. "
                + "Do not answer the task yet: keep short running notes instead, at most six lines, "
                + "covering what is still open, who is waiting for an answer, and what was decided. "
                + "Return only the notes, in the same language as the task above.";
    }

    /** Lunghezza indicativa di {@link #notesPrompt()}: serve al budget, non deve essere esatta. */
    private static final int NOTES_PROMPT_CHARS = 340;

    /* ====================================================================================== blocchi */

    /**
     * Spezza il testo sui confini dei messaggi (una riga per messaggio, come li costruisce {@link AiTasks}) e mai
     * dentro una riga. Nell'ultimo quarto del blocco si preferisce il punto dove lo stacco temporale fra due
     * messaggi è più grande.
     *
     * @param truncated diventa true se un singolo messaggio era più lungo della finestra: non si spezza in modo
     *                  sensato, si taglia e lo si DICE nel risultato invece di fingere
     */
    private static ArrayList<String> split(String text, int maxChars, boolean[] truncated) {
        final ArrayList<String> out = new ArrayList<>();
        final int max = Math.max(200, maxChars);
        final String[] lines = text.split("\n", -1);
        int start = 0;
        int i = 0;
        int chars = 0;
        while (i < lines.length) {
            final int len = lines[i].length() + 1;
            if (i == start && len > max) {
                out.add(lines[i].substring(0, max));
                truncated[0] = true;
                i++;
                start = i;
                chars = 0;
                continue;
            }
            if (i > start && chars + len > max) {
                final int cut = boundary(lines, start, i);
                out.add(join(lines, start, cut));
                start = cut;
                i = cut;
                chars = 0;
                continue;
            }
            chars += len;
            i++;
        }
        if (start < lines.length) {
            final String tail = join(lines, start, lines.length);
            if (tail.trim().length() > 0) {
                out.add(tail);
            }
        }
        return out;
    }

    /** Il confine migliore in {@code (start, end]}: lo stacco temporale più grande nell'ultimo quarto. */
    private static int boundary(String[] lines, int start, int end) {
        final int from = Math.max(start + 1, end - Math.max(1, (end - start) / 4));
        int best = end;
        int bestGap = -1;
        for (int b = from; b < end; b++) {
            final int before = minuteOf(lines[b - 1]);
            final int after = minuteOf(lines[b]);
            if (before < 0 || after < 0) {
                continue;
            }
            final int gap = after - before;
            if (gap > bestGap) {
                bestGap = gap;
                best = b;
            }
        }
        return best;
    }

    /**
     * Il minuto del timbro "dd/MM HH:mm" con cui {@link AiTasks} apre ogni riga, o -1 se la riga non ne ha uno.
     * Serve solo a confrontare due righe vicine, quindi il mese non entra nel conto: a cavallo di un mese lo
     * stacco esce negativo e quel confine semplicemente non vince.
     */
    private static int minuteOf(String line) {
        if (line == null || line.length() < 11 || line.charAt(2) != '/' || line.charAt(5) != ' ' || line.charAt(8) != ':') {
            return -1;
        }
        try {
            final int day = Integer.parseInt(line.substring(0, 2));
            final int hour = Integer.parseInt(line.substring(6, 8));
            final int minute = Integer.parseInt(line.substring(9, 11));
            return day * 1440 + hour * 60 + minute;
        } catch (Throwable ignore) {
            return -1;
        }
    }

    private static String join(String[] lines, int from, int to) {
        final StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    /** Dove tagliare in due un blocco che il dispositivo ha rifiutato: sul confine di riga più vicino alla metà. */
    private static int cutPoint(String block) {
        final int half = block.length() / 2;
        final int at = block.lastIndexOf('\n', half);
        return at > 0 ? at + 1 : half;
    }

    /* ==================================================================================== supporto */

    /**
     * L'inferenza è permessa solo quando l'app è in primo piano ({@code BACKGROUND_USE_BLOCKED}), quindi il lavoro
     * a blocchi aspetta invece di schiantarsi. {@code mainInterfacePaused} lo tiene {@code LaunchActivity}: una
     * bolla di chat non ci passa, perciò dopo l'attesa si prova comunque e la risposta la dà AICore.
     */
    private void awaitForeground(Abort abort) throws AiException {
        long waited = 0;
        while (ApplicationLoader.mainInterfacePaused && waited < FOREGROUND_WAIT_MS) {
            if (abort != null && abort.isAborted()) {
                throw new AiException(AiException.KIND_CANCELLED, label(), "cancelled");
            }
            sleep(FOREGROUND_STEP_MS, abort);
            waited += FOREGROUND_STEP_MS;
        }
    }

    private void sleep(long ms, Abort abort) throws AiException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiException.KIND_CANCELLED, label(), "interrupted");
        }
        if (abort != null && abort.isAborted()) {
            throw new AiException(AiException.KIND_CANCELLED, label(), "cancelled");
        }
    }
}

/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Google Gemini: API {@code generateContent}, forma diversa da quella di Groq.
 * <ul>
 * <li>POST {@code /v1beta/models/<modello>:generateContent}, autenticazione con l'intestazione
 *     {@code x-goog-api-key} (NON {@code Authorization}, e MAI la chiave nella query string: finirebbe nei log
 *     di ogni proxy sul percorso);</li>
 * <li>il prompt di sistema è un campo a sé, {@code systemInstruction}, non un elemento della conversazione;</li>
 * <li>la risposta è spezzata in {@code candidates[0].content.parts[]}, da riunire;</li>
 * <li>il rifiuto di contenuto arriva come {@code promptFeedback.blockReason} oppure come
 *     {@code candidates[0].finishReason} = SAFETY / BLOCKLIST / PROHIBITED_CONTENT / RECITATION.</li>
 * </ul>
 */
public final class GeminiProvider extends AiProvider {

    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    /** Nome del modello: costante da aggiornare quando Google lo ritira. */
    // gemini-2.0-flash e' stato spento da Google il 01/06/2026: era cablato qui e l'assistente
    // rispondeva NOT_FOUND. Valore di partenza, sovrascrivibile dalle impostazioni.
    private final String model = pick(AiSettings.model(AiProvider.GEMINI), DEFAULT_MODEL);

    private static final String DEFAULT_MODEL = "gemini-3.6-flash";

    @Override
    public int id() {
        return GEMINI;
    }

    @Override
    public String label() {
        return "Google Gemini";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    protected String endpoint() {
        return BASE + model + ":generateContent";
    }

    @Override
    protected String[] authHeader(String apiKey) {
        return new String[]{"x-goog-api-key", apiKey};
    }

    @Override
    protected String body(String system, String user, int maxTokens) throws Exception {
        final JSONObject root = new JSONObject();
        if (system != null && system.length() > 0) {
            root.put("systemInstruction", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", system))));
        }
        final JSONObject turn = new JSONObject();
        turn.put("role", "user");
        turn.put("parts", new JSONArray().put(new JSONObject().put("text", user)));
        root.put("contents", new JSONArray().put(turn));

        final JSONObject config = new JSONObject();
        config.put("temperature", 0.3);
        config.put("maxOutputTokens", maxTokens);
        root.put("generationConfig", config);
        return root.toString();
    }

    @Override
    protected String reply(String json) throws Exception {
        final JSONObject root = new JSONObject(json);
        final JSONObject feedback = root.optJSONObject("promptFeedback");
        if (feedback != null) {
            final String blocked = feedback.optString("blockReason", "");
            if (blocked.length() > 0) {
                throw new AiException(AiException.KIND_CONTENT, label(), blocked);
            }
        }
        final JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            throw new AiException(AiException.KIND_EMPTY, label(), "no candidates");
        }
        final JSONObject candidate = candidates.optJSONObject(0);
        if (candidate == null) {
            throw new AiException(AiException.KIND_EMPTY, label(), "no candidate");
        }
        final String finish = candidate.optString("finishReason", "");
        if ("SAFETY".equals(finish) || "BLOCKLIST".equals(finish) || "PROHIBITED_CONTENT".equals(finish) || "RECITATION".equals(finish) || "SPII".equals(finish)) {
            throw new AiException(AiException.KIND_CONTENT, label(), finish);
        }
        final JSONObject content = candidate.optJSONObject("content");
        final JSONArray parts = content != null ? content.optJSONArray("parts") : null;
        if (parts == null || parts.length() == 0) {
            // MAX_TOKENS con parts vuoto: risposta troncata a zero, niente da mostrare
            throw new AiException(AiException.KIND_EMPTY, label(), "no parts " + finish);
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            final JSONObject part = parts.optJSONObject(i);
            final String text = part != null ? part.optString("text", "") : "";
            if (text.length() > 0) {
                sb.append(text);
            }
        }
        return sb.toString();
    }
}

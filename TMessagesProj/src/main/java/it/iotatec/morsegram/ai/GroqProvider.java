/*
 * Morsegram — client Telegram non ufficiale.
 * Copyright (C) 2026 Iotatec. Licenza GPL-2.0-or-later, come l'upstream DrKLO/Telegram.
 */
package it.iotatec.morsegram.ai;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Groq: API chat completions in stile OpenAI.
 * <ul>
 * <li>POST {@code /openai/v1/chat/completions}, autenticazione {@code Authorization: Bearer <chiave>};</li>
 * <li>il prompt di sistema è il primo elemento di {@code messages} (ruolo {@code system});</li>
 * <li>risposta in {@code choices[0].message.content}; il rifiuto di contenuto arriva come
 *     {@code choices[0].message.refusal} oppure {@code finish_reason = content_filter}.</li>
 * </ul>
 */
public final class GroqProvider extends HttpAiProvider {

    private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    /** Nome del modello: costante da aggiornare quando Groq lo ritira. */
    // llama-3.3-70b-versatile e' stato spento da Groq il 16/08/2026: era cablato qui e l'assistente
    // rispondeva 404. Questo e' solo il valore di partenza, l'utente puo' cambiarlo dalle impostazioni.
    private final String model = pick(AiSettings.model(AiProvider.GROQ), DEFAULT_MODEL);

    private static final String DEFAULT_MODEL = "openai/gpt-oss-120b";

    @Override
    public int id() {
        return GROQ;
    }

    @Override
    public String label() {
        return "Groq";
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    protected String endpoint() {
        return ENDPOINT;
    }

    @Override
    protected String[] authHeader(String apiKey) {
        return new String[]{"Authorization", "Bearer " + apiKey};
    }

    @Override
    protected String body(String system, String user, int maxTokens) throws Exception {
        final JSONArray messages = new JSONArray();
        if (system != null && system.length() > 0) {
            final JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", system);
            messages.put(sys);
        }
        final JSONObject usr = new JSONObject();
        usr.put("role", "user");
        usr.put("content", user);
        messages.put(usr);

        final JSONObject root = new JSONObject();
        root.put("model", model);
        root.put("messages", messages);
        root.put("temperature", 0.3);
        root.put("max_tokens", maxTokens);
        root.put("stream", false);
        return root.toString();
    }

    @Override
    protected String reply(String json) throws Exception {
        final JSONObject root = new JSONObject(json);
        final JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new AiException(AiException.KIND_EMPTY, label(), "no choices");
        }
        final JSONObject choice = choices.optJSONObject(0);
        if (choice == null) {
            throw new AiException(AiException.KIND_EMPTY, label(), "no choice");
        }
        if ("content_filter".equals(choice.optString("finish_reason"))) {
            throw new AiException(AiException.KIND_CONTENT, label(), "content_filter");
        }
        final JSONObject message = choice.optJSONObject("message");
        if (message == null) {
            throw new AiException(AiException.KIND_EMPTY, label(), "no message");
        }
        final String refusal = message.isNull("refusal") ? null : message.optString("refusal", null);
        if (refusal != null && refusal.length() > 0) {
            throw new AiException(AiException.KIND_CONTENT, label(), refusal);
        }
        return message.optString("content", "");
    }
}

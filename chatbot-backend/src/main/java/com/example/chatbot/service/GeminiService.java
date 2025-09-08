package com.example.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

    @Value("${gemini.api.key}")
    private String geminiApiKey;

    @Value("${gemini.model:gemini-1.5-flash}")
    private String geminiModel;

    
    @Value("${chatbot.systemPrompt:You are GearBot, Gear9's assistant. Always be concise, factual, and professional. Answer in the same language as the user's last message (French or English). Do not greet unless explicitly asked. Prefer the company context provided (name, address, about, services, expertises, projects, awards). If information is missing, say so briefly and offer alternatives.}")
    private String systemPrompt;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String generateReply(String userMessage) {
        return generateReply(userMessage, null);
    }

    public String generateReply(String userMessage, String contextText) {
        return generateReply(userMessage, contextText, null);
    }

    public String generateReply(String userMessage, String contextText, String preferredLanguage) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            return "Server is missing Gemini API key.";
        }

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + geminiModel + ":generateContent?key=" + geminiApiKey;

        Map<String, Object> body = new HashMap<>();
        
        // Use a translator-specific system prompt to avoid any chat persona influence
        String translatorSystem = "You are a strict translation engine. Output ONLY the translated text in the requested language. Do not add greetings, explanations, or quotes. Preserve Markdown and list formatting.";
        body.put("system_instruction", Map.of(
                "parts", List.of(Map.of("text", translatorSystem))
        ));

        Map<String, Object> userContent = new HashMap<>();
        userContent.put("role", "user");
        String languageInstruction = (preferredLanguage != null && !preferredLanguage.isBlank())
                ? (preferredLanguage.equalsIgnoreCase("en")
                    ? "Please answer in English only. Do not greet; reply concisely and professionally."
                    : "Réponds uniquement en français. Ne salue pas; réponds de manière concise et professionnelle.")
                : inferLanguageInstruction(userMessage);
        if (contextText != null && !contextText.isBlank()) {
            String combined = languageInstruction + "\n\nContext (company data):\n" + contextText + "\n\nQuestion:\n" + userMessage;
            userContent.put("parts", List.of(Map.of("text", combined)));
        } else {
            String combined = languageInstruction + "\n\n" + userMessage;
            userContent.put("parts", List.of(Map.of("text", combined)));
        }
        body.put("contents", List.of(userContent));

        // Optional: gentle defaults
        body.put("generationConfig", Map.of(
                "temperature", 0.6,
                "topP", 0.9,
                "topK", 40
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("x-goog-api-key", geminiApiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return "The AI service did not return a response.";
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && parts.size() > 0) {
                    String text = parts.get(0).path("text").asText();
                    if (text == null || text.isBlank()) return "The AI returned an empty response.";
                    String cleaned = cleanResponse(text);
                    return cleaned.isBlank() ? text.trim() : cleaned;
                }
            }
            return "The AI response format was unexpected.";
        } catch (RestClientResponseException e) {
            String apiMessage = extractApiErrorMessage(e.getResponseBodyAsString());
            // Graceful handling for quota / rate limit errors
            int status = e.getStatusCode().value();
            boolean quotaLike = status == 429
                    || (apiMessage != null && apiMessage.toLowerCase().contains("quota"))
                    || (apiMessage != null && apiMessage.toLowerCase().contains("rate limit"))
                    || (apiMessage != null && apiMessage.toLowerCase().contains("exceeded"));
            if (quotaLike) {
                return friendlyQuotaMessage(preferredLanguage);
            }
            if (apiMessage != null && !apiMessage.isBlank()) {
                throw new RuntimeException("Gemini API error: " + apiMessage);
            }
            throw new RuntimeException("Gemini API error: " + e.getStatusCode().value() + " " + e.getStatusText());
        } catch (ResourceAccessException e) {
            throw new RuntimeException("Unable to reach Gemini service. Please check your network.");
        } catch (Exception e) {
            throw new RuntimeException("Failed to process AI response: " + e.getMessage());
        }
    }


    public String translate(String text, String targetLanguage) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            return text;
        }

        String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                + geminiModel + ":generateContent?key=" + geminiApiKey;

        String instruction = targetLanguage != null && targetLanguage.equalsIgnoreCase("en")
                ? "Translate into English. Keep the same Markdown and bullet structure. Do not add any extra words."
                : "Traduire en français. Conserver exactement la structure Markdown et les puces. N'ajoute aucun mot.";

        Map<String, Object> body = new HashMap<>();
        body.put("system_instruction", Map.of(
                "parts", List.of(Map.of("text", systemPrompt))
        ));

        Map<String, Object> userContent = new HashMap<>();
        userContent.put("role", "user");
        userContent.put("parts", List.of(Map.of("text", instruction + "\n\n" + text)));
        body.put("contents", List.of(userContent));

        body.put("generationConfig", Map.of(
                "temperature", 0.2,
                "topP", 0.9,
                "topK", 40
        ));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("x-goog-api-key", geminiApiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return text;
            }
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode candidates = root.path("candidates");
            if (candidates.isArray() && candidates.size() > 0) {
                JsonNode parts = candidates.get(0).path("content").path("parts");
                if (parts.isArray() && parts.size() > 0) {
                    String translated = parts.get(0).path("text").asText();
                    return translated != null && !translated.isBlank() ? cleanResponse(translated) : text;
                }
            }
        } catch (RestClientResponseException e) {
            String apiMessage = extractApiErrorMessage(e.getResponseBodyAsString());
            int status = e.getStatusCode().value();
            boolean quotaLike = status == 429
                    || (apiMessage != null && apiMessage.toLowerCase().contains("quota"))
                    || (apiMessage != null && apiMessage.toLowerCase().contains("rate limit"))
                    || (apiMessage != null && apiMessage.toLowerCase().contains("exceeded"));
            if (quotaLike) {
                return text;
            }
            throw new RuntimeException("Translation failed: " + (apiMessage != null ? apiMessage : e.getStatusText()));
        } catch (Exception e) {
            throw new RuntimeException("Translation failed: " + e.getMessage());
        }
        return text;
    }

    private String cleanResponse(String text) {
        if (text == null) return "";
        String cleaned = text.trim();
        
        cleaned = cleaned.replaceFirst("(?is)^(?:[\\s\u00A0])*(bonjour|salut|bonsoir|hello|hi)(?:[\\s\u00A0])*[!.,]*((?:\r?\n)+)?", "");

        String lower = cleaned.toLowerCase();
        if (lower.startsWith("bonjour") || lower.startsWith("hello") || lower.startsWith("hi") || lower.startsWith("salut") || lower.startsWith("bonsoir")) {
            String[] split = cleaned.split("\\R", 2);
            cleaned = (split.length > 1) ? split[1].trim() : "";
        }
        return cleaned.trim();
    }

    private String inferLanguageInstruction(String userMessage) {
        if (userMessage == null) return "";
        String q = userMessage.toLowerCase();
        boolean english = containsAny(q,
                "hello", "hi", "what", "where", "who", "when", "how", "address", "services", "projects",
                "clients", "awards", "expertise", "overview", "about");
        if (english) {
            return "Please answer in English only. Do not greet; reply concisely and professionally.";
        }
        boolean french = containsAny(q,
                "bonjour", "salut", "adresse", "services", "projets", "clients", "récompenses", "recompenses",
                "expertise", "à propos", "apropos", "apercu", "où", "ou", "qui", "quels", "quelles");
        if (french) {
            return "Réponds uniquement en français. Ne salue pas; réponds de manière concise et professionnelle.";
        }
        return "Réponds dans la langue de la question (FR/EN). Ne salue pas; réponds de manière concise.";
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private String extractApiErrorMessage(String responseBody) {
        try {
            if (responseBody == null || responseBody.isBlank()) return null;
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode error = root.path("error").path("message");
            if (!error.isMissingNode()) {
                return error.asText();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String friendlyQuotaMessage(String preferredLanguage) {
        boolean english = preferredLanguage != null && preferredLanguage.equalsIgnoreCase("en");
        if (english) {
            return "I'm currently out of AI requests. You can still ask me about Gear9's address, services, projects, clients, awards, or expertise, and I'll answer from my built-in knowledge.";
        }
        return "Je n'ai plus de requêtes IA pour le moment. Vous pouvez toujours me demander l'adresse, les services, les projets, les clients, les distinctions ou l'expertise de Gear9, et je répondrai avec mes connaissances intégrées.";
    }
} 
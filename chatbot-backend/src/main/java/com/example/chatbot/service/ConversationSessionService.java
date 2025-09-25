package com.example.chatbot.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConversationSessionService {
    
    private final Map<String, String> conversationLanguages = new ConcurrentHashMap<>();
    
    /**
     * Detect the language of the user's message and store it for the conversation
     * @param conversationId The unique conversation identifier
     * @param userMessage The user's message
     * @return The detected language ("en" for English, "fr" for French)
     */
    public String detectAndStoreLanguage(String conversationId, String userMessage) {
        if (userMessage == null) {
            return conversationLanguages.getOrDefault(conversationId, "fr");
        }

       
        String detectedLanguage = detectLanguage(userMessage);
        if (conversationId != null) {
            conversationLanguages.put(conversationId, detectedLanguage);
        }
        return detectedLanguage;
    }

    public String detectLanguageForMessage(String userMessage) {
        return detectLanguage(userMessage);
    }
    
    /**
     * Force and store a language for the conversation ("en" or "fr").
     */
    public void setConversationLanguage(String conversationId, String fixedLanguage) {
        if (conversationId == null || fixedLanguage == null) return;
        String lang = ("en".equalsIgnoreCase(fixedLanguage)) ? "en" : "fr";
        conversationLanguages.put(conversationId, lang);
    }
    
    /**
     * Get the stored language for a conversation
     * @param conversationId The conversation identifier
     * @return The language ("en" for English, "fr" for French), defaults to "fr"
     */
    public String getConversationLanguage(String conversationId) {
        if (conversationId == null) {
            return "fr";
        }
        return conversationLanguages.getOrDefault(conversationId, "fr");
    }
    
    /**
     * Clear a conversation session (useful for cleanup)
     * @param conversationId The conversation identifier
     */
    public void clearConversation(String conversationId) {
        if (conversationId != null) {
            conversationLanguages.remove(conversationId);
        }
    }
    
    /**
     * Detect language based on common keywords and patterns
     * @param message The user message
     * @return "en" for English, "fr" for French
     */
    private String detectLanguage(String message) {
        if (message == null || message.trim().isEmpty()) {
            return "fr"; // Default to French
        }
        
        String lowerMessage = message.toLowerCase().trim();

        // 1) Strong, exclusive indicators
        int enScore = 0;
        int frScore = 0;

        if (containsAny(lowerMessage, "hello", "hi", "hey", "what", "where", "when", "who", "how", "why",
                "address", "company", "about", "overview")) enScore += 3;
        if (containsAny(lowerMessage, "bonjour", "salut", "bonsoir", "quoi", "où", "ou ", "qui", "quand", "comment", "pourquoi",
                "adresse", "entreprise", "société", "societe", "à propos", "apropos")) frScore += 3;

        // 2) Presence of accented characters is a strong French signal
        if (lowerMessage.matches(".*[éèêàùâîïôç].*")) frScore += 2;

        // 3) Common stopwords heuristics (weak signals)
        if (containsAny(lowerMessage, " the ", " and ", " of ", " in ", " to ", " for ")) enScore += 1;
        if (containsAny(lowerMessage, " le ", " la ", " les ", " des ", " du ", " et ")) frScore += 1;

        // 2.5) English ASCII heuristic: if text is plain ASCII and contains common EN terms
        boolean looksAscii = lowerMessage.matches("[\\p{ASCII}]+");
        if (looksAscii && containsAny(lowerMessage, "what", "services", "provided", "by", "address", "company")) {
            enScore += 2;
        }

        if (enScore > frScore) return "en";
        if (frScore > enScore) return "fr";

        // 4) Tie-breakers
        if (lowerMessage.contains("address") || lowerMessage.contains("where")) return "en";
        if (lowerMessage.contains("adresse") || lowerMessage.contains("où") || lowerMessage.contains("ou ")) return "fr";

        // Default to French
        return "fr";
    }
    
    private boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
    
}

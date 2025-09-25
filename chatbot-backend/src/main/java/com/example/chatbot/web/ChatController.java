package com.example.chatbot.web;

import com.example.chatbot.dto.ChatRequest;
import com.example.chatbot.dto.ChatResponse;
import com.example.chatbot.service.GeminiService;
import com.example.chatbot.service.CompanyQaService;
import com.example.chatbot.service.ConversationSessionService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final GeminiService geminiService;
    private final CompanyQaService companyQaService;
    private final ConversationSessionService conversationSessionService;

    public ChatController(GeminiService geminiService, CompanyQaService companyQaService, ConversationSessionService conversationSessionService) {
        this.geminiService = geminiService;
        this.companyQaService = companyQaService;
        this.conversationSessionService = conversationSessionService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        String userMsg = request.getMessage();
        String conversationId = request.getConversationId();
        String preferredLanguage = request.getLanguage();
        
    
        String conversationLanguage;
        if (preferredLanguage != null && ("en".equalsIgnoreCase(preferredLanguage) || "fr".equalsIgnoreCase(preferredLanguage))) {
            conversationLanguage = preferredLanguage.toLowerCase();
            // Persist the chosen language for this conversation so subsequent turns are consistent
            conversationSessionService.setConversationLanguage(conversationId, conversationLanguage);
        } else {
            conversationLanguage = conversationSessionService.detectLanguageForMessage(userMsg);
            conversationSessionService.detectAndStoreLanguage(conversationId, userMsg);
        }
        boolean isEnglish = "en".equals(conversationLanguage);
        
        // 1) Try direct deterministic answer from JSON 
        String direct = companyQaService.answer(userMsg, isEnglish);
        if (direct != null && !direct.startsWith("Je suis désolé") && !direct.startsWith("I'm sorry")) {
            return new ChatResponse(direct);
        }
        
        // 2) If no direct answer, try to provide a basic response without Gemini API
        String basicResponse = provideBasicResponse(userMsg, isEnglish);
        if (basicResponse != null) {
            return new ChatResponse(basicResponse);
        }
        
        // 3) Only as last resort, try Gemini API
        try {
            String context = companyQaService.buildContext(userMsg);
            String reply = geminiService.generateReply(userMsg, context, conversationLanguage);
            return new ChatResponse(reply);
        } catch (Exception e) {
          
            String errorMessage = isEnglish 
                ? "I'm sorry, I'm currently experiencing technical difficulties. Please try asking about Gear9's address, services, projects, clients, awards, or expertise."
                : "Je suis désolé, je rencontre actuellement des difficultés techniques. Veuillez essayer de demander l'adresse, les services, les projets, les clients, les distinctions ou l'expertise de Gear9.";
            return new ChatResponse(errorMessage);
        }
    }
    
   
    private String provideBasicResponse(String userMessage, boolean isEnglish) {
        String lowerMessage = userMessage.toLowerCase().trim();
        
        // Handle "what is Gear9" type questions
        if (lowerMessage.contains("gear9") && (lowerMessage.contains("quoi") || lowerMessage.contains("what"))) {
            return isEnglish 
                ? "**Gear9** is a Moroccan digital transformation agency founded in 2019. We specialize in implementing digital culture, creating unique and engaging digital experiences, and using technology and data to drive business growth. We operate with an agile and innovative methodology, focusing on areas such as Digital Culture and Transformation, Product Thinking, Customer Experience and Automation, as well as Behavioral Analysis."
                : "**Gear9** est une agence marocaine de transformation digitale fondée en 2019. Elle se spécialise dans la mise en œuvre de la culture digitale, la création d'expériences digitales uniques et engageantes, et l'utilisation de la technologie et des données pour stimuler la croissance des entreprises. L'agence opère avec une méthodologie agile et innovante, se concentrant sur des domaines tels que la Culture et la Transformation Digitale, le Product Thinking, l'Expérience Client et l'Automatisation, ainsi que l'Analyse Comportementale.";
        }
        
        // Handle address questions
        if (lowerMessage.contains("adresse") || lowerMessage.contains("address") || lowerMessage.contains("où") || lowerMessage.contains("where")) {
            return isEnglish 
                ? "**Gear9**'s address: 219 Bd Zerktouni, angle Bd Brahim Roudani, Casablanca"
                : "Adresse de **Gear9** : 219 Bd Zerktouni, angle Bd Brahim Roudani, Casablanca";
        }
        
        // Handle general company information
        if (lowerMessage.contains("entreprise") || lowerMessage.contains("company") || lowerMessage.contains("société")) {
            return isEnglish 
                ? "**Gear9** is an agency specializing in Salesforce Digital Staff Augmentation. We help companies with digital transformation, Salesforce implementation, and creating engaging digital experiences."
                : "**Gear9** est une agence spécialisée dans la Régie Salesforce Digital. Nous aidons les entreprises dans leur transformation digitale, l'implémentation Salesforce et la création d'expériences digitales engageantes.";
        }
        
        // If no basic response matches, return null to try Gemini API
        return null;
    }
    
  @GetMapping(path = "/subjects", produces = MediaType.APPLICATION_JSON_VALUE)
  public java.util.List<String> subjects() {
    return companyQaService.getSubjects();
  }
  
 }
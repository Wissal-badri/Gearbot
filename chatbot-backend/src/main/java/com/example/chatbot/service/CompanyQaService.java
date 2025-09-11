package com.example.chatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class CompanyQaService {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JsonNode root;
    private final GeminiService geminiService;

    public CompanyQaService(GeminiService geminiService) {
        this.geminiService = geminiService;
        this.root = loadDataJson();
    }

    public String answer(String questionRaw) {
        boolean isEnglish = detectEnglish(questionRaw);
        return answer(questionRaw, isEnglish);
    }

    // Improved language detection: returns true for English, false for French
    private boolean detectEnglish(String question) {
        if (question == null) return false;
        String q = question.toLowerCase(Locale.ROOT);

        // English and French keywords
        String[] englishWords = {"what", "who", "where", "how", "company", "address", "service", "project", "client", "award", "expertise", "hello", "hi", "about", "overview", "leader", "director", "customer", "customers", "achievement", "achievements"};
        String[] frenchWords = {"quoi", "qui", "où", "ou", "comment", "entreprise", "adresse", "service", "projet", "client", "récompense", "expertise", "bonjour", "salut", "présentation", "présentez", "dirige", "direction", "réalisation", "réalisations", "apropos", "à propos", "apercu"};

        int enCount = 0, frCount = 0;
        for (String word : englishWords) if (q.contains(word)) enCount++;
        for (String word : frenchWords) if (q.contains(word)) frCount++;

        if (enCount == 0 && frCount == 0) {
            // fallback: guess by first word
            if (q.matches("^(le|la|les|un|une|des|est|être|vous|nous|bonjour|merci|salut).*")) return false;
            if (q.matches("^(what|who|where|how|hello|hi|about).*")) return true;
            // fallback: default to French
            return false;
        }
        return enCount >= frCount;
    }

    public String answer(String questionRaw, boolean isEnglish) {
        if (questionRaw == null || questionRaw.isBlank()) {
            return isEnglish 
                ? "I'm sorry, I don't have information on this topic."
                : "Je suis désolé, je ne trouve pas d'information à ce sujet.";
        }

        final String question = questionRaw.toLowerCase(Locale.ROOT);

        // Identity: answer regardless of leading fillers (hey/ay/hi) or minor variations
        if (isIdentityQuery(question)) {
            return isEnglish
                    ? "I am Gear9's assistant, here to help you with any information you need about Gear9."
                    : "Je suis l'assistant de Gear9, là pour vous aider avec toutes les informations dont vous avez besoin sur Gear9.";
        }

        // Do not auto-greet; only greet if the user explicitly asks for a greeting
        if (isLikelyGreetingOnly(question) && containsAny(question, "dis bonjour", "say hello")) {
            return isEnglish ? "Hello!" : "Bonjour !";
        }

        if (root == null || root.path("data").isMissingNode()) {
            return isEnglish 
                ? "I'm sorry, I don't have information on this topic."
                : "Je suis désolé, je ne trouve pas d'information à ce sujet.";
        }

        JsonNode data = root.path("data");

        // Determine if the question is about the company context at all
        boolean looksCompanyRelated = containsAny(question,
                // FR
                "gear9", "entreprise", "société", "societe", "adresse", "service",
                "projet", "client", "réalisation", "recompense", "récompense",
                "pdg", "dirige", "direction", "expertise", "salesforce", "marketing cloud",
                "mulesoft", "tableau", "data cloud", "apropos", "à propos", "apercu", "présentation",
                "qui êtes-vous", "qui etes vous", "où", "localisation",
                // EN
                "company", "address", "service", "services", "project", "projects", "client", "clients",
                "customer", "customers", "award", "awards", "achievement", "achievements", "ceo", "leader",
                "director", "about", "overview", "where", "location");

        if (!looksCompanyRelated) {
            // If the user greets in EN, reply politely in EN
            if (isEnglish && containsAny(question, "hello", "hi", "hey")) {
                return "Hello! Ask me anything about Gear9 (address, services, projects, awards, expertise, etc.).";
            }
            // For short/neutral messages, provide a friendly nudge in the detected language
            if (question.length() < 16 || containsAny(question, "salut", "bonjour", "hey", "hello", "hi")) {
                return isEnglish
                        ? "I can help with Gear9: address, services, expertises, projects, clients and awards. What would you like to know?"
                        : "Je peux vous renseigner sur Gear9 : adresse, services, expertises, projets, clients et distinctions. Que souhaitez-vous savoir ?";
            }
            return isEnglish
                    ? "I'm sorry, I can only answer questions related to this company."
                    : "Je suis désolé, je ne peux répondre qu'aux questions en rapport avec l'entreprise.";
        }

        // 1) Adresse / localisation
        if (containsAny(question,
                // FR
                "adresse", "localisation", "où", "ou", "située", "situee", "siège", "siege", "siège social",
                // EN
                "address", "where", "location", "located", "headquarters", "hq", "office", "offices", "head office")) {
            String adresse = textOrNull(data.path("adresse"));
            if (adresse != null) {
                String header = isEnglish ? "Address of **Gear9**:\n" : "Adresse de **Gear9**:\n";
                return header + adresse;
            } else {
                return isEnglish
                    ? "I'm sorry, I don't have information on this."
                    : "Je suis désolé, je ne trouve pas d'information à ce sujet.";
            }
        }

        // 2) Nom de l'entreprise
        if (containsAny(question, "nom de l'entreprise", "nom de l'entr", "comment s'appelle", "comment s'appelle l'entr", "qui êtes-vous", "qui etes vous", "présentez", "presentation",
                "company name", "what is the company name", "what's the company name")) {
            String nom = textOrNull(data.path("nom_entreprise"));
            if (nom != null) {
                String header = isEnglish ? "Company name of **Gear9**:\n" : "Nom de **Gear9**:\n";
                return header + nom;
            }
        }

        // 3) À propos / aperçu
        if (containsAny(question, "à propos", "apropos", "apercu", "présentation", "presentation", "que fait", "qui est", "quoi fait", "c'est quoi", "what is",
                "about", "overview", "what do you do", "who is")) {
            
            // Provide direct responses without any API calls
            if (isEnglish) {
                return "**Gear9** is a Moroccan digital transformation agency founded in 2019. We specialize in implementing digital culture, creating unique and engaging digital experiences, and using technology and data to drive business growth. We operate with an agile and innovative methodology, focusing on areas such as Digital Culture and Transformation, Product Thinking, Customer Experience and Automation, as well as Behavioral Analysis.";
            } else {
                return "**Gear9** est une agence marocaine de transformation digitale fondée en 2019. Elle se spécialise dans la mise en œuvre de la culture digitale, la création d'expériences digitales uniques et engageantes, et l'utilisation de la technologie et des données pour stimuler la croissance des entreprises. L'agence opère avec une méthodologie agile et innovante, se concentrant sur des domaines tels que la Culture et la Transformation Digitale, le Product Thinking, l'Expérience Client et l'Automatisation, ainsi que l'Analyse Comportementale.";
            }
        }

        // 4) Services / offres
        if (containsAny(question, "service", "offre", "proposez", "proposés", "proposes",
                "service", "services", "offer", "offers", "offering", "offerings", "what do you offer")) {
            JsonNode services = data.path("services");
            if (services.isArray() && services.size() > 0) {
                List<String> items = new ArrayList<>();
                for (JsonNode s : services) {
                    String nom = textOrNullLang(s, "nom", isEnglish);
                    String description = textOrNullLang(s, "description", isEnglish);
                    if (nom != null && description != null) {
                        String line = "- **" + nom + "**: " + description;
                        items.add(line);
                    } else if (nom != null) {
                        String line = "- **" + nom + "**";
                        items.add(line);
                    }
                }
                if (!items.isEmpty()) {
                    String header = isEnglish ? "Services offered by **Gear9**:" : "Les services proposés par **Gear9** sont :";
                    return header + "\n" + String.join("\n", items);
                }
            }
            return isEnglish ? "I'm sorry, I don't have information on this." : "Je suis désolé, je ne trouve pas d'information à ce sujet.";
        }

        // 5) Direction / PDG
        if (containsAny(question, "pdg", "direction", "dirige", "dirigeant", "ceo", "leader", "director")) {
            JsonNode direction = data.path("direction");
            if (direction.isArray() && direction.size() > 0) {
                JsonNode d = direction.get(0);
                String role = textOrNull(d.path("role"));
                String nom = textOrNull(d.path("nom"));
                List<String> parts = new ArrayList<>();
                if (role != null) parts.add(role);
                if (nom != null) parts.add(nom);
                if (!parts.isEmpty()) {
                    String header = isEnglish ? "Leadership of **Gear9**:" : "Direction de **Gear9**:";
                    return header + "\n- " + String.join(" — ", parts);
                }
            }
            return isEnglish ? "I'm sorry, I don't have information on this." : "Je suis désolé, je ne trouve pas d'information à ce sujet.";
        }

        // 6) Réalisations et récompenses
        if (containsAny(question, "réalisation", "realisations", "récompense", "recompenses", "prix", "exploits",
                "award", "awards", "achievement", "achievements", "rewards")) {
            JsonNode rr = data.path("realisations_et_recompenses");
            if (rr.isArray() && rr.size() > 0) {
                List<String> items = new ArrayList<>();
                for (JsonNode r : rr) {
                    List<String> parts = new ArrayList<>();
                    String titre = textOrNull(r.path("titre"));
                    if (titre != null) parts.add(titre);
                    if (!r.path("annee").isMissingNode()) parts.add(String.valueOf(r.path("annee").asInt()));
                    String lieu = textOrNull(r.path("lieu"));
                    if (lieu != null) parts.add(lieu);
                    if (!parts.isEmpty()) items.add("- " + String.join(" — ", parts));
                }
                if (!items.isEmpty()) {
                    String header = isEnglish ? "Awards and achievements of **Gear9**:" : "Distinctions et réalisations de **Gear9** :";
                    return header + "\n" + String.join("\n", items);
                }
            }
            return isEnglish ? "I'm sorry, I don't have information on this." : "Je suis désolé, je ne trouve pas d'information à ce sujet.";
        }

        // 7) Projets / clients (avec filtrage par secteur si mentionné)
        if (containsAny(question, "projet", "client", "référence", "references", "références",
                "project", "projects", "client", "clients", "customer", "customers", "reference", "portfolio")) {
            JsonNode projets = data.path("projets");
            if (projets.isArray() && projets.size() > 0) {
                String secteurFilter = detectSecteur(question);
                List<String> items = new ArrayList<>();
                for (JsonNode p : projets) {
                    String secteur = textOrNull(p.path("secteur"));
                    if (secteurFilter == null || (secteur != null && secteur.toLowerCase(Locale.ROOT).contains(secteurFilter))) {
                        String nom = textOrNull(p.path("nom"));
                        String type = textOrNullLang(p, "type", isEnglish);
                        String desc = textOrNullLang(p, "description", isEnglish);

                        // Robust English fallback for well-known projects to ensure parity
                        if (isEnglish) {
                            String pid = textOrNull(p.path("id"));
                            if (pid != null) {
                                switch (pid) {
                                    case "groupe_ocp":
                                        if (type == null) type = "Salesforce";
                                        break;
                                    case "sorec":
                                        if (type == null) type = "Digital asset redesign strategy";
                                        break;
                                    case "bank_of_africa":
                                        if (type == null) type = "Digital Customer Experience";
                                        if (desc == null) desc = "Redefinition of the group's digital customer journey";
                                        break;
                                    case "bank_alyousr":
                                        if (type == null) type = "Marketing Automation";
                                        if (desc == null) desc = "Addressing this major challenge, Bank Al Yousr…";
                                        break;
                                    case "attijariwafa_bank":
                                        if (type == null) type = "Digitalization of the FIAD platform";
                                        break;
                                    case "bmce_capital_bourse":
                                        if (type == null) type = "Stock market activity management platform";
                                        break;
                                    default:
                                        break;
                                }
                            }
                        }
                        // Ensure English queries include some project detail even if *_en fields are missing
                        if (isEnglish && type == null && desc == null) {
                            String secteurName = textOrNull(p.path("secteur"));
                            if (secteurName != null) {
                                type = secteurName;
                            }
                            if (desc == null) {
                                String url = textOrNull(p.path("url"));
                                if (url != null) desc = url;
                            }
                        }

                        List<String> parts = new ArrayList<>();
                        if (nom != null) parts.add(nom);
                        if (type != null) parts.add(type);
                        if (desc != null) parts.add(desc);
                        // As a last resort, if only the name is present, add sector to provide detail
                        if (isEnglish && parts.size() == 1) {
                            String secteurName = textOrNull(p.path("secteur"));
                            if (secteurName != null) {
                                parts.add(secteurName);
                            } else {
                                parts.add("Project");
                            }
                        }
                        // Ensure at least two fields for English readability
                        if (isEnglish && parts.size() == 1) {
                            parts.add("Project details unavailable");
                        }
                        if (!parts.isEmpty()) {
                            String separator = isEnglish ? " - " : " — ";
                            String line = "- " + String.join(separator, parts);
                            items.add(line);
                        }
                    }
                }
                if (!items.isEmpty()) {
                    // keep answer concise
                    if (items.size() > 6) {
                        items = items.subList(0, 6);
                    }
                    String header = isEnglish ? "Clients of **Gear9** and their projects" : "Quelques clients/projets de **Gear9**";
                    if (secteurFilter != null) header += isEnglish ? " (sector: " + secteurFilter + ")" : " (secteur: " + secteurFilter + ")";
                    header += isEnglish ? ":\n" : " :\n";
                    return header + String.join("\n", items);
                }
            }
            return isEnglish ? "I'm sorry, I don't have information on this." : "Je suis désolé, je ne trouve pas d'information à ce sujet.";
        }

        // 8) Expertises principales
        if (containsAny(question, "expertise principale", "expertises principales", "compétence principale", "competence principale",
                "core expertise", "main expertise", "primary expertise")) {
            JsonNode ex = data.path("expertise_principale");
            if (ex.isArray() && ex.size() > 0) {
                List<String> items = new ArrayList<>();
                for (JsonNode e : ex) {
                    String nom = textOrNull(e.path("nom"));
                    String description = textOrNull(e.path("description"));
                    if (nom != null && description != null) {
                        items.add("- **" + nom + "**: " + description);
                    } else if (nom != null) {
                        items.add("- **" + nom + "**");
                    }
                }
                if (!items.isEmpty()) {
                    String header = isEnglish ? "Main expertises of **Gear9**:" : "Expertises principales de **Gear9** :";
                    return header + "\n" + String.join("\n", items);
                }
            }
            return isEnglish ? "I'm sorry, I don't have information on this." : "Je suis désolé, je ne trouve pas d'information à ce sujet.";
        }

        // 9) Détail d'une expertise (Salesforce, régie, digital, etc.)
        JsonNode expertise = data.path("expertise");
        if (expertise.isArray()) {
            // Select which expertise group
            String groupId = null;
            if (containsAny(question, "salesforce", "sales cloud", "service cloud", "marketing cloud", "data cloud", "mulesoft", "tableau")) groupId = "salesforce";
            else if (containsAny(question, "régie", "regie")) groupId = "regie";
            else if (containsAny(question, "digital")) groupId = "digital";

            JsonNode group = null;
            if (groupId != null) {
                for (JsonNode g : expertise) {
                    if (groupId.equals(textOrNull(g.path("id")))) { group = g; break; }
                }
            }
            if (group == null && expertise.size() > 0) {
                group = expertise.get(0);
            }
            if (group != null) {
                JsonNode details = group.path("details");
                if (details.isArray() && details.size() > 0) {
                    List<String> items = new ArrayList<>();
                    for (JsonNode d : details) {
                        String nom = textOrNull(d.path("nom"));
                        String description = textOrNull(d.path("description"));
                        if (nom != null && description != null) {
                            items.add("- **" + nom + "**: " + description);
                        } else if (nom != null) {
                            items.add("- **" + nom + "**");
                        }
                    }
                    if (!items.isEmpty()) {
                        String label = "cette expertise";
                        if ("salesforce".equals(groupId)) label = isEnglish ? "Salesforce" : "Salesforce";
                        else if ("regie".equals(groupId)) label = isEnglish ? "Staff augmentation" : "Régie";
                        else if ("digital".equals(groupId)) label = isEnglish ? "Digital" : "Digital";
                        String header = isEnglish ? "Details of **Gear9**'s " + label + " expertise:" : "Détails de l'expertise " + label + " de **Gear9** :";
                        return header + "\n" + String.join("\n", items);
                    }
                }
            }
        }

        // 10) Nom de l'entreprise fallback
        if (containsAny(question, "nom", "appelle", "appelez", "name")) {
            String nom = textOrNull(data.path("nom_entreprise"));
            if (nom != null) {
                String header = isEnglish ? "Company name of **Gear9**:\n" : "Nom de **Gear9**:\n";
                return header + nom;
            }
        }

        // Default: unknown within company scope
        return isEnglish ? "I'm sorry, I don't have information on this." : "Je suis désolé, je ne trouve pas d'information à ce sujet.";
    }


    public String buildContext(String questionRaw) {
        if (root == null || root.path("data").isMissingNode()) return null;
        if (questionRaw == null || questionRaw.isBlank()) return null;
        String q = questionRaw.toLowerCase(Locale.ROOT);
        JsonNode data = root.path("data");

        StringBuilder sb = new StringBuilder();
        // Company basics (always include for better grounding)
        String nom = textOrNull(data.path("nom_entreprise"));
        String apropos = textOrNull(data.path("apropos"));
        String apercu = textOrNull(data.path("apercu"));
        String adresse = textOrNull(data.path("adresse"));
        if (nom != null) sb.append("Nom: ").append(nom).append('\n');
        if (adresse != null) sb.append("Adresse: ").append(adresse).append('\n');
        if (apropos != null) sb.append("À propos: ").append(apropos).append('\n');
        if (apercu != null) sb.append("Aperçu: ").append(apercu).append('\n');

        // Services
        if (containsAny(q, "service", "offre", "offers", "offerings")) {
            JsonNode services = data.path("services");
            if (services.isArray()) {
                int count = 0;
                for (JsonNode s : services) {
                    String n = textOrNull(s.path("nom"));
                    String d = textOrNull(s.path("description"));
                    if (n != null) {
                        sb.append("Service: ").append(n);
                        if (d != null) sb.append(" — ").append(d);
                        sb.append('\n');
                        if (++count >= 6) break;
                    }
                }
            }
        }

        // Direction
        if (containsAny(q, "pdg", "direction", "ceo", "leader", "director")) {
            JsonNode direction = data.path("direction");
            if (direction.isArray() && direction.size() > 0) {
                JsonNode d = direction.get(0);
                String role = textOrNull(d.path("role"));
                String name = textOrNull(d.path("nom"));
                if (role != null || name != null) {
                    sb.append("Direction: ");
                    if (role != null) sb.append(role).append(": ");
                    if (name != null) sb.append(name);
                    sb.append('\n');
                }
            }
        }

        // Réalisations / récompenses
        if (containsAny(q, "réalisation", "recompense", "récompense", "prix", "exploits", "award", "achievements", "rewards")) {
            JsonNode rr = data.path("realisations_et_recompenses");
            if (rr.isArray()) {
                int count = 0;
                for (JsonNode r : rr) {
                    String titre = textOrNull(r.path("titre"));
                    String lieu = textOrNull(r.path("lieu"));
                    String annee = r.path("annee").isMissingNode() ? null : String.valueOf(r.path("annee").asInt());
                    List<String> parts = new ArrayList<>();
                    if (titre != null) parts.add(titre);
                    if (annee != null) parts.add(annee);
                    if (lieu != null) parts.add(lieu);
                    if (!parts.isEmpty()) {
                        sb.append("Récompense: ").append(String.join(" — ", parts)).append('\n');
                        if (++count >= 6) break;
                    }
                }
            }
        }

        // Projets (optional filtering by secteur)
        if (containsAny(q, "projet", "client", "project", "clients", "portfolio", "reference")) {
            JsonNode projets = data.path("projets");
            String secteurFilter = detectSecteur(q);
            if (projets.isArray()) {
                int count = 0;
                for (JsonNode p : projets) {
                    String secteur = textOrNull(p.path("secteur"));
                    if (secteurFilter == null || (secteur != null && secteur.toLowerCase(Locale.ROOT).contains(secteurFilter))) {
                        String name = textOrNull(p.path("nom"));
                        String type = textOrNull(p.path("type"));
                        String desc = textOrNull(p.path("description"));
                        List<String> parts = new ArrayList<>();
                        if (name != null) parts.add(name);
                        if (type != null) parts.add(type);
                        if (desc != null) parts.add(desc);
                        if (!parts.isEmpty()) {
                            sb.append("Projet: ").append(String.join(" — ", parts)).append('\n');
                            if (++count >= 6) break;
                        }
                    }
                }
            }
        }

        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private boolean isIdentityQuery(String q) {
        if (q == null) return false;
        String s = q.trim();
        // Strip common leading fillers
        if (s.startsWith("hey ") || s.startsWith("hi ") || s.startsWith("hello ") || s.startsWith("ay ")) {
            s = s.substring(s.indexOf(' ') + 1);
        }
        // Direct patterns in EN and FR
        if (containsAny(s,
                "who are you", "who r u", "who're you", "who are u",
                "qui es-tu", "qui es tu", "qui êtes-vous", "qui etes vous", "tu es qui", "t'es qui")) {
            return true;
        }
        // Heuristic: contains who + you and either are/r
        if (s.contains("who") && s.contains("you") && (s.contains(" are ") || s.contains(" r "))) return true;
        return false;
    }

    private String ensureLanguage(String text, boolean isEnglish) {
        try {
            return geminiService.translate(text, isEnglish ? "en" : "fr");
        } catch (Exception e) {
            // If translation fails, return the original text
            return text;
        }
    }

    private boolean isLikelyGreetingOnly(String question) {
        if (question == null) return false;
        if (question.contains("?")) return false;
        // Very short messages are likely just greetings
        if (question.length() > 24) return false;
        // If it includes obvious intent words, it's not a pure greeting
        if (containsAny(question,
                "adresse", "address", "service", "services", "projet", "projects", "client", "clients",
                "réalisation", "recompense", "exploits", "award", "achievements", "rewards", "expertise", "qui", "quoi", "comment",
                "où", "ou", "quelle", "quels", "quelles")) {
            return false;
        }
        return true;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        String t = node.asText(null);
        return (t == null || t.isBlank()) ? null : t;
    }

    private String textOrNullLang(JsonNode parent, String baseField, boolean isEnglish) {
        if (parent == null) return null;
        if (isEnglish) {
            String en = textOrNull(parent.path(baseField + "_en"));
            if (en != null) return en;
        }
        return textOrNull(parent.path(baseField));
    }

    private String detectSecteur(String question) {
        // map of lower-case secteur keywords as they appear in JSON
        String[] secteurs = new String[]{
                // FR
                "secteur public", "finance", "assurance", "télécom", "telecom", "retail",
                "industrie", "education", "éducation", "hôtellerie", "hotellerie", "immobilier",
                // EN
                "public sector", "insurance", "telecommunications", "telecommunication", "telecom",
                "industry", "hospitality", "real estate"
        };
        List<String> found = new ArrayList<>();
        for (String s : secteurs) {
            if (question.contains(s)) found.add(normalizeSecteur(s));
        }
        if (found.isEmpty()) return null;
        return found.get(0);
    }

    private String normalizeSecteur(String s) {
        // match JSON values
        switch (s) {
            case "telecom": return "télécom";
            case "éducation": return "education";
            case "hotellerie": return "hôtellerie";
            case "public sector": return "secteur public";
            case "insurance": return "assurance";
            case "telecommunications": return "télécom";
            case "telecommunication": return "télécom";
            case "industry": return "industrie";
            case "hospitality": return "hôtellerie";
            case "real estate": return "immobilier";
            default: return s;
        }
    }

    private JsonNode loadDataJson() {
        // 1) Try absolute/relative file at module root
        try {
            Path path = new File("data.json").toPath();
            if (Files.exists(path)) {
                byte[] bytes = Files.readAllBytes(path);
                return objectMapper.readTree(bytes);
            }
        } catch (IOException ignored) {}

        // 2) Try classpath resource
        try {
            ClassPathResource cpr = new ClassPathResource("data.json");
            if (cpr.exists()) {
                return objectMapper.readTree(cpr.getInputStream());
            }
        } catch (IOException ignored) {}

        return null;
    }
}



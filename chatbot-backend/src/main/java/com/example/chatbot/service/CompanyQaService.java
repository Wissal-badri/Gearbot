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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


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

        final String question = normalize(questionRaw);

        // Alias-based fast-path from data.subjects.aliases
        String aliasAnswer = tryAliasMatch(question, isEnglish);
        if (aliasAnswer != null) {
            return aliasAnswer;
        }

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
        // Use word-boundary matching for short tokens like "ou" to avoid matching inside English words (e.g., "about")
        boolean askAddress =
                containsAny(question,
                    // FR (long tokens)
                    "adresse", "localisation", "située", "situee", "siège", "siege", "siège social",
                    // EN
                    "address", "location", "located", "headquarters", "hq", "office", "offices", "head office")
                || containsAnyWord(question, "où", "ou", "where");

        if (askAddress) {
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

        // 3) À propos / aperçu — FR: C'est quoi Gear9 ?; EN: Tell me about Gear9
        if (containsAny(question,
                // FR
                "c'est quoi gear9", "c est quoi gear9", "que fait gear9", "qui est gear9",
                // EN
                "tell me about gear9")) {

            // If the query also mentions a specific topic (e.g., Salesforce),
            // defer the generic about answer so specific handlers can respond.
            boolean mentionsSpecificTopic = containsAny(question,
                    // Salesforce stack
                    "salesforce", "sales cloud", "service cloud", "marketing cloud", "data cloud", "mulesoft", "tableau",
                    // Other expertise groups / topics
                    "digital", "product thinking", "customer experience", "automation", "régie", "regie", "staff augmentation");

            if (!mentionsSpecificTopic) {
                // Provide direct responses without any API calls
                if (isEnglish) {
                    return "**Gear9** is a Moroccan digital transformation agency founded in 2019. We specialize in implementing digital culture, creating unique and engaging digital experiences, and using technology and data to drive business growth. We operate with an agile and innovative methodology, focusing on areas such as Digital Culture and Transformation, Product Thinking, Customer Experience and Automation, as well as Behavioral Analysis.";
                } else {
                    return "**Gear9** est une agence marocaine de transformation digitale fondée en 2019. Elle se spécialise dans la mise en œuvre de la culture digitale, la création d'expériences digitales uniques et engageantes, et l'utilisation de la technologie et des données pour stimuler la croissance des entreprises. L'agence opère avec une méthodologie agile et innovante, se concentrant sur des domaines tels que la Culture et la Transformation Digitale, le Product Thinking, l'Expérience Client et l'Automatisation, ainsi que l'Analyse Comportementale.";
                }
            }
            // else: continue to specific expertise/services handling below
        }

        // 4) Services / offres
        if (containsAny(question, "service", "offre", "proposez", "proposés", "proposes",
                "services", "offer", "offers", "offering", "offerings", "what do you offer", "what services")) {
            JsonNode services = data.path("services");
            if (services.isArray() && services.size() > 0) {
                List<String> names = new ArrayList<>();
                List<String> snippets = new ArrayList<>();
                int count = 0;
                for (JsonNode s : services) {
                    String nom = textOrNullLang(s, "nom", isEnglish);
                    String description = textOrNullLang(s, "description", isEnglish);
                    if (nom != null) {
                        names.add(nom);
                        if (description != null) {
                            snippets.add(nom + ": " + description);
                        }
                        count++;
                        if (count >= 4) break;
                    }
                }
                if (!names.isEmpty()) {
                    String joined = joinWithAnd(names, isEnglish);
                    String lead = isEnglish ? "Gear9 offers services such as " : "Gear9 propose des services tels que ";
                    String sentence = lead + joined + ".";
                    if (!snippets.isEmpty()) {
                        String examplesLead = isEnglish ? " For example: " : " Par exemple : ";
                        String examples = String.join(isEnglish ? "; " : "; ", snippets);
                        sentence += examplesLead + examples + ".";
                    }
                    return ensureEnglish(sentence, isEnglish);
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
                if (role != null || nom != null) {
                    String out = isEnglish
                            ? ("Gear9 is led by " + (role != null ? role + " " : "") + (nom != null ? nom : "") + ".")
                            : ("Gear9 est dirigée par " + (role != null ? role + " " : "") + (nom != null ? nom : "") + ".");
                    return ensureEnglish(out, isEnglish);
                }
            }
            return isEnglish ? "I'm sorry, I don't have information on this." : "Je suis désolé, je ne trouve pas d'information à ce sujet.";
        }

        // 6) Réalisations et récompenses
        if (containsAny(question, "réalisation", "realisations", "récompense", "recompenses", "prix", "exploits",
                "award", "awards", "achievement", "achievements", "rewards")) {
            JsonNode rr = data.path("realisations_et_recompenses");
            if (rr.isArray() && rr.size() > 0) {
                Integer fromYear = extractYear(question);
                List<String> phrases = new ArrayList<>();
                for (JsonNode r : rr) {
                    int year = r.path("annee").isMissingNode() ? -1 : r.path("annee").asInt();
                    if (fromYear != null && year != -1 && year < fromYear) {
                        continue; // skip items before the requested year
                    }
                    List<String> parts = new ArrayList<>();
                    String titre = textOrNull(r.path("titre"));
                    if (titre != null) parts.add(titre);
                    if (year != -1) parts.add(String.valueOf(year));
                    String lieu = textOrNull(r.path("lieu"));
                    if (lieu != null) parts.add(lieu);
                    if (!parts.isEmpty()) {
                        phrases.add(String.join(", ", parts));
                    }
                }
                if (!phrases.isEmpty()) {
                    String lead;
                    if (fromYear != null) {
                        lead = isEnglish ? ("Awards and achievements since " + fromYear + " include ")
                                : ("Depuis " + fromYear + ", parmi les distinctions, citons ");
                    } else {
                        lead = isEnglish ? "Recent awards and achievements include " : "Parmi les distinctions récentes, citons ";
                    }
                    return ensureEnglish(lead + joinWithAnd(phrases, isEnglish) + ".", isEnglish);
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
                    if (items.size() > 4) items = items.subList(0, 4);
                    String lead = isEnglish ? "Some client projects include " : "Parmi nos projets clients, citons ";
                    String sentence = lead + joinWithAnd(stripBullets(items), isEnglish) + ".";
                    return ensureEnglish(sentence, isEnglish);
                }
            }
            return isEnglish ? "I'm sorry, I don't have information on this." : "Je suis désolé, je ne trouve pas d'information à ce sujet.";
        }

        // 8) Expertises principales ("What is the expertise of Gear9?")
        if (containsAny(question, "expertise principale", "expertises principales", "compétence principale", "competence principale",
                "what is the expertise of gear9", "what is the expertise of", "what is your expertise",
                "expertise", "expertises", "core expertise", "main expertise", "primary expertise")) {
            JsonNode ex = data.path("expertise_principale");
            if (ex.isArray() && ex.size() > 0) {
                List<String> items = new ArrayList<>();
                for (JsonNode e : ex) {
                    String nom = textOrNullLang(e, "nom", isEnglish);
                    String description = textOrNullLang(e, "description", isEnglish);
                    if (nom != null && description != null) {
                        items.add("- **" + nom + "**: " + description);
                    } else if (nom != null) {
                        items.add("- **" + nom + "**");
                    }
                }
                if (!items.isEmpty()) {
                    List<String> phrases = new ArrayList<>();
                    for (String it : items) {
                        String cleaned = it.replaceFirst("^- \\*\\*(.*?)\\*\\*: ", "$1: ");
                        phrases.add(cleaned);
                    }
                    String lead = isEnglish ? "Our main expertises include " : "Nos expertises principales incluent ";
                    return ensureEnglish(lead + joinWithAnd(phrases, isEnglish) + ".", isEnglish);
                }
            }
            return isEnglish ? "I'm sorry, I don't have information on this." : "Je suis désolé, je ne trouve pas d'information à ce sujet.";
        }

        // 9) Détail d'une expertise (Salesforce, régie, digital, etc.)
        JsonNode expertise = data.path("expertise");
        if (expertise.isArray()) {
            // Select which expertise group
            String groupId = null;
            boolean mentionsSalesforce = containsAny(question, "salesforce", "sales cloud", "service cloud", "marketing cloud", "data cloud", "mulesoft", "tableau");
            boolean mentionsRegie = containsAny(question, "régie", "regie", "staff augmentation");
            boolean mentionsDigital = containsAny(question, "digital");
            if (mentionsSalesforce) groupId = "salesforce";
            else if (mentionsRegie) groupId = "regie";
            else if (mentionsDigital) groupId = "digital";

            // If no specific group mentioned but the user asked about expertise in general, summarize ALL groups
            if (groupId == null && containsAny(question, "expertise", "expertises")) {
                List<String> groupSummaries = new ArrayList<>();
                for (JsonNode g : expertise) {
                    String gid = textOrNull(g.path("id"));
                    String gname = textOrNull(g.path("nom"));
                    JsonNode details = g.path("details");
                    if (!details.isArray() || details.size() == 0) continue;
                    List<String> names = new ArrayList<>();
                    int added = 0;
                    for (JsonNode d : details) {
                        String nom = textOrNull(d.path("nom"));
                        if (nom != null) {
                            names.add(nom);
                            added++;
                            if (added >= 5) break;
                        }
                    }
                    if (!names.isEmpty()) {
                        String label;
                        if ("salesforce".equalsIgnoreCase(gid)) label = isEnglish ? "Salesforce" : "Salesforce";
                        else if ("regie".equalsIgnoreCase(gid)) label = isEnglish ? "staff augmentation" : "régie";
                        else if ("digital".equalsIgnoreCase(gid)) label = isEnglish ? "digital" : "digital";
                        else label = (gname != null ? gname : (isEnglish ? "expertise" : "expertise"));
                        String sentence = (isEnglish
                                ? (label + ": " + joinWithAnd(names, true))
                                : (label + " : " + joinWithAnd(names, false))
                        );
                        groupSummaries.add(sentence);
                    }
                }
                if (!groupSummaries.isEmpty()) {
                    String lead = isEnglish ? "Our expertises cover " : "Nos expertises couvrent ";
                    return lead + joinWithAnd(groupSummaries, isEnglish) + ".";
                }
            }

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
                        String nom = textOrNullLang(d, "nom", isEnglish);
                        String description = textOrNullLang(d, "description", isEnglish);
                        if (nom != null && description != null) {
                            items.add("- **" + nom + "**: " + description);
                        } else if (nom != null) {
                            items.add("- **" + nom + "**");
                        }
                    }
                    if (!items.isEmpty()) {
                        List<String> phrases = new ArrayList<>();
                        for (String it : items) {
                            String cleaned = it.replaceFirst("^- \\*\\*(.*?)\\*\\*: ", "$1: ");
                            phrases.add(cleaned);
                        }
                        String label = "cette expertise";
                        if ("salesforce".equals(groupId)) label = isEnglish ? "Salesforce" : "Salesforce";
                        else if ("regie".equals(groupId)) label = isEnglish ? "staff augmentation" : "régie";
                        else if ("digital".equals(groupId)) label = isEnglish ? "digital" : "digital";
                        String lead = isEnglish ? ("Details of our " + label + " expertise include ") : ("Parmi les détails de notre expertise " + label + ", on retrouve ");
                        return ensureEnglish(lead + joinWithAnd(phrases, isEnglish) + ".", isEnglish);
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

    private String tryAliasMatch(String normalizedQuestion, boolean isEnglish) {
        if (root == null) return null;
        JsonNode subjects = root.path("data").path("subjects");
        if (subjects.isMissingNode()) return null;

        // Default description fallback if matching generic subjects
        String defaultFr = textOrNull(root.path("data").path("apercu"));
        String defaultEn = null;
        if (defaultFr != null) {
            defaultEn = "Gear9 is a Moroccan digital transformation agency founded in 2019. We specialize in implementing digital culture, creating unique and engaging digital experiences, and using technology and data to drive business growth. We operate with an agile and innovative methodology, focusing on areas such as Digital Culture and Transformation, Product Thinking, Customer Experience and Automation, as well as Behavioral Analysis.";
        }

        // Iterate keys
        subjects.fieldNames().forEachRemaining(key -> {}); // touch iterator for compatibility
        for (java.util.Iterator<String> it = subjects.fieldNames(); it.hasNext();) {
            String key = it.next();
            JsonNode node = subjects.path(key);
            JsonNode aliases = node.path("aliases");
            if (aliases.isArray()) {
                for (JsonNode a : aliases) {
                    String alias = normalize(textOrNull(a));
                    if (alias != null && !alias.isEmpty() && normalizedQuestion.contains(alias)) {
                        // 0) If the subject provides a custom localized answer, prefer it.
                        // If the question looks English, serve EN even if current convo language is FR.
                        boolean looksEnglish = detectEnglish(normalizedQuestion);
                        String localized = textOrNull(node.path((isEnglish || looksEnglish) ? "answer_en" : "answer_fr"));
                        if (localized != null && !localized.isBlank()) {
                            return localized;
                        }
                        // 1) Map some keys to known answers using existing logic
                        switch (key) {
                            case "about": {
                                // If a specific topic is present (e.g., Salesforce, Digital, Régie),
                                // do not short-circuit to the generic description.
                                boolean hasSpecific = normalizedQuestion.contains("salesforce")
                                        || normalizedQuestion.contains("sales cloud")
                                        || normalizedQuestion.contains("service cloud")
                                        || normalizedQuestion.contains("marketing cloud")
                                        || normalizedQuestion.contains("data cloud")
                                        || normalizedQuestion.contains("mulesoft")
                                        || normalizedQuestion.contains("tableau")
                                        || normalizedQuestion.contains("digital")
                                        || normalizedQuestion.contains("product thinking")
                                        || normalizedQuestion.contains("customer experience")
                                        || normalizedQuestion.contains("automation")
                                        || normalizedQuestion.contains("régie")
                                        || normalizedQuestion.contains("regie")
                                        || normalizedQuestion.contains("staff augmentation");
                                if (!hasSpecific) {
                                    return isEnglish ? defaultEn : defaultFr;
                                }
                                // Let more specific alias handlers decide
                                break;
                            }
                            case "address":
                                return (isEnglish ? "Address of **Gear9**:\n" : "Adresse de **Gear9**:\n") + textOrNull(root.path("data").path("adresse"));
                            case "services":
                                return answer("services", isEnglish); // fall back to existing branch via keyword
                            case "clients":
                                return answer("clients", isEnglish);
                            case "awards":
                                return answer("awards", isEnglish);
                            case "leadership":
                                return answer("ceo", isEnglish);
                            case "expertise":
                                return answer("expertise", isEnglish);
                            case "salesforce":
                                return answer("salesforce expertise", isEnglish);
                            case "digital":
                                return answer("digital expertise", isEnglish);
                            default:
                                return isEnglish ? defaultEn : defaultFr;
                        }
                    }
                }
            }
        }
        return null;
    }

    private String normalize(String s) {
        if (s == null) return "";
        String lower = s.toLowerCase(Locale.ROOT).trim();
        String nfd = java.text.Normalizer.normalize(lower, java.text.Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
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

    // Word-boundary contains for single words (handles accents too)
    private boolean containsAnyWord(String haystack, String... words) {
        if (haystack == null) return false;
        for (String w : words) {
            if (w == null || w.isEmpty()) continue;
            // Build a regex that approximates word boundaries for ASCII and accented letters
            String regex = "(?<![a-záàâäãåçéèêëíìîïñóòôöõúùûüýÿ])" + Pattern.quote(w) + "(?![a-záàâäãåçéèêëíìîïñóòôöõúùûüýÿ])";
            if (Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(haystack).find()) {
                return true;
            }
        }
        return false;
    }

    private String joinWithAnd(List<String> items, boolean isEnglish) {
        if (items == null || items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        if (items.size() == 2) return items.get(0) + (isEnglish ? " and " : " et ") + items.get(1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            if (i == items.size() - 1) sb.append(isEnglish ? "and " : "et ");
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private String ensureEnglish(String text, boolean isEnglish) {
        // Return immediately to avoid network calls that slow down deterministic answers.
        return text;
    }

    private List<String> stripBullets(List<String> bulletLines) {
        List<String> result = new ArrayList<>();
        if (bulletLines == null) return result;
        for (String s : bulletLines) {
            String t = s.trim();
            if (t.startsWith("- ")) t = t.substring(2).trim();
            t = t.replace("**", "");
            result.add(t);
        }
        return result;
    }

    // removed bilingual helpers to restore original behavior

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

    // Extract a 4-digit year from the question (e.g., 2023). Returns null if none found or out of range.
    private Integer extractYear(String text) {
        if (text == null) return null;
        Matcher m = Pattern.compile("(?<!\\d)(19\\d{2}|20\\d{2})(?!\\d)").matcher(text);
        if (m.find()) {
            try {
                int y = Integer.parseInt(m.group(1));
                if (y >= 1900 && y <= 2100) return y;
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    // Subjects for autocomplete (company basics, services, expertise, projects, awards, leadership)
    public List<String> getSubjects() {
        Set<String> subjects = new LinkedHashSet<>();
        if (root == null) return new ArrayList<>(subjects);
        JsonNode data = root.path("data");
        if (data.isMissingNode()) return new ArrayList<>(subjects);

        // Company basics
        String nom = textOrNull(data.path("nom_entreprise"));
        if (nom != null) subjects.add(nom);
        if (textOrNull(data.path("adresse")) != null) subjects.add("Adresse");
        if (textOrNull(data.path("apropos")) != null) subjects.add("À propos");

        // Services
        JsonNode services = data.path("services");
        if (services.isArray()) {
            for (JsonNode s : services) {
                String n = textOrNull(s.path("nom"));
                String ne = textOrNull(s.path("nom_en"));
                String c = textOrNull(s.path("categorie"));
                if (n != null) subjects.add(n);
                if (ne != null) subjects.add(ne);
                if (c != null) subjects.add(c);
            }
        }

        // Expertise principale
        JsonNode exMain = data.path("expertise_principale");
        if (exMain.isArray()) {
            for (JsonNode e : exMain) {
                String n = textOrNull(e.path("nom"));
                String cat = textOrNull(e.path("categorie"));
                if (n != null) subjects.add(n);
                if (cat != null) subjects.add(cat);
            }
        }

        // Expertise groups and details
        JsonNode ex = data.path("expertise");
        if (ex.isArray()) {
            for (JsonNode g : ex) {
                String gname = textOrNull(g.path("nom"));
                if (gname != null) subjects.add(gname);
                JsonNode details = g.path("details");
                if (details.isArray()) {
                    for (JsonNode d : details) {
                        String dn = textOrNull(d.path("nom"));
                        if (dn != null) subjects.add(dn);
                    }
                }
            }
        }

        // Projects: sectors, names, types
        JsonNode projets = data.path("projets");
        if (projets.isArray()) {
            for (JsonNode p : projets) {
                String secteur = textOrNull(p.path("secteur"));
                String pn = textOrNull(p.path("nom"));
                String type = textOrNull(p.path("type"));
                String typeEn = textOrNull(p.path("type_en"));
                if (secteur != null) subjects.add(secteur);
                if (pn != null) subjects.add(pn);
                if (type != null) subjects.add(type);
                if (typeEn != null) subjects.add(typeEn);
            }
        }

        // Awards titles
        JsonNode rr = data.path("realisations_et_recompenses");
        if (rr.isArray()) {
            for (JsonNode r : rr) {
                String t = textOrNull(r.path("titre"));
                if (t != null) subjects.add(t);
            }
        }

        // Leadership roles
        JsonNode direction = data.path("direction");
        if (direction.isArray()) {
            for (JsonNode d : direction) {
                String role = textOrNull(d.path("role"));
                if (role != null) subjects.add(role);
            }
        }

        return new ArrayList<>(subjects);
    }
}




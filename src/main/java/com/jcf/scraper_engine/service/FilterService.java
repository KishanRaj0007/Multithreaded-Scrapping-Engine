package com.jcf.scraper_engine.service;

import com.jcf.scraper_engine.model.RawContact;
import com.jcf.scraper_engine.model.ScrapedContact;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class FilterService {

    private static final String[] ONCO_KEYWORDS = {"onco", "cancer", "tumor", "tumour", "carcinoma", "sarcoma", "lymphoma", "leukemia", "chemo", "radiation", "radiotherapy", "immunotherapy", "hematolog", "haematolog", "neoplasm", "malignant", "metasta", "biopsy", "palliative"};
    private static final String[] MEDICAL_KEYWORDS = {"hospital", "clinic", "medical", "medicine", "healthcare", "patient", "treatment", "surgery", "surgical", "cardiology", "neurology", "gastroenterology", "pathology", "radiology", "pediatric", "dermatology", "orthopedic", "urology", "nephrology", "pulmonology", "endocrinology", "psychiatry", "ophthalmology", "research", "clinical"};
    private static final String[] MEDICAL_TITLES = {"dr", "dr.", "doctor", "md", "mbbs", "phd", "prof", "professor", "do", "dds", "dmd", "rn", "surgeon", "physician", "oncologist"};

    // Python Missing Logic: Anti-Obfuscation
    private String normalizeHiddenEmails(String text) {
        text = text.replaceAll("(?i)\\s*\\[\\s*at\\s*\\]\\s*|\\s*\\(\\s*at\\s*\\)\\s*|\\s+at\\s+", "@");
        text = text.replaceAll("(?i)\\s*\\[\\s*dot\\s*\\]\\s*|\\s*\\(\\s*dot\\s*\\)\\s*", ".");
        return text.replaceAll("(?i)([a-z0-9])\\s+dot\\s+", "$1.");
    }

    public List<String> extractEmails(String text) {
        List<String> uniqueEmails = new ArrayList<>();
        String normalized = normalizeHiddenEmails(text);
        Matcher m = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}").matcher(normalized);
        while (m.find()) {
            String email = m.group().toLowerCase();
            if (email.length() <= 100 && !email.contains("<") && !email.contains("{") && !uniqueEmails.contains(email)) {
                uniqueEmails.add(email);
            }
        }
        return uniqueEmails;
    }

    // Python Missing Logic: Phone Extraction
    public String extractPhone(String text) {
        Matcher m = Pattern.compile("(?:\\+\\d{1,3})?\\s*(?:\\(?\\d{3}\\)?)[\\s.-]?\\d{3}[\\s.-]?\\d{4}").matcher(text);
        if (m.find()) {
            String cleaned = m.group().replaceAll("[^\\d\\+\\-\\(\\)\\s\\.]", "").trim();
            if (cleaned.replaceAll("\\D", "").length() >= 10) return cleaned;
        }
        return "";
    }

    // Python Missing Logic: Name Generation Fallback
    public String extractNameFromEmail(String email) {
        if (!email.contains("@")) return "";
        String local = email.split("@")[0];
        local = local.replaceAll("(?i)^(dr\\.?|doc\\.?)", "").replaceAll("[0-9_]", "").replaceAll("[.\\-]", " ");
        local = local.replaceAll("([a-z])([A-Z])", "$1 $2");
        String[] words = local.split("\\s+");
        return Arrays.stream(words).map(w -> w.isEmpty() ? "" : w.substring(0, 1).toUpperCase() + w.substring(1).toLowerCase()).collect(Collectors.joining(" ")).trim();
    }

    // Python Missing Logic: DOM Walking
    public String findNearbyName(Document doc, String email) {
        if (doc == null) return "";
        Elements elements = doc.getElementsContainingText(email);
        if (elements.isEmpty()) return "";
        
        Element container = elements.last(); 
        for (int i = 0; i < 5; i++) {
            if (container == null || container.tagName().equals("body")) break;
            Elements tags = container.select("h1, h2, h3, h4, h5, strong, b, span, p");
            for (Element t : tags) {
                String potName = t.text().trim();
                if (isValidPersonName(potName)) return potName;
            }
            container = container.parent();
        }
        return "";
    }

    private boolean isValidPersonName(String name) {
        String clean = name.replaceAll("[^\\w\\s]", "");
        String[] words = clean.split("\\s+");
        if (words.length < 2 || words.length > 5) return false;
        if (clean.matches(".*\\d.*")) return false;
        String[] garbage = {"hospital", "office", "center", "department", "university", "college", "clinic", "program", "institute", "director", "admin", "manager", "click", "skip", "menu"};
        for (String g : garbage) { if (clean.toLowerCase().contains(g)) return false; }
        return true;
    }

    public List<String> extractNames(String text) {
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("(?i)\\b(?:dr\\.?|prof\\.?|professor|doctor)\\s+([A-Z][a-z]+(?:\\s+[A-Z][a-z]+){0,3})").matcher(text);
        while (m.find()) {
            String name = m.group(1).trim();
            if (isValidPersonName(name) && !names.contains(name)) names.add(name);
        }
        return names;
    }

    public String getContext(String text, String email) {
        int pos = text.toLowerCase().indexOf(email.toLowerCase());
        if (pos == -1) return "";
        int start = Math.max(0, pos - 500);
        int end = Math.min(text.length(), pos + email.length() + 500);
        return text.substring(start, end);
    }

    private boolean nameMatchesEmail(String email, String name) {
        if (!email.contains("@") || name.isEmpty()) return false;
        String local = email.split("@")[0].toLowerCase().replaceAll("^(dr\\.?|doc\\.?|prof\\.?)", "").replaceAll("[0-9]", "");
        String[] parts = name.toLowerCase().split("\\s+");
        int matches = 0;
        for (String p : parts) { if (local.contains(p)) matches++; }
        return matches >= 1 && parts[0].length() >= 3;
    }

    // Python Missing Logic: The Two-Pass Scoring Engine
    public List<ScrapedContact> scoreAllContacts(List<RawContact> rawContacts, Set<String> allSiteNames) {
        List<ScrapedContact> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (RawContact contact : rawContacts) {
            String email = contact.email.toLowerCase();
            if (!seen.add(email)) continue;

            int score = 20;
            String local = email.split("@")[0];
            String domain = email.split("@")[1];

            if (domain.endsWith(".edu") || domain.endsWith(".edu.in") || domain.endsWith(".ac.uk") || domain.endsWith(".gov") || domain.endsWith(".org")) score += 15;
            
            if (!contact.nearbyName.isEmpty() && !contact.nearbyName.equalsIgnoreCase("unknown")) {
                score += 20;
                if (Arrays.stream(MEDICAL_TITLES).anyMatch(contact.nearbyName.toLowerCase()::contains)) score += 20;
                if (Arrays.stream(ONCO_KEYWORDS).anyMatch(contact.nearbyName.toLowerCase()::contains)) score += 15;
            }

            if (Arrays.stream(ONCO_KEYWORDS).anyMatch(contact.context.toLowerCase()::contains)) score += 15;
            else if (Arrays.stream(MEDICAL_KEYWORDS).anyMatch(contact.context.toLowerCase()::contains)) score += 5;

            for (String siteName : allSiteNames) {
                if (nameMatchesEmail(email, siteName)) {
                    score += 10;
                    if (Arrays.stream(MEDICAL_TITLES).anyMatch(siteName.toLowerCase()::contains)) score += 5;
                    break;
                }
            }

            if (local.matches("(?i)^dr\\.?.*")) score += 10;

            String[] generic = {"info", "hello", "contact", "enquiry", "mail", "web", "general", "admin", "support", "noreply", "hr", "careers", "jobs", "billing", "feedback"};
            for (String g : generic) { if (local.equals(g)) score -= 15; }
            String[] sys = {"system", "webcontrols", "template", "framework", "repeater", "verification", "bounce", "daemon", "postmaster"};
            for (String s : sys) { if (email.contains(s)) score -= 20; }

            String finalName = !contact.nearbyName.isEmpty() ? contact.nearbyName : contact.fallbackName;
            results.add(new ScrapedContact(email, finalName, contact.phone, Math.max(score, 0), contact.url));
        }

        results.sort((a, b) -> Integer.compare(b.score, a.score));
        return results;
    }
}
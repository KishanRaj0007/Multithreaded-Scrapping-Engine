package com.jcf.scraper_engine.service;

import com.jcf.scraper_engine.model.RawContact;
import com.jcf.scraper_engine.model.ScrapedContact;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class CrawlService {

    private final ExtractionService extractionService;
    private final FilterService filterService;
    public static final ConcurrentHashMap<String, EngineState> ACTIVE_ENGINES = new ConcurrentHashMap<>();

    private static final String[] BLACKLIST = {"/careers", "/jobs", "/human-resources", "/billing", "/donate", "/shop", "/login", ".jpg", ".png", ".mp4", ".zip", ".css"};
    private final CsvWriterService csvWriterService;

    public CrawlService(ExtractionService extractionService, FilterService filterService, CsvWriterService csvWriterService) {
        this.extractionService = extractionService; 
        this.filterService = filterService;
        this.csvWriterService = csvWriterService;
    }

    public static class EngineState {
        public String domain;
        public AtomicInteger pagesCrawled = new AtomicInteger(0);
        public AtomicInteger emailsFound = new AtomicInteger(0); // Raw RAM Emails
        public AtomicInteger scoredEmails = new AtomicInteger(0); // Saved to CSV Emails
        
        // Bulletproof Task Tracking
        public AtomicInteger submittedTasks = new AtomicInteger(0);
        public AtomicInteger completedTasks = new AtomicInteger(0);
        
        public int maxPages, currentDepth = 0;
        public String status = "Crawling";
        public Set<String> visited = ConcurrentHashMap.newKeySet();
        
        public ConcurrentLinkedQueue<RawContact> rawContacts = new ConcurrentLinkedQueue<>();
        public Set<String> allSiteNames = ConcurrentHashMap.newKeySet();

        public EngineState(String domain, int maxPages) { this.domain = domain; this.maxPages = maxPages; }
    }

    public void startOrchestratedSweep(Set<String> rootUrls, int maxDepth, int maxPagesPerUrl) {
        ACTIVE_ENGINES.clear();
        ExecutorService orchestratorPool = Executors.newFixedThreadPool(6); 

        for (String rootUrl : rootUrls) {
            orchestratorPool.submit(() -> runIsolatedEngine(rootUrl, maxDepth, maxPagesPerUrl));
        }

        orchestratorPool.shutdown();
        try { orchestratorPool.awaitTermination(24, TimeUnit.HOURS); } catch (InterruptedException e) {}
    }

    private void runIsolatedEngine(String rootUrl, int maxDepth, int maxPages) {
        EngineState state = new EngineState(rootUrl, maxPages);
        ACTIVE_ENGINES.put(rootUrl, state); 
        String hostBoundary = extractRootDomain(rootUrl); 

        ExecutorService virtualPool = Executors.newVirtualThreadPerTaskExecutor();
        
        state.submittedTasks.incrementAndGet();
        virtualPool.submit(() -> crawlUrl(rootUrl, 0, maxDepth, virtualPool, state, hostBoundary));

        // Anti-Jam Monitor & Watchdog Timer
        int lastCompletedCount = 0;
        long lastProgressTime = System.currentTimeMillis();

        while (true) {
            try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
            
            int currentCompleted = state.completedTasks.get();
            
            // Break loop normally if we hit max pages, or if submitted == completed
            if (state.pagesCrawled.get() >= state.maxPages || state.submittedTasks.get() == currentCompleted) {
                break;
            }
            
            // THE WATCHDOG: If no new pages finish for 45 seconds, the server has jammed us.
            if (currentCompleted != lastCompletedCount) {
                lastCompletedCount = currentCompleted;
                lastProgressTime = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastProgressTime > 45000) {
                // Break the infinite loop and force Pass 2 to save our data!
                break;
            }
        }
        
        virtualPool.shutdownNow();
        state.status = "Scoring";
        try {
            List<ScrapedContact> finalResults = filterService.scoreAllContacts(List.copyOf(state.rawContacts), state.allSiteNames);
            
            List<ScrapedContact> validToSave = new java.util.ArrayList<>();
            for (ScrapedContact contact : finalResults) {
                if (contact.score >= 20) validToSave.add(contact);
            }
            
            // Instantly write to disk, and update dashboard with ONLY brand-new unique emails
            int newlySaved = csvWriterService.writeBatch(validToSave);
            state.scoredEmails.addAndGet(newlySaved);
            
            state.status = "DONE";
        } catch (Exception e) {
            state.status = "ERROR";
        }
    }

    private void crawlUrl(String url, int currentDepth, int maxDepth, ExecutorService executor, EngineState state, String rootDomain) {
        try {
            url = normalizeUrl(url); 
            if (state.pagesCrawled.get() >= state.maxPages || currentDepth > maxDepth || state.visited.contains(url) || isBlacklisted(url)) return;

            state.pagesCrawled.incrementAndGet();
            state.visited.add(url);
            state.currentDepth = Math.max(state.currentDepth, currentDepth);

            String rawPayload;
            Document doc = null;
            String pageText = "";

            if (url.toLowerCase().endsWith(".pdf")) {
                rawPayload = extractionService.extractPdfLane(url);
                pageText = rawPayload != null ? rawPayload : "";
            } else {
                rawPayload = extractionService.universalVacuum(url);
                if (rawPayload != null) {
                    doc = Jsoup.parse(rawPayload, url);
                    pageText = doc.text();
                }
            }

            if (rawPayload == null || rawPayload.isEmpty()) return;

            // PASS 1: Harvest all data into RAM
            List<String> emails = filterService.extractEmails(pageText);
            state.allSiteNames.addAll(filterService.extractNames(pageText));
            String phone = filterService.extractPhone(pageText);

            for (String email : emails) {
                String nearbyName = filterService.findNearbyName(doc, email);
                String fallbackName = filterService.extractNameFromEmail(email);
                String context = filterService.getContext(pageText, email);
                state.rawContacts.add(new RawContact(email, nearbyName, fallbackName, context, url, phone));
                state.emailsFound.incrementAndGet(); // Raw RAM Emails
            }

            if (currentDepth < maxDepth && doc != null && state.pagesCrawled.get() < state.maxPages) {
                for (Element link : doc.select("a[href]")) {
                    String childUrl = normalizeUrl(link.attr("abs:href").trim());
                    if (!childUrl.isEmpty() && childUrl.startsWith("http") && extractRootDomain(childUrl).contains(rootDomain)) {
                        
                        state.submittedTasks.incrementAndGet(); // Prevent early exit
                        executor.submit(() -> crawlUrl(childUrl, currentDepth + 1, maxDepth, executor, state, rootDomain));
                    }
                }
            }
        } finally {
            state.completedTasks.incrementAndGet(); // Mark task absolutely finished
        }
    }

    private String normalizeUrl(String url) { int hashIdx = url.indexOf('#'); return hashIdx != -1 ? url.substring(0, hashIdx) : url; }
    private boolean isBlacklisted(String url) { for (String p : BLACKLIST) { if (url.toLowerCase().contains(p)) return true; } return false; }
    private String extractRootDomain(String url) {
        try {
            String h = new URI(url).getHost(); 
            if (h == null) return "";
            h = h.toLowerCase(); 
            if (h.startsWith("www.")) h = h.substring(4);
            // We NO LONGER split by the last two words. 
            // We return the exact full host (e.g., curtin.edu.au) so engines never overlap.
            return h;
        } catch (Exception e) { return ""; }
    }
}
package com.jcf.scraper_engine.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.WaitUntilState;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class ExtractionService {

    // Safely limits headless browsers so we don't blow up CPU, but prevents deadlocks.
    private static final Semaphore PLAYWRIGHT_SEMAPHORE = new Semaphore(3);

    public String extractHtmlFastLane(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .ignoreContentType(true).timeout(15000).get();
            return doc.html();
        } catch (Exception e) { return null; }
    }

    public String extractJsHeavyLane(String url) {
        try {
            // Wait up to 15 seconds for a free browser slot, otherwise skip to prevent jamming
            if (!PLAYWRIGHT_SEMAPHORE.tryAcquire(15, TimeUnit.SECONDS)) return null;
            
            try (Playwright playwright = Playwright.create()) {
                Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)
                    .setArgs(List.of("--disable-blink-features=AutomationControlled", "--disable-infobars")));
                Page page = browser.newPage();
                
                // STRICT TIMEOUT: Prevents forever-hanging on bad hospital servers
                page.setDefaultNavigationTimeout(15000); 
                page.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined});");

                page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForTimeout(2000); 
                page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
                
                String html = page.content();
                browser.close();
                return html + ""; 
            } finally {
                PLAYWRIGHT_SEMAPHORE.release();
            }
        } catch (Exception e) { return null; }
    }

    public String extractPdfLane(String url) {
        try {
            // FIX: Replaced infinite openStream() with strict socket timeouts!
            java.net.URLConnection conn = new java.net.URI(url).toURL().openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000); // Kills the download if it takes more than 15 seconds
            
            try (java.io.InputStream in = conn.getInputStream()) {
                byte[] pdfBytes = in.readAllBytes();
                try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
                    return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                }
            }
        } catch (Exception e) { 
            return null; 
        }
    }

    public String universalVacuum(String url) {
        String payload = extractHtmlFastLane(url);
        if (payload == null || payload.length() < 2000 || 
            payload.toLowerCase().contains("enable javascript") || 
            payload.toLowerCase().contains("cloudflare") ||
            payload.toLowerCase().contains("challenge-platform")) {
            
            payload = extractJsHeavyLane(url);
        }
        return payload;
    }
}
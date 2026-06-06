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
            // UPDATED: Added .ignoreHttpErrors(true) so Jsoup doesn't instantly crash on a 403, allowing us to read the headers!
            org.jsoup.Connection.Response res = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .ignoreContentType(true)
                    .ignoreHttpErrors(true) 
                    .timeout(15000)
                    .execute();
            
            // THE 99.9% ACCURACY CHECK:
            if (res.statusCode() == 403 || res.statusCode() == 503) {
                String server = res.header("Server");
                if ((server != null && server.toLowerCase().contains("cloudflare")) || res.hasHeader("CF-RAY")) {
                    flagCloudflareBlock(url);
                    return null; // Immediately abort
                }
            }
            return res.parse().html();
        } catch (Exception e) { return null; }
    }

    public String extractJsHeavyLane(String url) {
        try {
            if (!PLAYWRIGHT_SEMAPHORE.tryAcquire(15, TimeUnit.SECONDS)) return null;
            
            try (Playwright playwright = Playwright.create()) {
                Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true)
                    .setArgs(List.of("--disable-blink-features=AutomationControlled", "--disable-infobars")));
                Page page = browser.newPage();
                
                page.setDefaultNavigationTimeout(15000); 
                page.addInitScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined});");

                // UPDATED: Capture the Response object to check network headers
                com.microsoft.playwright.Response response = page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                
                // THE 99.9% ACCURACY CHECK FOR PLAYWRIGHT:
                if (response != null && (response.status() == 403 || response.status() == 503)) {
                    String server = response.headers().get("server");
                    if ((server != null && server.toLowerCase().contains("cloudflare")) || response.headers().containsKey("cf-ray")) {
                        flagCloudflareBlock(url);
                        browser.close();
                        return null; // Immediately abort
                    }
                }

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
            java.net.URLConnection conn = new java.net.URI(url).toURL().openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000); 
            
            try (java.io.InputStream in = conn.getInputStream()) {
                byte[] pdfBytes = in.readAllBytes();
                try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
                    return new org.apache.pdfbox.text.PDFTextStripper().getText(document);
                }
            }
        } catch (Exception e) { return null; }
    }

    public String universalVacuum(String url) {
        String payload = extractHtmlFastLane(url);
        
        // PERFORMANCE BOOST: If Jsoup proved it's Cloudflare, do NOT launch the Playwright browser. Abort instantly!
        if (isAlreadyFlaggedAsCloudflare(url)) {
            return null;
        }

        if (payload == null || payload.length() < 2000 || 
            payload.toLowerCase().contains("enable javascript") || 
            payload.toLowerCase().contains("cloudflare") ||
            payload.toLowerCase().contains("challenge-platform")) {
            
            payload = extractJsHeavyLane(url);
        }
        return payload;
    }

    private void flagCloudflareBlock(String url) {
        try {
            // 1. Save the EXACT full URL for the text file
            com.jcf.scraper_engine.runner.PipelineRunner.CLOUDFLARE_BLOCKED_URLS.add(url);
            
            // 2. Extract the domain to block the rest of the site and save CPU
            String h = new java.net.URI(url).getHost();
            if (h != null) {
                h = h.toLowerCase();
                if (h.startsWith("www.")) h = h.substring(4);
                com.jcf.scraper_engine.runner.PipelineRunner.CLOUDFLARE_BLOCKED_DOMAINS.add(h);
            }
        } catch (Exception e) {}
    }

    private boolean isAlreadyFlaggedAsCloudflare(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            if (h != null) {
                h = h.toLowerCase();
                if (h.startsWith("www.")) h = h.substring(4);
                return com.jcf.scraper_engine.runner.PipelineRunner.CLOUDFLARE_BLOCKED_DOMAINS.contains(h);
            }
        } catch (Exception e) {}
        return false;
    }
}
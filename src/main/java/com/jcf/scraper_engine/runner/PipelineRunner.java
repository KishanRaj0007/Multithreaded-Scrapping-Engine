package com.jcf.scraper_engine.runner;

import com.jcf.scraper_engine.service.CrawlService;
import com.jcf.scraper_engine.service.CsvWriterService;
import com.jcf.scraper_engine.service.IntakeService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class PipelineRunner implements CommandLineRunner {

    private final IntakeService intakeService;
    private final CrawlService crawlService;
    private final CsvWriterService csvWriterService;
    public static final ConcurrentLinkedQueue<String> WORKLOAD_QUEUE = new ConcurrentLinkedQueue<>();

    public PipelineRunner(IntakeService intakeService, CrawlService crawlService, CsvWriterService csvWriterService) {
        this.intakeService = intakeService; this.crawlService = crawlService; this.csvWriterService = csvWriterService;
    }

    @Override
    public void run(String... args) throws Exception {
        Scanner scanner = new Scanner(System.in);
        
        System.out.println("\nPlease ensure your URLs are saved in manual_targets.txt");
        System.out.println("Rule: One URL per line, without any quotes.");
        System.out.print("\nIs the text file ready? (y/n): ");
        
        String choice = scanner.nextLine().trim().toLowerCase();
        
        if (!choice.equals("y") && !choice.equals("yes")) {
            System.out.println("Operation cancelled. Please prepare manual_targets.txt and run again.");
            System.exit(0);
        }

        try {
            WORKLOAD_QUEUE.addAll(intakeService.loadManualTargets("manual_targets.txt"));
            if (WORKLOAD_QUEUE.isEmpty()) {
                System.out.println("[-] No URLs found in manual_targets.txt. Exiting.");
                System.exit(0);
            }
        } catch (Exception e) { return; }

        System.out.print("\nEnter Crawl Depth Limit (Default: 5): ");
        int maxDepth = 5; try { maxDepth = Integer.parseInt(scanner.nextLine().trim()); } catch (Exception e) {}

        System.out.print("\nEnter Max Pages Per URL (Default: 900): ");
        int maxPages = 900; try { maxPages = Integer.parseInt(scanner.nextLine().trim()); } catch (Exception e) {}

        startSilentDashboardThread();

        crawlService.startOrchestratedSweep(new HashSet<>(WORKLOAD_QUEUE), maxDepth, maxPages);

        // Corrected: Using state.domain instead of state.targetUrl
        System.out.println("\n=================================================================================================================");
        System.out.println("[FINAL CRAWL DASHBOARD] - Sweep Complete");
        System.out.println("=================================================================================================================");
        for (CrawlService.EngineState state : CrawlService.ACTIVE_ENGINES.values()) {
            String printUrl = state.domain.length() > 25 ? state.domain.substring(0, 22) + "..." : state.domain;
            System.out.printf("[Engine] %-25s | Pages: %4d / %-4d | Depth: %-2d | Status: %-10s | Raw: %-4d | Saved: %d\n", 
                printUrl, state.pagesCrawled.get(), state.maxPages, state.currentDepth, state.status, state.emailsFound.get(), state.scoredEmails.get());
        }
        System.out.println("=================================================================================================================");
        
        // NEW BLOCK: Isolate Cloudflare/Blocked URLs and append them to a text file
        try (java.io.PrintWriter blockedWriter = new java.io.PrintWriter(new java.io.FileWriter("cloudflare_blocked.txt", true))) {
            int blockedCount = 0;
            for (CrawlService.EngineState state : CrawlService.ACTIVE_ENGINES.values()) {
                // If it got trapped at the front door (Pages <= 1) and found nothing, it's a firewall block
                if (state.pagesCrawled.get() <= 1 && state.emailsFound.get() == 0) {
                    blockedWriter.println(state.domain);
                    blockedCount++;
                }
            }
            if (blockedCount > 0) {
                System.out.println("[!] Segregated " + blockedCount + " blocked URLs into 'cloudflare_blocked.txt'");
            }
        } catch (Exception e) {
            System.out.println("[!] Warning: Could not write to cloudflare_blocked.txt");
        }
        
        System.out.println("\n[+] PIPELINE COMPLETE. Data securely saved to output_doctors.csv.");
        System.exit(0);
    }

    private void startSilentDashboardThread() {
        Thread.ofVirtual().start(() -> {
            while (true) {
                try {
                    Thread.sleep(3000);
                    long activeCount = CrawlService.ACTIVE_ENGINES.values().stream()
                        .filter(s -> !s.status.equals("DONE") && !s.status.equals("ERROR")).count();
                        
                    System.out.println("\n=================================================================================================================");
                    System.out.println("[LIVE RELATIONAL CRAWL DASHBOARD] - Actively Crawling: " + activeCount + " (Total Historical Engines: " + CrawlService.ACTIVE_ENGINES.size() + ")");
                    System.out.println("=================================================================================================================");
                    for (CrawlService.EngineState state : CrawlService.ACTIVE_ENGINES.values()) {
                        
                        // Replace the printf block with this:
                        String printUrl = state.domain.length() > 25 ? state.domain.substring(0, 22) + "..." : state.domain;
                        System.out.printf("[Engine] %-25s | Pages: %4d / %-4d | Depth: %-2d | Status: %-10s | Raw: %-4d | Saved: %d\n", 
                            printUrl, state.pagesCrawled.get(), state.maxPages, state.currentDepth, state.status, state.emailsFound.get(), state.scoredEmails.get());
                    }
                    System.out.println("=================================================================================================================");
                } catch (InterruptedException e) { break; }
            }
        });
    }
}
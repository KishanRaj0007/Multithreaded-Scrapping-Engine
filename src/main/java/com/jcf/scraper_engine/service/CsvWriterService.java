package com.jcf.scraper_engine.service;

import com.jcf.scraper_engine.model.ScrapedContact;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CsvWriterService {

    private final ConcurrentHashMap<String, Boolean> existingEmails = new ConcurrentHashMap<>();
    private static final String OUTPUT_FILE = "output_doctors.csv";
    private boolean initialized = false;

    private synchronized void initializeState() {
        if (initialized) return;
        File file = new File(OUTPUT_FILE);
        if (file.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) {
                    String[] cols = line.split(",");
                    if (cols.length > 0) existingEmails.put(cols[0].toLowerCase(), true);
                }
            } catch (IOException e) {}
        } else {
            try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
                pw.println("Email,Name,Phone,Score,Source_Url");
            } catch (IOException e) {}
        }
        initialized = true;
    }

    // Direct, synchronized write. Returns the number of NEW emails actually saved.
    public synchronized int writeBatch(List<ScrapedContact> contacts) {
        if (!initialized) initializeState();
        int newlySavedCount = 0;
        
        try (PrintWriter pw = new PrintWriter(new FileWriter(OUTPUT_FILE, true))) {
            for (ScrapedContact record : contacts) {
                // If it's a brand new email not in the CSV...
                if (existingEmails.putIfAbsent(record.email.toLowerCase(), true) == null) {
                    
                    // FIX: Strip ALL hidden 'Enter/Return' newlines and commas that break CSV formatting!
                    String cleanEmail = record.email != null ? record.email.replaceAll("[\\n\\r,]", "") : "";
                    String cleanName = record.name != null ? record.name.replaceAll("[\\n\\r,]", " ").trim() : "";
                    String cleanPhone = record.phone != null ? record.phone.replaceAll("[\\n\\r,]", " ").trim() : "";
                    String cleanUrl = record.url != null ? record.url.replaceAll("[\\n\\r,]", "") : "";
                    
                    pw.printf("%s,%s,%s,%d,%s\n", cleanEmail, cleanName, cleanPhone, record.score, cleanUrl);
                    newlySavedCount++;
                }
            }
            pw.flush(); // Force OS to save to disk instantly
        } catch (IOException e) {
            System.out.println("\n[!] FATAL DISK ERROR: Cannot write to CSV. Error: " + e.getMessage());
        }
        return newlySavedCount;
    }
}
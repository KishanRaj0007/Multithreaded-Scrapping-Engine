package com.jcf.scraper_engine.service;

import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Service
public class IntakeService {
    public List<String> loadManualTargets(String file) {
        List<String> urls = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(Paths.get(file));
            for(String line : lines) {
                String clean = line.trim();
                // Ignores empty lines or lines starting with #
                if(!clean.isEmpty() && !clean.startsWith("#")) {
                    urls.add(clean);
                }
            }
        } catch (Exception e) {
            System.out.println("[-] Failed to read " + file);
        }
        return urls;
    }
}
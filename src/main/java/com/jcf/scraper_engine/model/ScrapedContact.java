package com.jcf.scraper_engine.model;

public class ScrapedContact {
    public String email, name, phone, url;
    public int score;

    public ScrapedContact(String email, String name, String phone, int score, String url) {
        this.email = email; this.name = name; this.phone = phone; this.score = score; this.url = url;
    }
}

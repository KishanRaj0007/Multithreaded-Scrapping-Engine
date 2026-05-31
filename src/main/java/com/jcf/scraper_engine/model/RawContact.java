package com.jcf.scraper_engine.model;

public class RawContact {
    public String email, nearbyName, fallbackName, context, url, phone;

    public RawContact(String email, String nearbyName, String fallbackName, String context, String url, String phone) {
        this.email = email; this.nearbyName = nearbyName; this.fallbackName = fallbackName;
        this.context = context; this.url = url; this.phone = phone;
    }
}

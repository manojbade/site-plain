package com.siteplain.web.controller;

import com.siteplain.service.SitemapService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SitemapController {

    private final SitemapService sitemapService;

    public SitemapController(SitemapService sitemapService) {
        this.sitemapService = sitemapService;
    }

    @GetMapping("/sitemap.xml")
    public ResponseEntity<String> showSitemap() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/xml;charset=UTF-8")
                .body(sitemapService.currentSitemap());
    }
}

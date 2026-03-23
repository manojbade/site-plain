package com.siteplain.service;

import com.siteplain.config.SiteProperties;
import com.siteplain.data.repository.SeoPageRepository;
import com.siteplain.data.repository.StatePageRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SitemapService {

    private final SeoPageRepository seoPageRepository;
    private final StatePageRepository statePageRepository;
    private final SiteProperties siteProperties;
    private volatile String sitemapXml = xmlHeader() + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"></urlset>";

    public SitemapService(SeoPageRepository seoPageRepository,
                          StatePageRepository statePageRepository,
                          SiteProperties siteProperties) {
        this.seoPageRepository = seoPageRepository;
        this.statePageRepository = statePageRepository;
        this.siteProperties = siteProperties;
    }

    public synchronized void rebuild() {
        StringBuilder xml = new StringBuilder();
        xml.append(xmlHeader());
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
        appendUrl(xml, absoluteUrl("/"));
        appendUrl(xml, absoluteUrl("/about"));
        for (String epaId : seoPageRepository.findAllSiteIds()) {
            appendUrl(xml, absoluteUrl("/site/" + epaId));
        }
        for (String stateCode : statePageRepository.findAllStateCodes()) {
            appendUrl(xml, absoluteUrl("/state/" + stateCode));
        }
        xml.append("</urlset>");
        sitemapXml = xml.toString();
    }

    public String currentSitemap() {
        return sitemapXml;
    }

    private void appendUrl(StringBuilder xml, String location) {
        xml.append("<url><loc>").append(xmlEscape(location)).append("</loc></url>");
    }

    private String absoluteUrl(String path) {
        String baseUrl = StringUtils.hasText(siteProperties.getBaseUrl())
                ? siteProperties.getBaseUrl().trim()
                : "https://site-plain.com";
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if ("/".equals(path)) {
            return baseUrl + "/";
        }
        return baseUrl + path;
    }

    private static String xmlHeader() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";
    }

    private String xmlEscape(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}

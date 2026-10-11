package com.siteplain.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Request-level abuse controls. Two layers: global concurrency caps on the expensive work (they do
 * not depend on who is asking), and per-client token buckets on the public endpoints, set well above
 * what a person does by hand. Numbers here are the defaults; each can be overridden with a
 * siteplain.limits.* property (or SITEPLAIN_LIMITS_* environment variable) without a code change.
 */
@ConfigurationProperties(prefix = "siteplain.limits")
public class LimitsProperties {

    /** Per-client rate limits on or off. The concurrency caps always apply. */
    private boolean rateLimitsEnabled = true;
    /**
     * Request header carrying the client IP, set by the edge proxy. Railway documents X-Real-IP as
     * the client's remote IP and overwrites any copy the client sends; see ClientAddress.
     */
    private String clientIpHeader = "X-Real-IP";
    /** Most clients tracked per limit; past it the least recently seen client's bucket is dropped. */
    private int maxTrackedClients = 20_000;

    /** POST /lookup: geocoding plus one proximity query. */
    private Bucket lookup = new Bucket(30, Duration.ofSeconds(3));
    /** GET /results: proximity query and page. */
    private Bucket results = new Bucket(60, Duration.ofSeconds(1));
    /** GET /results/pdf: proximity query and PDF rendering. */
    private Bucket pdf = new Bucket(10, Duration.ofSeconds(6));
    /** POST /feedback: one database row each. */
    private Bucket feedback = new Bucket(5, Duration.ofMinutes(12));
    /** GET /api/site-search/suggestions: typing in the home page search box. */
    private Bucket suggestions = new Bucket(60, Duration.ofMillis(500));
    /** GET /search/sites. */
    private Bucket search = new Bucket(30, Duration.ofSeconds(2));

    private Cap pdfRendering = new Cap(2, Duration.ofSeconds(2));
    private Cap geocoding = new Cap(6, Duration.ofSeconds(1));
    private Cap proximityQuery = new Cap(6, Duration.ofSeconds(2));

    /** Least time between two Nominatim requests, from the whole app (OSMF policy: at most 1 per second). */
    private Duration nominatimInterval = Duration.ofSeconds(1);
    /** Longest a lookup waits for its Nominatim turn before answering busy. */
    private Duration nominatimMaxWait = Duration.ofSeconds(3);

    /** A token bucket: up to capacity requests at once, then one more per refill interval. */
    public static class Bucket {
        private int capacity;
        private Duration refill;

        public Bucket() {
        }

        public Bucket(int capacity, Duration refill) {
            this.capacity = capacity;
            this.refill = refill;
        }

        public int getCapacity() {
            return capacity;
        }

        public void setCapacity(int capacity) {
            this.capacity = capacity;
        }

        public Duration getRefill() {
            return refill;
        }

        public void setRefill(Duration refill) {
            this.refill = refill;
        }
    }

    /** A concurrency cap: at most max at once; a request waits up to maxWait for a slot, then gets 503. */
    public static class Cap {
        private int max;
        private Duration maxWait;

        public Cap() {
        }

        public Cap(int max, Duration maxWait) {
            this.max = max;
            this.maxWait = maxWait;
        }

        public int getMax() {
            return max;
        }

        public void setMax(int max) {
            this.max = max;
        }

        public Duration getMaxWait() {
            return maxWait;
        }

        public void setMaxWait(Duration maxWait) {
            this.maxWait = maxWait;
        }
    }

    public boolean isRateLimitsEnabled() {
        return rateLimitsEnabled;
    }

    public void setRateLimitsEnabled(boolean rateLimitsEnabled) {
        this.rateLimitsEnabled = rateLimitsEnabled;
    }

    public String getClientIpHeader() {
        return clientIpHeader;
    }

    public void setClientIpHeader(String clientIpHeader) {
        this.clientIpHeader = clientIpHeader;
    }

    public int getMaxTrackedClients() {
        return maxTrackedClients;
    }

    public void setMaxTrackedClients(int maxTrackedClients) {
        this.maxTrackedClients = maxTrackedClients;
    }

    public Bucket getLookup() {
        return lookup;
    }

    public void setLookup(Bucket lookup) {
        this.lookup = lookup;
    }

    public Bucket getResults() {
        return results;
    }

    public void setResults(Bucket results) {
        this.results = results;
    }

    public Bucket getPdf() {
        return pdf;
    }

    public void setPdf(Bucket pdf) {
        this.pdf = pdf;
    }

    public Bucket getFeedback() {
        return feedback;
    }

    public void setFeedback(Bucket feedback) {
        this.feedback = feedback;
    }

    public Bucket getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(Bucket suggestions) {
        this.suggestions = suggestions;
    }

    public Bucket getSearch() {
        return search;
    }

    public void setSearch(Bucket search) {
        this.search = search;
    }

    public Cap getPdfRendering() {
        return pdfRendering;
    }

    public void setPdfRendering(Cap pdfRendering) {
        this.pdfRendering = pdfRendering;
    }

    public Cap getGeocoding() {
        return geocoding;
    }

    public void setGeocoding(Cap geocoding) {
        this.geocoding = geocoding;
    }

    public Cap getProximityQuery() {
        return proximityQuery;
    }

    public void setProximityQuery(Cap proximityQuery) {
        this.proximityQuery = proximityQuery;
    }

    public Duration getNominatimInterval() {
        return nominatimInterval;
    }

    public void setNominatimInterval(Duration nominatimInterval) {
        this.nominatimInterval = nominatimInterval;
    }

    public Duration getNominatimMaxWait() {
        return nominatimMaxWait;
    }

    public void setNominatimMaxWait(Duration nominatimMaxWait) {
        this.nominatimMaxWait = nominatimMaxWait;
    }
}

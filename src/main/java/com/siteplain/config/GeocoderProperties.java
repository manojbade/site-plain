package com.siteplain.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "siteplain.geocoder")
public class GeocoderProperties {

    private String censusBaseUrl = "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress";
    private String nominatimBaseUrl = "https://nominatim.openstreetmap.org/search";
    /** Sent to both geocoders. Nominatim's usage policy asks for an identifying User-Agent with a way to reach the operator. */
    public static final String DEFAULT_USER_AGENT = "site-plain/0.0.1 (+https://site-plain.com/feedback)";

    private String userAgent = DEFAULT_USER_AGENT;
    private Duration censusTimeout = Duration.ofSeconds(3);
    /**
     * 5 s, down from 20 s. Nominatim answers a single-address search in well under a second, so 5 s
     * is several times its normal latency. The longer timeout only made a slow Nominatim hold scarce
     * capacity: each lookup keeps one of the 6 geocoding slots (WorkLimits) through its Census call
     * (up to 3 s), its wait for the one-per-second Nominatim slot (up to 3 s) and the Nominatim call.
     * At 20 s that was up to 26 s a slot, so six slow calls shut out every other uncached lookup (a
     * 503 after 1 s) for nearly half a minute; at 5 s it is at most 11 s, and the visitor waits at most
     * that long for an answer instead of 26 s. A Nominatim timeout reads as no answer: the lookup shows
     * the could-not-resolve page and the address is not cached, so a retry tries again.
     */
    private Duration nominatimTimeout = Duration.ofSeconds(5);
    private String censusBenchmark = "Public_AR_Current";
    private int nominatimLimit = 1;
    /** Geocode cache: most addresses kept, how long a match is reused, how long a definite "no match" is. */
    private int cacheMaxEntries = 5_000;
    private Duration cacheTtl = Duration.ofDays(7);
    private Duration cacheUnresolvedTtl = Duration.ofHours(1);

    public String getCensusBaseUrl() {
        return censusBaseUrl;
    }

    public void setCensusBaseUrl(String censusBaseUrl) {
        this.censusBaseUrl = censusBaseUrl;
    }

    public String getNominatimBaseUrl() {
        return nominatimBaseUrl;
    }

    public void setNominatimBaseUrl(String nominatimBaseUrl) {
        this.nominatimBaseUrl = nominatimBaseUrl;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Duration getCensusTimeout() {
        return censusTimeout;
    }

    public void setCensusTimeout(Duration censusTimeout) {
        this.censusTimeout = censusTimeout;
    }

    public Duration getNominatimTimeout() {
        return nominatimTimeout;
    }

    public void setNominatimTimeout(Duration nominatimTimeout) {
        this.nominatimTimeout = nominatimTimeout;
    }

    public String getCensusBenchmark() {
        return censusBenchmark;
    }

    public void setCensusBenchmark(String censusBenchmark) {
        this.censusBenchmark = censusBenchmark;
    }

    public int getNominatimLimit() {
        return nominatimLimit;
    }

    public void setNominatimLimit(int nominatimLimit) {
        this.nominatimLimit = nominatimLimit;
    }

    public int getCacheMaxEntries() {
        return cacheMaxEntries;
    }

    public void setCacheMaxEntries(int cacheMaxEntries) {
        this.cacheMaxEntries = cacheMaxEntries;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public Duration getCacheUnresolvedTtl() {
        return cacheUnresolvedTtl;
    }

    public void setCacheUnresolvedTtl(Duration cacheUnresolvedTtl) {
        this.cacheUnresolvedTtl = cacheUnresolvedTtl;
    }
}

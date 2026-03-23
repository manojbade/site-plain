package com.siteplain.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "siteplain.geocoder")
public class GeocoderProperties {

    private String censusBaseUrl = "https://geocoding.geo.census.gov/geocoder/locations/onelineaddress";
    private String nominatimBaseUrl = "https://nominatim.openstreetmap.org/search";
    private String userAgent = "site-plain/0.0.1";
    private Duration censusTimeout = Duration.ofSeconds(3);
    private Duration nominatimTimeout = Duration.ofSeconds(20);
    private String censusBenchmark = "Public_AR_Current";
    private int nominatimLimit = 1;

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
}

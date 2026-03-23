package com.siteplain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "siteplain.data")
public class DataProperties {

    private boolean bootstrapEnabled = true;
    private boolean refreshOnStartup;
    private int maxAgeDays = 90;
    private String nplBoundariesUrl;
    private String humanExposureUrl = "https://www3.epa.gov/semsjson/Human_Exposure_Site_List.json";

    public boolean isBootstrapEnabled() {
        return bootstrapEnabled;
    }

    public void setBootstrapEnabled(boolean bootstrapEnabled) {
        this.bootstrapEnabled = bootstrapEnabled;
    }

    public boolean isRefreshOnStartup() {
        return refreshOnStartup;
    }

    public void setRefreshOnStartup(boolean refreshOnStartup) {
        this.refreshOnStartup = refreshOnStartup;
    }

    public int getMaxAgeDays() {
        return maxAgeDays;
    }

    public void setMaxAgeDays(int maxAgeDays) {
        this.maxAgeDays = maxAgeDays;
    }

    public String getNplBoundariesUrl() {
        return nplBoundariesUrl;
    }

    public void setNplBoundariesUrl(String nplBoundariesUrl) {
        this.nplBoundariesUrl = nplBoundariesUrl;
    }

    public String getHumanExposureUrl() {
        return humanExposureUrl;
    }

    public void setHumanExposureUrl(String humanExposureUrl) {
        this.humanExposureUrl = humanExposureUrl;
    }
}

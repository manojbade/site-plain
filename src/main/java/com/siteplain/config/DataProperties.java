package com.siteplain.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "siteplain.data")
public class DataProperties {

    private boolean bootstrapEnabled = true;
    private boolean refreshOnStartup;
    // Retired: boundaries reload by source identity (see DataRefreshPolicyService). Still bound so an
    // existing SITEPLAIN_DATA_MAX_AGE_DAYS does not break startup; the bootstrap logs that it is ignored.
    private Integer maxAgeDays;
    private String nplBoundariesUrl;
    private String humanExposureUrl = "https://www3.epa.gov/semsjson/Human_Exposure_Site_List.json";
    // Whole-download limits (headers and body). Caps are about 4x today's sizes: the boundary
    // release is 57.8 MB and the exposure feed 1.4 MB.
    private Duration boundariesDownloadTimeout = Duration.ofSeconds(300);
    private DataSize boundariesMaxSize = DataSize.ofMegabytes(240);
    private Duration humanExposureDownloadTimeout = Duration.ofSeconds(60);
    private DataSize humanExposureMaxSize = DataSize.ofMegabytes(6);
    // Operator override for the 5% active-site change gate: a refresh whose projected active site
    // count equals this number is accepted even when it moves more than 5%. Any other count is still
    // gated, so a value left set stops mattering once that change is live.
    private Integer acceptActiveCount;

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

    public Integer getMaxAgeDays() {
        return maxAgeDays;
    }

    public void setMaxAgeDays(Integer maxAgeDays) {
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

    public Duration getBoundariesDownloadTimeout() {
        return boundariesDownloadTimeout;
    }

    public void setBoundariesDownloadTimeout(Duration boundariesDownloadTimeout) {
        this.boundariesDownloadTimeout = boundariesDownloadTimeout;
    }

    public DataSize getBoundariesMaxSize() {
        return boundariesMaxSize;
    }

    public void setBoundariesMaxSize(DataSize boundariesMaxSize) {
        this.boundariesMaxSize = boundariesMaxSize;
    }

    public Duration getHumanExposureDownloadTimeout() {
        return humanExposureDownloadTimeout;
    }

    public void setHumanExposureDownloadTimeout(Duration humanExposureDownloadTimeout) {
        this.humanExposureDownloadTimeout = humanExposureDownloadTimeout;
    }

    public DataSize getHumanExposureMaxSize() {
        return humanExposureMaxSize;
    }

    public void setHumanExposureMaxSize(DataSize humanExposureMaxSize) {
        this.humanExposureMaxSize = humanExposureMaxSize;
    }

    public Integer getAcceptActiveCount() {
        return acceptActiveCount;
    }

    public void setAcceptActiveCount(Integer acceptActiveCount) {
        this.acceptActiveCount = acceptActiveCount;
    }
}

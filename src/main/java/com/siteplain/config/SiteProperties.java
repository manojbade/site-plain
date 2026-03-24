package com.siteplain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "siteplain.site")
public class SiteProperties {

    private String baseUrl = "https://site-plain.com";
    private boolean enforceCanonicalHost = false;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isEnforceCanonicalHost() {
        return enforceCanonicalHost;
    }

    public void setEnforceCanonicalHost(boolean enforceCanonicalHost) {
        this.enforceCanonicalHost = enforceCanonicalHost;
    }
}

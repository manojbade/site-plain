package com.siteplain.domain.model;

public class NplSite {

    private String epaId;
    private String siteName;
    private String stateCode;
    private String nplStatusCode;
    private String epaUrl;
    private double distanceMeters;
    private String exposureStatusCode;
    private String exposureStatusLabel;
    private String exposureStatusColor;
    private String exposurePathwayDescription;

    public String getEpaId() {
        return epaId;
    }

    public void setEpaId(String epaId) {
        this.epaId = epaId;
    }

    public String getSiteName() {
        return siteName;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    public String getStateCode() {
        return stateCode;
    }

    public void setStateCode(String stateCode) {
        this.stateCode = stateCode;
    }

    public String getNplStatusCode() {
        return nplStatusCode;
    }

    public void setNplStatusCode(String nplStatusCode) {
        this.nplStatusCode = nplStatusCode;
    }

    public String getEpaUrl() {
        return epaUrl;
    }

    public void setEpaUrl(String epaUrl) {
        this.epaUrl = epaUrl;
    }

    public double getDistanceMeters() {
        return distanceMeters;
    }

    public void setDistanceMeters(double distanceMeters) {
        this.distanceMeters = distanceMeters;
    }

    public String getExposureStatusCode() {
        return exposureStatusCode;
    }

    public void setExposureStatusCode(String exposureStatusCode) {
        this.exposureStatusCode = exposureStatusCode;
    }

    public String getExposureStatusLabel() {
        return exposureStatusLabel;
    }

    public void setExposureStatusLabel(String exposureStatusLabel) {
        this.exposureStatusLabel = exposureStatusLabel;
    }

    public String getExposureStatusColor() {
        return exposureStatusColor;
    }

    public void setExposureStatusColor(String exposureStatusColor) {
        this.exposureStatusColor = exposureStatusColor;
    }

    public String getExposurePathwayDescription() {
        return exposurePathwayDescription;
    }

    public void setExposurePathwayDescription(String exposurePathwayDescription) {
        this.exposurePathwayDescription = exposurePathwayDescription;
    }

    public String effectiveEpaUrl() {
        if (epaUrl != null && !epaUrl.isBlank()) {
            return epaUrl;
        }
        return "https://cumulis.epa.gov/supercpad/cursites/srchsites.cfm?search_string=" + epaId;
    }
}

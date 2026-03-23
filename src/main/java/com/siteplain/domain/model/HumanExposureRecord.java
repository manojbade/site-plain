package com.siteplain.domain.model;

public record HumanExposureRecord(
        String epaId,
        String statusCode,
        String pathwayDescription,
        String nplStatus,
        String siteName
) {
}

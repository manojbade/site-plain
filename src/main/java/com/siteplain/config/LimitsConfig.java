package com.siteplain.config;

import com.siteplain.service.Bulkhead;
import com.siteplain.service.NominatimGate;
import com.siteplain.service.WorkLimits;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LimitsConfig {

    /** Retry-After for a refusal by a full concurrency cap: the work behind it takes a second or two. */
    private static final long CAP_RETRY_AFTER_SECONDS = 5;

    @Bean
    public WorkLimits workLimits(LimitsProperties properties) {
        return new WorkLimits(
                bulkhead("PDF rendering", properties.getPdfRendering()),
                bulkhead("geocoding", properties.getGeocoding()),
                bulkhead("proximity query", properties.getProximityQuery()),
                NominatimGate.realTime(properties.getNominatimInterval(), properties.getNominatimMaxWait()));
    }

    private static Bulkhead bulkhead(String name, LimitsProperties.Cap cap) {
        return new Bulkhead(name, cap.getMax(), cap.getMaxWait(), CAP_RETRY_AFTER_SECONDS);
    }
}

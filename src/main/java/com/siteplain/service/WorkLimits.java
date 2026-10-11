package com.siteplain.service;

import java.time.Duration;

/**
 * The global caps on expensive work, shared by the services that do it: PDF rendering, outbound
 * geocoding calls (plus the Nominatim one-per-second gate) and the proximity query.
 */
public record WorkLimits(Bulkhead pdfRendering, Bulkhead geocoding, Bulkhead proximityQuery, NominatimGate nominatim) {

    /** For tests and tools: caps that never apply and no spacing between Nominatim calls. */
    public static WorkLimits unlimited() {
        return new WorkLimits(
                Bulkhead.unlimited("PDF rendering"),
                Bulkhead.unlimited("geocoding"),
                Bulkhead.unlimited("proximity query"),
                NominatimGate.realTime(Duration.ZERO, Duration.ZERO));
    }
}

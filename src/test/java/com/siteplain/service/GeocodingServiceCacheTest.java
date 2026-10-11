package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.siteplain.config.GeocoderProperties;
import com.siteplain.domain.model.GeocodedAddress;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The geocode cache, against stub Census and Nominatim servers that count their calls. */
class GeocodingServiceCacheTest {

    private static final String CENSUS_MATCH = """
            {"result":{"addressMatches":[{"matchedAddress":"57 SALEM ST, WOBURN, MA, 01801",
              "coordinates":{"x":-71.1523,"y":42.4793},
              "addressComponents":{"city":"WOBURN","state":"MA","zip":"01801"}}]}}
            """;
    private static final String CENSUS_NO_MATCH = "{\"result\":{\"addressMatches\":[]}}";
    private static final String NOMINATIM_MATCH = """
            [{"lat":"42.4793","lon":"-71.1523","place_rank":30,"addresstype":"place","display_name":"57, Salem Street, Woburn",
              "address":{"house_number":"57","state":"Massachusetts","ISO3166-2-lvl4":"US-MA"}}]
            """;

    private HttpServer server;
    private final AtomicInteger censusCalls = new AtomicInteger();
    private final AtomicInteger nominatimCalls = new AtomicInteger();
    private volatile int censusStatus = 200;
    private volatile String censusBody = CENSUS_MATCH;
    private volatile int nominatimStatus = 200;
    private volatile String nominatimBody = "[]";
    private final MutableClock clock = new MutableClock();
    private final GeocoderProperties properties = new GeocoderProperties();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/census", exchange -> {
            censusCalls.incrementAndGet();
            reply(exchange, censusStatus, censusBody);
        });
        server.createContext("/nominatim", exchange -> {
            nominatimCalls.incrementAndGet();
            reply(exchange, nominatimStatus, nominatimBody);
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.setCensusBaseUrl(base + "/census");
        properties.setNominatimBaseUrl(base + "/nominatim");
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void repeatLookupsOfTheSameAddress_callTheGeocoderOnce() {
        GeocodingService service = service();
        GeocodedAddress first = service.geocode("57 Salem St, Woburn, MA");
        GeocodedAddress again = service.geocode("  57  SALEM st ,Woburn, MA, USA ");

        assertThat(first.resolved()).isTrue();
        assertThat(again).isEqualTo(first);
        assertThat(censusCalls).hasValue(1);
        assertThat(nominatimCalls).hasValue(0);
    }

    @Test
    void nominatimMatches_areCachedToo() {
        censusBody = CENSUS_NO_MATCH;
        nominatimBody = NOMINATIM_MATCH;
        GeocodingService service = service();
        assertThat(service.geocode("57 Salem Street Woburn").geocoderUsed()).isEqualTo("nominatim");
        assertThat(service.geocode("57 salem street woburn").geocoderUsed()).isEqualTo("nominatim");

        assertThat(censusCalls).hasValue(1);
        assertThat(nominatimCalls).hasValue(1);
    }

    @Test
    void matchesExpireAfterTheirTimeToLive() {
        GeocodingService service = service();
        service.geocode("57 Salem St, Woburn, MA");
        clock.advance(Duration.ofDays(7).minusMinutes(1));
        service.geocode("57 Salem St, Woburn, MA");
        assertThat(censusCalls).hasValue(1);

        clock.advance(Duration.ofMinutes(2));
        service.geocode("57 Salem St, Woburn, MA");
        assertThat(censusCalls).hasValue(2);
    }

    @Test
    void aDefiniteNoMatchIsRememberedBriefly() {
        censusBody = CENSUS_NO_MATCH;
        GeocodingService service = service();
        GeocodedAddress first = service.geocode("xyzzy nowhere");
        GeocodedAddress again = service.geocode("XYZZY Nowhere");

        assertThat(first.resolved()).isFalse();
        assertThat(again.resolved()).isFalse();
        assertThat(again.rawAddress()).isEqualTo("XYZZY Nowhere");
        assertThat(censusCalls).hasValue(1);
        assertThat(nominatimCalls).hasValue(1);

        clock.advance(Duration.ofMinutes(61));
        service.geocode("xyzzy nowhere");
        assertThat(censusCalls).hasValue(2);
    }

    @Test
    void failedCallsAreNotCached() {
        censusStatus = 500;
        nominatimStatus = 503;
        GeocodingService service = service();
        assertThat(service.geocode("57 Salem St, Woburn, MA").resolved()).isFalse();

        censusStatus = 200;
        assertThat(service.geocode("57 Salem St, Woburn, MA").resolved()).isTrue();
        assertThat(censusCalls).hasValue(2);
    }

    @Test
    void theCacheIsBoundedInSize() {
        properties.setCacheMaxEntries(2);
        GeocodingService service = service();
        service.geocode("1 A St");
        service.geocode("2 B St");
        service.geocode("3 C St");
        service.geocode("1 A St");

        assertThat(censusCalls).hasValue(4);
    }

    @Test
    void cacheKey_ignoresCaseAndSpacing() {
        assertThat(GeocodingService.cacheKey("  57  Salem St ,Woburn,MA ")).isEqualTo("57 salem st, woburn, ma");
        assertThat(GeocodingService.cacheKey(" ")).isNull();
    }

    private GeocodingService service() {
        return new GeocodingService(HttpClient.newHttpClient(), new ObjectMapper(), properties, clock);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-10T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

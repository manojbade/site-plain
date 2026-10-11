package com.siteplain.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.domain.model.NplLookupResult;
import com.siteplain.service.AuditService;
import com.siteplain.service.GeocodingService;
import com.siteplain.service.NplLookupService;
import com.siteplain.service.ResultsLink;
import com.siteplain.service.ResultsPdfService;
import com.siteplain.service.ResultsTokens;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.util.UriComponentsBuilder;

@ExtendWith(MockitoExtension.class)
class LookupControllerRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    @Mock
    private GeocodingService geocodingService;

    @Mock
    private NplLookupService nplLookupService;

    @Mock
    private AuditService auditService;

    @Mock
    private NplBoundaryRepository nplBoundaryRepository;

    @Mock
    private ResultsPdfService resultsPdfService;

    @Mock
    private HumanExposureRepository humanExposureRepository;

    private final ResultsTokens resultsTokens = new ResultsTokens("");

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LookupController controller = new LookupController(
                geocodingService,
                nplLookupService,
                auditService,
                nplBoundaryRepository,
                resultsPdfService,
                humanExposureRepository,
                resultsTokens,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void lookup_redirectsWithOnlyAnEncryptedLinkThatCarriesTheLookup() throws Exception {
        GeocodedAddress geocoded = new GeocodedAddress(
                "Woburn, MA", "Woburn, Middlesex County, Massachusetts, United States", "Woburn", "MA", "01801",
                "nominatim", 42.4793, -71.1523, true, "city");
        when(geocodingService.geocode(anyString())).thenReturn(geocoded);
        NplLookupResult result = new NplLookupResult(geocoded, List.of(), null, false);
        when(nplLookupService.findSitesNear(geocoded)).thenReturn(result);

        String location = mockMvc.perform(post("/lookup").param("address", "  Woburn, MA "))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        // No address, coordinates or anything else readable in the URL.
        assertThat(location).startsWith("/results?r=").doesNotContain("Woburn").doesNotContain("42.4")
                .doesNotContain("&").doesNotContain("state=").doesNotContain("approx");
        ResultsLink link = decode(location);
        assertThat(link).isEqualTo(new ResultsLink("Woburn, Middlesex County, Massachusetts, United States", "Woburn, MA",
                42.4793, -71.1523, "MA", "nominatim", "city", NOW.getEpochSecond()));
        // The audit row is written here, once, with the real result.
        verify(auditService).logLookup(same(geocoded), same(result));
    }

    @Test
    void lookup_houseMatchCarriesNoApproximateLabel() throws Exception {
        when(geocodingService.geocode(anyString())).thenReturn(new GeocodedAddress(
                "57 Salem St", "57 SALEM ST, WOBURN, MA, 01801", "WOBURN", "MA", "01801",
                "census", 42.4793, -71.1523, true));
        when(nplLookupService.findSitesNear(any())).thenReturn(new NplLookupResult(null, List.of(), null, false));

        String location = mockMvc.perform(post("/lookup").param("address", "57 Salem St"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        ResultsLink link = decode(location);
        assertThat(link.state()).isEqualTo("MA");
        assertThat(link.approx()).isNull();
    }

    @Test
    void lookup_treatsGeocodeOutsideCoverageAsUnresolved() throws Exception {
        when(geocodingService.geocode(anyString())).thenReturn(new GeocodedAddress(
                "Null Island", "Null Island", null, null, null, "nominatim", 0.0, 0.0, true));

        String location = mockMvc.perform(post("/lookup").param("address", "Null Island"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).startsWith("/results?r=").doesNotContain("Null").doesNotContain("error=");
        ResultsLink link = decode(location);
        assertThat(link.unresolved()).isTrue();
        assertThat(link.raw()).isNull();
        verify(auditService).logLookup(any(), argThat(NplLookupResult::unresolved));
        verify(nplLookupService, never()).findSitesNear(any());
    }

    @Test
    void approximateMatchLabel_keepsOnlyTheWordsTheGeocodersProduce() {
        assertThat(LookupController.approximateMatchLabel(null)).isNull();
        assertThat(LookupController.approximateMatchLabel(" ")).isNull();
        assertThat(LookupController.approximateMatchLabel("ZIP code")).isEqualTo("ZIP code");
        assertThat(LookupController.approximateMatchLabel("zip CODE")).isEqualTo("ZIP code");
        assertThat(LookupController.approximateMatchLabel(" city ")).isEqualTo("city");
        assertThat(LookupController.approximateMatchLabel("street")).isEqualTo("street");
        // Plain letters are no longer enough: a crafted phrase is shown as the generic word.
        assertThat(LookupController.approximateMatchLabel("certified clean zone")).isEqualTo("area");
        assertThat(LookupController.approximateMatchLabel("isolated dwelling")).isEqualTo("area");
        assertThat(LookupController.approximateMatchLabel("<script>alert(1)</script>")).isEqualTo("area");
        assertThat(LookupController.approximateMatchLabel("x".repeat(31))).isEqualTo("area");
    }

    @Test
    void coordinatesLabel_hasFiveDecimals() {
        assertThat(LookupController.coordinatesLabel(39.825, -84.0551234)).isEqualTo("39.82500, -84.05512");
    }

    private ResultsLink decode(String location) {
        String token = UriComponentsBuilder.fromUri(URI.create(location)).build().getQueryParams().getFirst("r");
        return resultsTokens.decode(token).orElseThrow();
    }
}

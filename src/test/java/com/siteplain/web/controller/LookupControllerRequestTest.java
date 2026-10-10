package com.siteplain.web.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.service.AuditService;
import com.siteplain.service.GeocodingService;
import com.siteplain.service.NplLookupService;
import com.siteplain.service.ResultsPdfService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class LookupControllerRequestTest {

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

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LookupController controller = new LookupController(
                geocodingService,
                nplLookupService,
                auditService,
                nplBoundaryRepository,
                resultsPdfService
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void lookup_carriesApproximateMatchToResults() throws Exception {
        when(geocodingService.geocode(anyString())).thenReturn(new GeocodedAddress(
                "Woburn, MA", "Woburn, Middlesex County, Massachusetts, United States", "Woburn", "MA", "01801",
                "nominatim", 42.4793, -71.1523, true, "city"));

        String location = mockMvc.perform(post("/lookup").param("address", "Woburn, MA"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).contains("state=MA").contains("geocoder=nominatim").contains("approx=city");
    }

    @Test
    void lookup_streetMatchHasNoApproximateParam() throws Exception {
        when(geocodingService.geocode(anyString())).thenReturn(new GeocodedAddress(
                "57 Salem St", "57 SALEM ST, WOBURN, MA, 01801", "WOBURN", "MA", "01801",
                "census", 42.4793, -71.1523, true));

        String location = mockMvc.perform(post("/lookup").param("address", "57 Salem St"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).contains("state=MA").doesNotContain("approx");
    }

    @Test
    void approximateMatchLabel_keepsPlainWordsOnly() {
        assertThat(LookupController.approximateMatchLabel(null)).isNull();
        assertThat(LookupController.approximateMatchLabel(" ")).isNull();
        assertThat(LookupController.approximateMatchLabel("ZIP code")).isEqualTo("ZIP code");
        assertThat(LookupController.approximateMatchLabel("<script>alert(1)</script>")).isEqualTo("area");
        assertThat(LookupController.approximateMatchLabel("x".repeat(31))).isEqualTo("area");
    }
}

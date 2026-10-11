package com.siteplain.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.domain.model.NplLookupResult;
import com.siteplain.domain.model.NplSite;
import com.siteplain.service.AuditService;
import com.siteplain.service.GeocodingService;
import com.siteplain.service.NplLookupService;
import com.siteplain.service.ResultsLink;
import com.siteplain.service.ResultsPdfService;
import com.siteplain.service.ResultsTokens;
import com.siteplain.web.advice.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class LookupControllerPdfTest {

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
                resultsTokens
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void downloadResultsPdf_rendersTheLinkedLookupWithoutWritingAuditLog() throws Exception {
        NplSite site = new NplSite();
        site.setEpaId("TEST001");
        site.setSiteName("Test Site");
        site.setStateCode("GA");
        site.setDistanceMeters(1200.0);
        site.setExposureStatusCode("HENC");
        site.setExposureStatusLabel("Human exposure is NOT currently under control");
        site.setExposureStatusColor("red");

        when(nplLookupService.findSitesNear(any())).thenReturn(new NplLookupResult(
                new GeocodedAddress("123 Main St", "123 Main St, Atlanta, GA", "Atlanta", "GA", "30303", "census", 33.775, -84.395, true),
                List.of(site),
                0.7,
                false
        ));
        when(resultsPdfService.generateResultsPdf(any())).thenReturn("%PDF-mock".getBytes());
        String token = resultsTokens.encode(new ResultsLink("123 Main St, Atlanta, GA", "123 Main St",
                33.775, -84.395, "GA", "census", null, 1_791_600_000L));

        mockMvc.perform(get("/results/pdf").param("r", token))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"site-plain-results.pdf\""))
                .andExpect(content().bytes("%PDF-mock".getBytes()));

        verify(resultsPdfService).generateResultsPdf(argThat(viewModel ->
                "123 Main St, Atlanta, GA".equals(viewModel.normalizedAddress())
                        && "123 Main St".equals(viewModel.inputAddress())
                        && "GA".equals(viewModel.stateCode())));
        verify(auditService, never()).logLookup(any(), any());
    }

    @Test
    void downloadResultsPdf_fromAnOldLinkShowsCoordinatesOnly() throws Exception {
        when(nplLookupService.findSitesNear(any())).thenReturn(new NplLookupResult(null, List.of(), null, false));
        when(resultsPdfService.generateResultsPdf(any())).thenReturn("%PDF-mock".getBytes());

        mockMvc.perform(get("/results/pdf")
                        .param("lat", "33.775")
                        .param("lng", "-84.395")
                        .param("address", "Somewhere Else Entirely")
                        .param("raw", "somewhere")
                        .param("state", "MA"))
                .andExpect(status().isOk());

        verify(resultsPdfService).generateResultsPdf(argThat(viewModel ->
                "33.77500, -84.39500".equals(viewModel.normalizedAddress())
                        && viewModel.inputAddress() == null
                        && viewModel.stateCode() == null));
        verify(auditService, never()).logLookup(any(), any());
    }

    @Test
    void downloadResultsPdf_returnsBadRequestForMissingOrForgedLinks() throws Exception {
        mockMvc.perform(get("/results/pdf"))
                .andExpect(status().isBadRequest());
        String token = resultsTokens.encode(new ResultsLink("1 Main St", "1 Main St", 33.775, -84.395, "GA", "census", null, 1L));
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        mockMvc.perform(get("/results/pdf").param("r", tampered))
                .andExpect(status().isBadRequest());
        String unresolved = resultsTokens.encode(new ResultsLink(null, "nowhere", null, null, null, null, null, 1L));
        mockMvc.perform(get("/results/pdf").param("r", unresolved))
                .andExpect(status().isBadRequest());
        verify(resultsPdfService, never()).generateResultsPdf(any());
    }
}

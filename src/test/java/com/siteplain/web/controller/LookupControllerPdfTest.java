package com.siteplain.web.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.domain.model.NplLookupResult;
import com.siteplain.domain.model.NplSite;
import com.siteplain.service.AuditService;
import com.siteplain.service.GeocodingService;
import com.siteplain.service.NplLookupService;
import com.siteplain.service.ResultsPdfService;
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
    void downloadResultsPdf_returnsAttachmentWithoutWritingAuditLog() throws Exception {
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

        mockMvc.perform(get("/results/pdf")
                        .param("lat", "33.775")
                        .param("lng", "-84.395")
                        .param("address", "123 Main St, Atlanta, GA")
                        .param("raw", "123 Main St")
                        .param("state", "GA"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"site-plain-results.pdf\""))
                .andExpect(content().bytes("%PDF-mock".getBytes()));

        verify(auditService, never()).logLookup(any(), any());
    }

    @Test
    void downloadResultsPdf_returnsBadRequestWhenParamsMissing() throws Exception {
        mockMvc.perform(get("/results/pdf"))
                .andExpect(status().isBadRequest());
    }
}

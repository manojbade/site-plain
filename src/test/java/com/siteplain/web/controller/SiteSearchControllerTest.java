package com.siteplain.web.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.siteplain.domain.view.SiteSearchSuggestion;
import com.siteplain.service.SiteSearchService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SiteSearchControllerTest {

    @Mock
    private SiteSearchService siteSearchService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SiteSearchController(siteSearchService)).build();
    }

    @Test
    void suggestions_returnsJsonPayload() throws Exception {
        when(siteSearchService.suggest("durham")).thenReturn(List.of(
                new SiteSearchSuggestion("NCD980557805", "Durham Meadows", "NC", "Human exposure is under control")
        ));

        mockMvc.perform(get("/api/site-search/suggestions").param("q", "durham"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].epaId").value("NCD980557805"))
                .andExpect(jsonPath("$[0].siteName").value("Durham Meadows"));
    }

    @Test
    void search_redirects_whenServiceFindsExactMatch() throws Exception {
        when(siteSearchService.search("GA1234567890"))
                .thenReturn(SiteSearchService.SiteSearchResolution.redirect("GA1234567890"));

        mockMvc.perform(get("/search/sites").param("q", "GA1234567890"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/site/GA1234567890"));
    }

    @Test
    void search_rendersResultsPage_whenMultipleMatchesExist() throws Exception {
        List<SiteSearchSuggestion> matches = List.of(
                new SiteSearchSuggestion("GA1234567890", "Alpha Site", "GA", "Human exposure is under control"),
                new SiteSearchSuggestion("GA9999999999", "Beta Site", "GA", "Exposure status not reported")
        );
        when(siteSearchService.search("alpha"))
                .thenReturn(SiteSearchService.SiteSearchResolution.results("alpha", matches));

        mockMvc.perform(get("/search/sites").param("q", "alpha"))
                .andExpect(status().isOk())
                .andExpect(view().name("site-search-results"))
                .andExpect(model().attributeExists("viewModel"));
    }

    @Test
    void search_redirectsHome_whenQueryMissing() throws Exception {
        when(siteSearchService.search(null)).thenReturn(SiteSearchService.SiteSearchResolution.empty());

        mockMvc.perform(get("/search/sites"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/"));
    }
}

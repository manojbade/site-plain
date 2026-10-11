package com.siteplain.web.controller;

import com.siteplain.domain.view.SiteSearchResultsViewModel;
import com.siteplain.domain.view.SiteSearchSuggestion;
import com.siteplain.service.SiteSearchService;
import com.siteplain.web.RequestInput;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class SiteSearchController {

    private final SiteSearchService siteSearchService;

    public SiteSearchController(SiteSearchService siteSearchService) {
        this.siteSearchService = siteSearchService;
    }

    @GetMapping(value = "/api/site-search/suggestions", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<SiteSearchSuggestion> suggestions(@RequestParam(name = "q", required = false) String query) {
        return siteSearchService.suggest(RequestInput.maxLength(query, RequestInput.MAX_SEARCH_QUERY, "q"));
    }

    @GetMapping("/search/sites")
    public String search(@RequestParam(name = "q", required = false) String query, Model model) {
        SiteSearchService.SiteSearchResolution resolution =
                siteSearchService.search(RequestInput.maxLength(query, RequestInput.MAX_SEARCH_QUERY, "q"));
        if (!resolution.hasQuery() && !resolution.shouldRedirect()) {
            return "redirect:/";
        }
        if (resolution.shouldRedirect()) {
            return "redirect:/site/" + resolution.redirectEpaId();
        }

        model.addAttribute("viewModel", new SiteSearchResultsViewModel(
                resolution.query(),
                resolution.matches()
        ));
        return "site-search-results";
    }
}

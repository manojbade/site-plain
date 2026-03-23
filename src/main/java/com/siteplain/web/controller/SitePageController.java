package com.siteplain.web.controller;

import com.siteplain.service.SeoPageService;
import com.siteplain.web.form.AddressLookupForm;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class SitePageController {

    private final SeoPageService seoPageService;

    public SitePageController(SeoPageService seoPageService) {
        this.seoPageService = seoPageService;
    }

    @GetMapping("/site/{epaId}")
    public String showSite(@PathVariable String epaId, Model model, HttpServletResponse response) {
        return seoPageService.buildSitePage(epaId.toUpperCase(Locale.ROOT))
                .map(viewModel -> {
                    model.addAttribute("viewModel", viewModel);
                    model.addAttribute("lookupForm", new AddressLookupForm());
                    return "site";
                })
                .orElseGet(() -> {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    return "error/not-found";
                });
    }
}

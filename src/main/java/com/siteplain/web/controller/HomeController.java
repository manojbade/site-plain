package com.siteplain.web.controller;

import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.domain.view.HomePageViewModel;
import com.siteplain.web.form.AddressLookupForm;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;

@Controller
public class HomeController {

    private final NplBoundaryRepository nplBoundaryRepository;
    private final String mapboxPublicToken;

    public HomeController(NplBoundaryRepository nplBoundaryRepository,
                          @Value("${MAPBOX_PUBLIC_TOKEN:}") String mapboxPublicToken) {
        this.nplBoundaryRepository = nplBoundaryRepository;
        this.mapboxPublicToken = StringUtils.hasText(mapboxPublicToken) ? mapboxPublicToken : null;
    }

    @GetMapping("/")
    public String showHome(Model model) {
        model.addAttribute("viewModel", buildHomePageViewModel(nplBoundaryRepository));
        model.addAttribute("mapboxPublicToken", mapboxPublicToken);
        return "index";
    }

    @ModelAttribute("lookupForm")
    public AddressLookupForm lookupForm() {
        return new AddressLookupForm();
    }

    public static HomePageViewModel buildHomePageViewModel(NplBoundaryRepository repository) {
        return new HomePageViewModel(
                repository.countActiveSites(),
                "19.46 million",
                "357 of 435",
                repository.lastLoadedDate().orElse(null)
        );
    }
}

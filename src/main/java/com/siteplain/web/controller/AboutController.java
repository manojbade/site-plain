package com.siteplain.web.controller;

import com.siteplain.domain.view.AboutPageViewModel;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AboutController {

    @GetMapping("/about")
    public String showAbout(Model model) {
        model.addAttribute("viewModel", new AboutPageViewModel(
                List.of(
                        "Site Plain uses EPA National Priorities List boundary polygons, not site centroids.",
                        "Addresses are geocoded with the Census Geocoder first and Nominatim as fallback.",
                        "Human exposure status comes from EPA's Human Exposure Site List JSON."
                ),
                List.of(
                        "Proximity to a site boundary does not mean a property is contaminated.",
                        "EPA site boundaries are not groundwater plume maps.",
                        "The tool only covers active final NPL sites with boundary geometry."
                ),
                List.of(
                        "EPA NPL Superfund Site Boundaries",
                        "EPA Human Exposure Site List JSON",
                        "Census Geocoder and Nominatim"
                )
        ));
        return "about";
    }
}

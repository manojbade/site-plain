package com.siteplain.web.controller;

import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.domain.view.AboutPageViewModel;
import com.siteplain.domain.view.DataSourceLink;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AboutController {

    private final NplBoundaryRepository nplBoundaryRepository;
    private final HumanExposureRepository humanExposureRepository;

    public AboutController(NplBoundaryRepository nplBoundaryRepository, HumanExposureRepository humanExposureRepository) {
        this.nplBoundaryRepository = nplBoundaryRepository;
        this.humanExposureRepository = humanExposureRepository;
    }

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
                        "Distance results only cover active final NPL sites with an EPA-published boundary. Active sites without one have a site page but no distance."
                ),
                DataSourceLink.officialSources(
                        nplBoundaryRepository.lastLoadedDate().orElse(null),
                        humanExposureRepository.lastLoadedDate().orElse(null))
        ));
        return "about";
    }
}

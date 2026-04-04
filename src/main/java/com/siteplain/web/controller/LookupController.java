package com.siteplain.web.controller;

import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.domain.model.NplLookupResult;
import com.siteplain.domain.model.NplSite;
import com.siteplain.domain.view.ResultsViewModel;
import com.siteplain.service.AuditService;
import com.siteplain.service.GeocodingService;
import com.siteplain.service.NplLookupService;
import com.siteplain.service.ResultsPdfService;
import com.siteplain.support.StateResourceRegistry;
import com.siteplain.web.form.AddressLookupForm;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class LookupController {

    private final GeocodingService geocodingService;
    private final NplLookupService nplLookupService;
    private final AuditService auditService;
    private final NplBoundaryRepository nplBoundaryRepository;
    private final ResultsPdfService resultsPdfService;

    @Value("${MAPBOX_PUBLIC_TOKEN:}")
    private String mapboxPublicToken;

    public LookupController(GeocodingService geocodingService,
                            NplLookupService nplLookupService,
                            AuditService auditService,
                            NplBoundaryRepository nplBoundaryRepository,
                            ResultsPdfService resultsPdfService) {
        this.geocodingService = geocodingService;
        this.nplLookupService = nplLookupService;
        this.auditService = auditService;
        this.nplBoundaryRepository = nplBoundaryRepository;
        this.resultsPdfService = resultsPdfService;
    }

    @PostMapping("/lookup")
    public String lookup(@Valid @ModelAttribute("lookupForm") AddressLookupForm form,
                         BindingResult bindingResult,
                         Model model,
                         RedirectAttributes redirectAttributes) {
        if (bindingResult.hasErrors()) {
            model.addAttribute("viewModel", HomeController.buildHomePageViewModel(nplBoundaryRepository));
            return "index";
        }
        GeocodedAddress geocodedAddress = geocodingService.geocode(form.getAddress().trim());
        if (!geocodedAddress.resolved()) {
            auditService.logLookup(geocodedAddress, new NplLookupResult(geocodedAddress, java.util.List.of(), null, true));
            redirectAttributes.addAttribute("error", "unresolved");
            redirectAttributes.addAttribute("address", form.getAddress().trim());
            return "redirect:/results";
        }
        redirectAttributes.addAttribute("lat", geocodedAddress.latitude());
        redirectAttributes.addAttribute("lng", geocodedAddress.longitude());
        redirectAttributes.addAttribute("address", geocodedAddress.normalizedAddress());
        redirectAttributes.addAttribute("raw", form.getAddress().trim());
        redirectAttributes.addAttribute("state", geocodedAddress.stateCode());
        redirectAttributes.addAttribute("geocoder", geocodedAddress.geocoderUsed());
        return "redirect:/results";
    }

    @GetMapping("/results")
    public String showResults(@RequestParam(required = false) Double lat,
                              @RequestParam(required = false) Double lng,
                              @RequestParam(required = false) String address,
                              @RequestParam(required = false) String raw,
                              @RequestParam(required = false) String state,
                              @RequestParam(required = false) String geocoder,
                              @RequestParam(required = false) String error,
                              Model model) {
        if ("unresolved".equalsIgnoreCase(error)) {
            model.addAttribute("viewModel", unresolvedViewModel(raw != null ? raw : address, address));
            return "results";
        }
        if (lat == null || lng == null || address == null) {
            model.addAttribute("viewModel", unresolvedViewModel(null, null));
            return "results";
        }
        ResultsViewModel viewModel = buildResolvedViewModel(lat, lng, address, raw, state, geocoder, true, true);
        model.addAttribute("viewModel", viewModel);
        return "results";
    }

    @GetMapping(value = "/results/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadResultsPdf(@RequestParam(required = false) Double lat,
                                                     @RequestParam(required = false) Double lng,
                                                     @RequestParam(required = false) String address,
                                                     @RequestParam(required = false) String raw,
                                                     @RequestParam(required = false) String state,
                                                     @RequestParam(required = false) String geocoder) {
        if (lat == null || lng == null || address == null) {
            return ResponseEntity.badRequest().build();
        }
        ResultsViewModel viewModel = buildResolvedViewModel(lat, lng, address, raw, state, geocoder, false, false);
        byte[] pdf = resultsPdfService.generateResultsPdf(viewModel);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("site-plain-results.pdf")
                        .build()
                        .toString())
                .body(pdf);
    }

    private ResultsViewModel buildResolvedViewModel(Double lat,
                                                    Double lng,
                                                    String address,
                                                    String raw,
                                                    String state,
                                                    String geocoder,
                                                    boolean includeMap,
                                                    boolean writeAuditLog) {
        GeocodedAddress geocodedAddress = new GeocodedAddress(
                raw != null ? raw : address,
                address,
                null,
                state,
                null,
                geocoder,
                lat,
                lng,
                true
        );
        NplLookupResult result = nplLookupService.findSitesNear(geocodedAddress);
        if (writeAuditLog) {
            auditService.logLookup(geocodedAddress, result);
        }

        String siteBoundaryGeojson = null;
        if (includeMap && !result.sites().isEmpty()) {
            List<String> epaIds = result.sites().stream().map(NplSite::getEpaId).toList();
            Map<String, String> geomMap = nplBoundaryRepository.findGeoJsonByIds(epaIds);
            siteBoundaryGeojson = buildFeatureCollection(result.sites(), geomMap);
        }

        return new ResultsViewModel(
                geocodedAddress.rawAddress(),
                geocodedAddress.normalizedAddress(),
                false,
                !result.sites().isEmpty(),
                result.sites().size(),
                result.sites(),
                includeMap ? mapboxPublicToken : null,
                geocodedAddress.stateCode(),
                lat,
                lng,
                siteBoundaryGeojson,
                StateResourceRegistry.lookup(geocodedAddress.stateCode())
        );
    }

    private ResultsViewModel unresolvedViewModel(String inputAddress, String normalizedAddress) {
        return new ResultsViewModel(
                inputAddress,
                normalizedAddress,
                true,
                false,
                0,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    static String buildFeatureCollection(List<NplSite> sites, Map<String, String> geomMap) {
        if (geomMap == null || geomMap.isEmpty()) {
            return null;
        }
        StringBuilder fc = new StringBuilder("{\"type\":\"FeatureCollection\",\"features\":[");
        boolean first = true;
        for (NplSite site : sites) {
            String geom = geomMap.get(site.getEpaId());
            if (geom == null) continue;
            if (!first) fc.append(",");
            String color = site.getExposureStatusColor() != null ? site.getExposureStatusColor() : "gray";
            String safeName = site.getSiteName() == null ? "" :
                    site.getSiteName().replace("\\", "\\\\").replace("\"", "\\\"");
            fc.append("{\"type\":\"Feature\",\"geometry\":").append(geom)
              .append(",\"properties\":{\"epaId\":\"").append(site.getEpaId()).append("\"")
              .append(",\"siteName\":\"").append(safeName).append("\"")
              .append(",\"color\":\"").append(color).append("\"}}");
            first = false;
        }
        fc.append("]}");
        return fc.toString();
    }
}

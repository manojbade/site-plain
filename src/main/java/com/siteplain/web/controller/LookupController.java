package com.siteplain.web.controller;

import com.siteplain.data.repository.HumanExposureRepository;
import com.siteplain.data.repository.NplBoundaryRepository;
import com.siteplain.domain.model.GeocodedAddress;
import com.siteplain.domain.model.NplLookupResult;
import com.siteplain.domain.model.NplSite;
import com.siteplain.domain.view.ResultsViewModel;
import com.siteplain.service.AuditService;
import com.siteplain.service.GeocodingService;
import com.siteplain.service.NplLookupService;
import com.siteplain.service.ResultsLink;
import com.siteplain.service.ResultsPdfService;
import com.siteplain.service.ResultsTokens;
import com.siteplain.support.ApproximateMatch;
import com.siteplain.support.DisplayDates;
import com.siteplain.support.StateNames;
import com.siteplain.support.StateResourceRegistry;
import com.siteplain.support.UsCoverageArea;
import com.siteplain.web.BadRequestException;
import com.siteplain.web.RequestInput;
import com.siteplain.web.form.AddressLookupForm;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.web.util.UriComponentsBuilder;

@Controller
public class LookupController {

    private final GeocodingService geocodingService;
    private final NplLookupService nplLookupService;
    private final AuditService auditService;
    private final NplBoundaryRepository nplBoundaryRepository;
    private final ResultsPdfService resultsPdfService;
    private final HumanExposureRepository humanExposureRepository;
    private final ResultsTokens resultsTokens;
    private final Clock clock;

    @Value("${MAPBOX_PUBLIC_TOKEN:}")
    private String mapboxPublicToken;

    @Autowired
    public LookupController(GeocodingService geocodingService,
                            NplLookupService nplLookupService,
                            AuditService auditService,
                            NplBoundaryRepository nplBoundaryRepository,
                            ResultsPdfService resultsPdfService,
                            HumanExposureRepository humanExposureRepository,
                            ResultsTokens resultsTokens) {
        this(geocodingService, nplLookupService, auditService, nplBoundaryRepository, resultsPdfService,
                humanExposureRepository, resultsTokens, Clock.systemUTC());
    }

    LookupController(GeocodingService geocodingService,
                     NplLookupService nplLookupService,
                     AuditService auditService,
                     NplBoundaryRepository nplBoundaryRepository,
                     ResultsPdfService resultsPdfService,
                     HumanExposureRepository humanExposureRepository,
                     ResultsTokens resultsTokens,
                     Clock clock) {
        this.geocodingService = geocodingService;
        this.nplLookupService = nplLookupService;
        this.auditService = auditService;
        this.nplBoundaryRepository = nplBoundaryRepository;
        this.resultsPdfService = resultsPdfService;
        this.humanExposureRepository = humanExposureRepository;
        this.resultsTokens = resultsTokens;
        this.clock = clock;
    }

    /**
     * The one place a lookup happens: geocode, find the sites, write the lookup_audit row, then
     * redirect to /results?r=<encrypted link>. The address and coordinates travel only inside the
     * encrypted link, so they never reach the URL, browser history or analytics.
     */
    @PostMapping("/lookup")
    public String lookup(@Valid @ModelAttribute("lookupForm") AddressLookupForm form,
                         BindingResult bindingResult,
                         Model model,
                         RedirectAttributes redirectAttributes,
                         HttpServletResponse response) {
        if (bindingResult.hasErrors()) {
            // Blank or over 200 characters: the form again, with the field error, as a 400.
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            model.addAttribute("viewModel", HomeController.buildHomePageViewModel(nplBoundaryRepository, humanExposureRepository));
            return "index";
        }
        String typed = form.getAddress().trim();
        GeocodedAddress geocodedAddress = geocodingService.geocode(typed);
        long issuedAt = clock.instant().getEpochSecond();
        if (!geocodedAddress.resolved() || !UsCoverageArea.contains(geocodedAddress.latitude(), geocodedAddress.longitude())) {
            auditService.logLookup(geocodedAddress, new NplLookupResult(geocodedAddress, List.of(), null, true));
            // Nothing typed goes into the link: the page is the same generic "could not resolve" for everyone.
            redirectAttributes.addAttribute("r", resultsTokens.encode(
                    new ResultsLink(null, null, null, null, null, null, null, issuedAt)));
            return "redirect:/results";
        }
        // Run here so the audit row has the real result count; GET /results runs it again to render.
        auditService.logLookup(geocodedAddress, nplLookupService.findSitesNear(geocodedAddress));
        redirectAttributes.addAttribute("r", resultsTokens.encode(new ResultsLink(
                geocodedAddress.normalizedAddress(),
                typed,
                geocodedAddress.latitude(),
                geocodedAddress.longitude(),
                geocodedAddress.stateCode(),
                geocodedAddress.geocoderUsed(),
                geocodedAddress.approximateMatch(),
                issuedAt)));
        return "redirect:/results";
    }

    /**
     * Renders a results link. Never writes lookup_audit (POST /lookup did). Links from before the
     * encrypted format (lat, lng, address, ... in the query) still work but show the coordinates
     * only: nothing else in such a URL can be trusted, so no address label, state or geocoder.
     */
    @GetMapping("/results")
    public String showResults(@RequestParam(name = "r", required = false) String token,
                              @RequestParam(required = false) Double lat,
                              @RequestParam(required = false) Double lng,
                              @RequestParam(required = false) String address,
                              @RequestParam(required = false) String raw,
                              @RequestParam(required = false) String state,
                              @RequestParam(required = false) String geocoder,
                              @RequestParam(required = false) String approx,
                              @RequestParam(required = false) String error,
                              Model model,
                              HttpServletResponse response) {
        requireCappedResultParams(address, raw, state, geocoder, approx);
        RequestInput.maxLength(error, RequestInput.MAX_SHORT_PARAM, "error");
        if (token != null) {
            ResultsLink link = decodeLink(token);
            if (link.unresolved()) {
                // Generic page; links made before the typed text was left out still carry it, and it is not shown.
                model.addAttribute("viewModel", unresolvedViewModel());
                return "results";
            }
            requireCovered(link.lat(), link.lng());
            model.addAttribute("viewModel", buildResolvedViewModel(fromLink(link), true, pdfPathForToken(token)));
            return "results";
        }
        if ("unresolved".equalsIgnoreCase(error) || lat == null || lng == null) {
            // An old unresolved link echoed the typed address from its URL; now nothing from the URL is shown.
            model.addAttribute("viewModel", unresolvedViewModel());
            return "results";
        }
        if (!UsCoverageArea.contains(lat, lng)) {
            // Not a point any lookup could produce. Answering would give a confident "no sites".
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            model.addAttribute("viewModel", unresolvedViewModel());
            return "results";
        }
        model.addAttribute("viewModel", buildResolvedViewModel(legacyAddress(lat, lng, approx), true, legacyPdfPath(lat, lng, approx)));
        return "results";
    }

    @GetMapping(value = "/results/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadResultsPdf(@RequestParam(name = "r", required = false) String token,
                                                     @RequestParam(required = false) Double lat,
                                                     @RequestParam(required = false) Double lng,
                                                     @RequestParam(required = false) String address,
                                                     @RequestParam(required = false) String raw,
                                                     @RequestParam(required = false) String state,
                                                     @RequestParam(required = false) String geocoder,
                                                     @RequestParam(required = false) String approx) {
        requireCappedResultParams(address, raw, state, geocoder, approx);
        GeocodedAddress location;
        if (token != null) {
            ResultsLink link = decodeLink(token);
            if (link.unresolved()) {
                throw new BadRequestException("results link for an unresolved lookup has no report");
            }
            requireCovered(link.lat(), link.lng());
            location = fromLink(link);
        } else {
            if (lat == null || lng == null) {
                throw new BadRequestException("results PDF without a location");
            }
            requireCovered(lat, lng);
            location = legacyAddress(lat, lng, approx);
        }
        ResultsViewModel viewModel = buildResolvedViewModel(location, false, null);
        byte[] pdf = resultsPdfService.generateResultsPdf(viewModel);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("site-plain-results.pdf")
                        .build()
                        .toString())
                .body(pdf);
    }

    private ResultsLink decodeLink(String token) {
        RequestInput.maxLength(token, ResultsTokens.MAX_TOKEN_LENGTH, "r");
        return resultsTokens.decode(token).orElseThrow(() -> new BadRequestException("results link not valid"));
    }

    /** Coordinates no lookup could produce (NaN, out of range, outside US coverage) are a client error. */
    private static void requireCovered(Double lat, Double lng) {
        if (!UsCoverageArea.contains(lat, lng)) {
            throw new BadRequestException("coordinates outside coverage");
        }
    }

    private static GeocodedAddress fromLink(ResultsLink link) {
        return new GeocodedAddress(
                link.raw(),
                link.label(),
                null,
                link.state(),
                null,
                link.geocoder(),
                link.lat(),
                link.lng(),
                true,
                approximateMatchLabel(link.approx()));
    }

    /** An old-format link: the coordinates as the label, and nothing else from the URL but the approximate caveat. */
    private static GeocodedAddress legacyAddress(double lat, double lng, String approx) {
        return new GeocodedAddress(
                null,
                coordinatesLabel(lat, lng),
                null,
                null,
                null,
                null,
                lat,
                lng,
                true,
                approximateMatchLabel(approx));
    }

    static String coordinatesLabel(double lat, double lng) {
        return String.format(Locale.ROOT, "%.5f, %.5f", lat, lng);
    }

    private static String pdfPathForToken(String token) {
        return UriComponentsBuilder.fromPath("/results/pdf").queryParam("r", token).build().toUriString();
    }

    private static String legacyPdfPath(double lat, double lng, String approx) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromPath("/results/pdf")
                .queryParam("lat", lat)
                .queryParam("lng", lng);
        String label = approximateMatchLabel(approx);
        if (label != null) {
            builder.queryParam("approx", label);
        }
        return builder.encode().build().toUriString();
    }

    private static void requireCappedResultParams(String address, String raw, String state, String geocoder, String approx) {
        RequestInput.maxLength(address, RequestInput.MAX_ADDRESS, "address");
        RequestInput.maxLength(raw, RequestInput.MAX_ADDRESS, "raw");
        RequestInput.maxLength(state, RequestInput.MAX_STATE, "state");
        RequestInput.maxLength(geocoder, RequestInput.MAX_GEOCODER, "geocoder");
        RequestInput.maxLength(approx, RequestInput.MAX_APPROX, "approx");
    }

    private ResultsViewModel buildResolvedViewModel(GeocodedAddress geocodedAddress, boolean includeMap, String pdfPath) {
        NplLookupResult result = nplLookupService.findSitesNear(geocodedAddress);

        String siteBoundaryGeojson = null;
        if (includeMap && !result.sites().isEmpty()) {
            List<String> epaIds = result.sites().stream().map(NplSite::getEpaId).toList();
            Map<String, String> geomMap = nplBoundaryRepository.findGeoJsonByIds(epaIds);
            siteBoundaryGeojson = buildFeatureCollection(result.sites(), geomMap);
        }

        String stateCode = normalizeStateCode(geocodedAddress.stateCode());
        return new ResultsViewModel(
                geocodedAddress.rawAddress(),
                geocodedAddress.normalizedAddress(),
                false,
                !result.sites().isEmpty(),
                result.sites().size(),
                result.sites(),
                includeMap ? mapboxPublicToken : null,
                stateCode,
                geocodedAddress.latitude(),
                geocodedAddress.longitude(),
                siteBoundaryGeojson,
                StateResourceRegistry.lookup(stateCode),
                nplBoundaryRepository.countActiveSites(),
                nplLookupService.findActiveSitesWithoutBoundary(stateCode),
                nplLookupService.countActiveSitesWithoutBoundary(),
                geocodedAddress.approximateMatch(),
                DisplayDates.format(humanExposureRepository.statusAsOfDate().orElse(null)),
                pdfPath
        );
    }

    private ResultsViewModel unresolvedViewModel() {
        return new ResultsViewModel(
                null,
                null,
                true,
                false,
                0,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                List.of(),
                0,
                null,
                null,
                null
        );
    }

    /**
     * What a coarse geocoder match was (city, ZIP code, ...). Held to the words the geocoders produce
     * (ApproximateMatch), with "area" for anything else, since on an old link it comes from the URL.
     */
    static String approximateMatchLabel(String approx) {
        return ApproximateMatch.label(approx);
    }

    private static String normalizeStateCode(String stateCode) {
        if (stateCode == null) {
            return null;
        }
        String normalized = stateCode.trim().toUpperCase(Locale.ROOT);
        return StateNames.isKnown(normalized) ? normalized : null;
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

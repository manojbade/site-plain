package com.siteplain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.siteplain.data.loader.SeoPageCacheBuilder;
import com.siteplain.data.loader.StatePageCacheBuilder;
import com.siteplain.service.ResultsLink;
import com.siteplain.service.ResultsTokens;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Renders real site, state and results pages against fixture rows and checks the text users and
 * search engines see.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class PageRenderingIntegrationTest {

    /** A script tag without a src attribute, that is, an inline script block. */
    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_ATTRIBUTE = Pattern.compile("\\sstyle\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_BLOCK = Pattern.compile("<style[\\s>]", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESULTS_SCRIPT = Pattern.compile("<script src=\"/js/results(-[0-9a-f]{32})?\\.js\"></script>");

    private static final List<String> IDS = List.of("OHR000000001", "OHR000000002");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ResultsTokens resultsTokens;

    @Autowired
    private SeoPageCacheBuilder seoPageCacheBuilder;

    @Autowired
    private StatePageCacheBuilder statePageCacheBuilder;

    @BeforeEach
    void loadFixture() {
        cleanup();
        // Scheme-less, like three boundary rows in prod.
        insertBoundary("OHR000000001", "Render Test Landfill", "www.epa.gov/superfund/render-test-landfill");
        insertExposure("OHR000000001", "HENC", "OH", null);
        // Final per the feed, no published boundary.
        insertExposure("OHR000000002", "HEID", "OH", "www.epa.gov/superfund/render-test-unmapped");
        seoPageCacheBuilder.rebuild();
        statePageCacheBuilder.rebuild();
    }

    @AfterEach
    void cleanup() {
        String placeholders = String.join(",", IDS.stream().map(id -> "?").toList());
        jdbcTemplate.update("DELETE FROM site_seo_page_cache WHERE epa_id IN (" + placeholders + ")", IDS.toArray());
        jdbcTemplate.update("DELETE FROM npl_human_exposure WHERE epa_id IN (" + placeholders + ")", IDS.toArray());
        jdbcTemplate.update("DELETE FROM npl_site_boundaries WHERE epa_id IN (" + placeholders + ")", IDS.toArray());
        jdbcTemplate.update("DELETE FROM state_page_cache WHERE state_code = 'OH'");
    }

    @Test
    void sitePage_metaDescriptionNamesStateAndStatus() throws Exception {
        String html = mockMvc.perform(get("/site/OHR000000001"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("viewModel.")
                .contains("<title>Render Test Landfill: EPA Superfund Site | Site Plain</title>")
                .contains("content=\"Render Test Landfill is an active EPA Superfund site in Ohio. "
                        + "Human exposure is NOT currently under control. "
                        + "Enter your address to see your distance from this site.\"");

        String unmapped = mockMvc.perform(get("/site/OHR000000002"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(unmapped).doesNotContain("viewModel.")
                .contains("in Ohio. Exposure status: insufficient data. "
                        + "EPA has not published a boundary for this site.\"");
    }

    @Test
    void epaProfileLinks_areAbsoluteOnSiteAndResultsPages() throws Exception {
        String site = mockMvc.perform(get("/site/OHR000000001"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(site).contains("href=\"https://www.epa.gov/superfund/render-test-landfill\"")
                .doesNotContain("href=\"www.epa.gov");

        String unmapped = mockMvc.perform(get("/site/OHR000000002"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(unmapped).contains("href=\"https://www.epa.gov/superfund/render-test-unmapped\"");

        String results = mockMvc.perform(results(39.825, -84.055, "Render Test Address, Dayton, OH", "OH", null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(results).contains("href=\"https://www.epa.gov/superfund/render-test-landfill\"")
                .doesNotContain("href=\"www.epa.gov");
    }

    @Test
    void resultsPage_showsApproximateLocationNoticeForCoarseMatches() throws Exception {
        String approximate = mockMvc.perform(results(39.825, -84.055, "Dayton, Montgomery County, Ohio, United States", "OH", "city"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(approximate).contains("<span class=\"sp-notice-title\">Approximate location</span>")
                .containsPattern("href=\"/results/pdf\\?r=[A-Za-z0-9_-]+\"")
                .doesNotContain("approx=city");
        assertThat(approximate.replaceAll("\\s+", " ")).contains("only to the following <span>city</span>: "
                + "<span>Dayton, Montgomery County, Ohio, United States</span>");
        // The caveat comes before the summary numbers.
        assertThat(approximate.indexOf("sp-notice-title")).isLessThan(approximate.indexOf("tt-kpi-strip"));

        String exact = mockMvc.perform(results(39.825, -84.055, "1 Main St, Dayton, OH", "OH", null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(exact).doesNotContain("Approximate location");
    }

    @Test
    void results_rejectInvalidCoordinatesWithoutAuditRow() throws Exception {
        int auditRows = auditRowCount();
        String[][] invalid = {
                {"NaN", "NaN"}, {"Infinity", "-84.05"}, {"39.82", "-Infinity"},
                {"95", "200"}, {"1e300", "-84.05"}, {"0", "0"}, {"51.5", "-0.12"}};
        for (String[] point : invalid) {
            String html = mockMvc.perform(get("/results")
                            .param("lat", point[0])
                            .param("lng", point[1])
                            .param("address", "1 Example Street, Woburn, MA 01801"))
                    .andExpect(status().isBadRequest())
                    .andReturn().getResponse().getContentAsString();
            assertThat(html).contains("Address could not be resolved")
                    .doesNotContain("No active EPA Superfund sites found");

            mockMvc.perform(get("/results/pdf")
                            .param("lat", point[0])
                            .param("lng", point[1])
                            .param("address", "1 Example Street, Woburn, MA 01801"))
                    .andExpect(status().isBadRequest());
        }
        // A results link that decodes but holds such a point (only a forged key could make one) is refused too.
        mockMvc.perform(results(0.0, 0.0, "1 Example Street, Woburn, MA 01801", "MA", null))
                .andExpect(status().isBadRequest());
        assertThat(auditRowCount()).isEqualTo(auditRows);

        // Viewing results never writes lookup_audit; only POST /lookup does (SEC-7).
        mockMvc.perform(results(39.825, -84.055, "1 Main St, Dayton, OH", null, null))
                .andExpect(status().isOk());
        mockMvc.perform(get("/results").param("lat", "39.825").param("lng", "-84.055").param("address", "1 Main St"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/results/pdf").param("lat", "39.825").param("lng", "-84.055"))
                .andExpect(status().isOk());
        assertThat(auditRowCount()).isEqualTo(auditRows);
    }

    @Test
    void oldStyleLinks_showCoordinatesOnlyAndKeepOnlyTheApproximateCaveat() throws Exception {
        String html = page(get("/results")
                .param("lat", "39.825").param("lng", "-84.055")
                .param("address", "1 Example Street, Woburn, MA 01801")
                .param("raw", "1 example street")
                .param("state", "MA")
                .param("geocoder", "manual")
                .param("approx", "city"), 200);
        assertThat(html).contains("<h1 class=\"tt-page-title mb-3\">39.82500, -84.05500</h1>")
                .contains("Render Test Landfill")
                .contains("Approximate location")
                .contains("href=\"/results/pdf?lat=39.825&amp;lng=-84.055&amp;approx=city\"")
                .doesNotContain("Example Street")
                .doesNotContain("1 example street")
                .doesNotContain("<li>MA</li>")
                .doesNotContain("Active sites in this state with no published boundary");

        String unresolved = page(get("/results").param("error", "unresolved").param("address", "Fake claim text"), 200);
        assertThat(unresolved).contains("Address could not be resolved").doesNotContain("Fake claim text");

        mockMvc.perform(get("/results").param("r", "not-a-real-link")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/results/pdf").param("r", "not-a-real-link")).andExpect(status().isBadRequest());
    }

    @Test
    void results_rejectOverlongParametersWithA400() throws Exception {
        String[][] overlong = {
                {"address", "x".repeat(201)}, {"raw", "x".repeat(201)}, {"state", "OHX"},
                {"geocoder", "x".repeat(21)}, {"approx", "x".repeat(31)}};
        for (String[] param : overlong) {
            mockMvc.perform(results(39.825, -84.055, "1 Main St, Dayton, OH", null, null).param(param[0], param[1]))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(get("/results/pdf").param("lat", "39.825").param("lng", "-84.055")
                            .param("address", "1 Main St, Dayton, OH").param(param[0], param[1]))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(get("/results").param("error", "unresolved").param("address", "x".repeat(201)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exposureAsOfDate_isShownOnSiteAndResultsPages() throws Exception {
        String site = mockMvc.perform(get("/site/OHR000000001"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(site).contains("EPA exposure status as of October 10, 2026");

        String unmapped = mockMvc.perform(get("/site/OHR000000002"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(unmapped).contains("EPA exposure status as of October 10, 2026");

        String results = mockMvc.perform(results(39.825, -84.055, "1 Main St, Dayton, OH", "OH", null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(results).contains("EPA exposure status as of October 10, 2026");

        String home = mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(home).contains("EPA site boundaries loaded").doesNotContain("Updated <span>");
    }

    @Test
    void resultsPage_saysInsideBoundaryForAPointInsideTheSite() throws Exception {
        String inside = mockMvc.perform(results(39.825, -84.055, "1 Main St, Dayton, OH", null, null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(inside).contains("Inside the EPA site boundary").doesNotContain("Less than 10 feet");

        String outside = mockMvc.perform(results(39.825, -84.045, "2 Main St, Dayton, OH", null, null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(outside).contains("Render Test Landfill").doesNotContain("Inside the EPA site boundary");
    }

    @Test
    void resultsPage_showsStatusLegendAndStatusSquares() throws Exception {
        String results = mockMvc.perform(results(39.825, -84.055, "1 Main St, Dayton, OH", "OH", null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(results).contains("class=\"sp-status-legend\"")
                .contains("<span class=\"sp-status sp-status-red\">Not under control</span>")
                .contains("<span class=\"sp-status sp-status-yellow\">Under control</span>")
                .contains("<span class=\"sp-status sp-status-green\">Long-term protection achieved</span>")
                .contains("<span class=\"sp-status sp-status-gray\">Insufficient data or not reported</span>")
                // The HENC fixture site is "not under control", the only state shown in red.
                .contains("<span class=\"sp-status sp-status-red\">Human exposure is NOT currently under control</span>")
                .doesNotContain("badge-exposure-");

        String site = mockMvc.perform(get("/site/OHR000000002"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(site).contains("<span class=\"sp-status sp-status-gray\">Exposure status: insufficient data</span>");
    }

    @Test
    void feedbackForm_putsTheCommentBoxBeforeTheAnswerButtons() throws Exception {
        String site = mockMvc.perform(get("/site/OHR000000001"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int form = site.indexOf("id=\"feedback\"");
        int comments = site.indexOf("name=\"comments\"", form);
        int yes = site.indexOf("name=\"helpful\" value=\"true\"", form);
        assertThat(form).isPositive();
        assertThat(comments).isPositive().isLessThan(yes);
        assertThat(site).contains("<label class=\"form-label tt-field-label mb-2\" for=\"feedback-comments\">");
    }

    @Test
    void epaPathwayText_isEscapedWithOnlyLineBreaksKept() throws Exception {
        jdbcTemplate.update("UPDATE npl_human_exposure SET humanexposurepathdesc = ? WHERE epa_id = 'OHR000000001'",
                "Groundwater<br/>Soil <b>vapor</b> & <script>alert(1)</script>");
        seoPageCacheBuilder.rebuild();
        String expected = "Groundwater<br/>Soil &lt;b&gt;vapor&lt;/b&gt; &amp; &lt;script&gt;alert(1)&lt;/script&gt;";

        String site = page(get("/site/OHR000000001"), 200);
        assertThat(site).contains("<p class=\"sp-epa-text mb-0\">" + expected + "</p>")
                .doesNotContain("<script>alert(1)")
                .doesNotContain("<b>vapor");

        String results = page(results(39.825, -84.055, "1 Main St, Dayton, OH", "OH", null), 200);
        assertThat(results).contains("<span>" + expected + "</span>")
                .doesNotContain("<script>alert(1)")
                .doesNotContain("<b>vapor");
    }

    @Test
    void statePage_metaDescriptionNamesState() throws Exception {
        String html = mockMvc.perform(get("/state/OH"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).doesNotContain("viewModel.")
                .contains("content=\"2 active EPA Superfund sites in Ohio. "
                        + "Enter your address to see how close you are to any of them.\"");
    }

    @Test
    void dataPages_haveNoInlineScriptsStylesOrEmDashes() throws Exception {
        Map<String, String> pages = Map.of(
                "results with sites", page(results(39.825, -84.055, "1 Main St, Dayton, OH", "OH", null), 200),
                "results approximate", page(results(39.825, -84.055, "Dayton, OH", "OH", "city"), 200),
                "results without sites", page(results(38.5, -116.5, "Somewhere, NV", "NV", null), 200),
                "results unresolved", page(get("/results").param("error", "unresolved").param("address", "nowhere"), 200),
                "site", page(get("/site/OHR000000001"), 200),
                "site without boundary", page(get("/site/OHR000000002"), 200),
                "state", page(get("/state/OH"), 200),
                "site search", page(get("/search/sites").param("q", "render test"), 200),
                "site search without match", page(get("/search/sites").param("q", "zzqx no such site"), 200));
        pages.forEach((name, html) -> {
            assertThat(INLINE_SCRIPT.matcher(html).find()).as("inline script on %s", name).isFalse();
            assertThat(STYLE_ATTRIBUTE.matcher(html).find()).as("style attribute on %s", name).isFalse();
            assertThat(STYLE_BLOCK.matcher(html).find()).as("style block on %s", name).isFalse();
            assertThat(html).as("old markup on %s", name)
                    .doesNotContain("\u2014")
                    .doesNotContain("&mdash;")
                    .doesNotContain("site(s)")
                    .doesNotContain("alert-warning")
                    .doesNotContain("class=\"card")
                    .doesNotContain("site-nav-button")
                    .doesNotContain("bootstrap.bundle")
                    .doesNotContain(" th:")
                    .contains("class=\"bg-page\"");
        });
        assertThat(RESULTS_SCRIPT.matcher(pages.get("results with sites")).find()).isTrue();
        assertThat(pages.get("results with sites")).doesNotContain("Link copied!");
    }

    @Test
    void resultsPage_showsSummaryRowAndPutsTheMapAfterTheSiteList() throws Exception {
        String inside = page(results(39.825, -84.055, "1 Main St, Dayton, OH", "OH", null), 200);
        assertThat(inside).contains("<dt>Active sites within 3 miles</dt>")
                .contains("<dt>Nearest site boundary</dt><dd>Inside</dd>")
                .contains("<dt>Exposure not under control</dt><dd class=\"tt-alert-text\">1</dd>")
                .contains("<span class=\"sp-site-distance is-inside\">Inside the EPA site boundary</span>")
                .contains("1 active Superfund site found within 3 miles");

        // Map data travels in data- attributes for /js/results.js; the map column comes after the list,
        // so on phones the map sits below the sites.
        assertThat(inside).contains("id=\"results-map\"")
                .contains("data-geojson=\"{&quot;type&quot;:&quot;FeatureCollection&quot;")
                .contains("data-lat=\"39.825\"")
                .doesNotContain("role=\"img\"");
        // The map sits in a figure whose caption is the text equivalent; Mapbox's own canvas and
        // attribution links inside the map element stay exposed to assistive technology.
        assertThat(inside).containsPattern("<figure class=\"m-0\">\\s*<div id=\"results-map\"")
                .containsPattern("</div>\\s*<figcaption class=\"tt-mini-note sp-map-caption mb-0\">Boundaries are colored");
        assertThat(page(get("/js/results.js"), 200))
                .contains("map.getCanvas().setAttribute('aria-label', MAP_LABEL);");
        assertThat(inside.indexOf("class=\"sp-site-list\"")).isPositive()
                .isLessThan(inside.indexOf("id=\"results-map\""));

        // The state's no-boundary site is listed in its own section.
        assertThat(inside).contains("Active sites in this state with no published boundary")
                .contains("href=\"/site/OHR000000002\"");

        String none = page(results(38.5, -116.5, "Somewhere, NV", "NV", null), 200);
        assertThat(none).contains("No active EPA Superfund sites found within 3 miles of this address.")
                .contains("0 active Superfund sites found within 3 miles")
                .doesNotContain("tt-kpi-strip")
                .doesNotContain("id=\"results-map\"");
    }

    @Test
    void sitePages_useReportLayoutWithLabelledLookupAndNoBoundaryNotice() throws Exception {
        String site = page(get("/site/OHR000000001"), 200);
        assertThat(site).contains("<h1 class=\"tt-page-title mb-3\">Render Test Landfill</h1>")
                .contains("for=\"site-lookup-address\"")
                .contains("id=\"site-lookup-address\"")
                .contains("<span class=\"tt-fact-value\">HENC</span>")
                .contains("href=\"/state/OH\"")
                .doesNotContain("No published boundary");

        String unmapped = page(get("/site/OHR000000002"), 200);
        assertThat(unmapped).contains("<span class=\"sp-notice-title\">No published boundary</span>")
                .contains("EPA has not published a boundary for this site, so Site Plain cannot calculate how far an address is from it")
                .doesNotContain("site-lookup-address");
    }

    @Test
    void statePage_usesRuledTablesSummaryRowAndLegend() throws Exception {
        String state = page(get("/state/OH"), 200);
        assertThat(state).contains("tt-record-table")
                .contains("class=\"sp-status-legend\"")
                .contains("<dt>Not under control</dt><dd class=\"tt-alert-text\">")
                .contains("Sites with a published boundary")
                .contains("Active sites with no published boundary")
                .contains("for=\"state-lookup-address\"")
                .contains("<div class=\"tt-cell-detail\">OHR000000002</div>");
    }

    @Test
    void siteSearch_listsMatchesAsDirectoryRowsWithStatusSquares() throws Exception {
        String search = page(get("/search/sites").param("q", "render test"), 200);
        assertThat(search).contains("class=\"tt-directory-item\" href=\"/site/OHR000000001\"")
                .contains("<span class=\"sp-status sp-status-red\">Human exposure is NOT currently under control</span>")
                .contains("for=\"site-search-q\"");

        String none = page(get("/search/sites").param("q", "zzqx no such site"), 200);
        assertThat(none).contains("No matching site found").contains("Try an address lookup instead");
    }

    @Test
    void resultsPage_putsTheFeedbackFormInTheReportGridAfterTheSideColumn() throws Exception {
        String html = page(results(39.825, -84.055, "1 Main St, Dayton, OH", "OH", null), 200);
        int layout = html.indexOf("<div class=\"row g-4 g-xl-5 sp-report-layout\">");
        int main = html.indexOf("<div class=\"col-xl-8 sp-report-main\">");
        int side = html.indexOf("<div class=\"col-xl-4 align-self-start sp-report-side\">");
        int feedback = html.indexOf("<div class=\"col-xl-8 sp-report-feedback\">");
        int form = html.indexOf("id=\"feedback\"");
        // One grid with the report's gutters, in phone order: list, side column (map, sources), form.
        assertThat(layout).isPositive();
        assertThat(main).isGreaterThan(layout).isLessThan(side);
        assertThat(side).isLessThan(feedback);
        assertThat(feedback).isLessThan(form);
        assertThat(html.substring(side, form)).doesNotContain("<div class=\"row\">");

        String css = page(get("/css/site-plain.css"), 200);
        assertThat(css).contains("grid-row: 1 / span 2;").contains(".sp-report-layout > .sp-report-feedback {");
    }

    @Test
    void resultsMetaLine_saysDistancesAreToTheBoundaryOnlyWhenThereAreSites() throws Exception {
        String sites = page(results(39.825, -84.055, "1 Main St, Dayton, OH", "OH", null), 200);
        assertThat(sites).contains("<li>Distances are to the EPA site boundary</li>")
                .doesNotContain("Distance to the EPA site boundary");

        String none = page(results(38.5, -116.5, "Somewhere, NV", "NV", null), 200);
        assertThat(none).doesNotContain("Distances are to the EPA site boundary")
                .doesNotContain("Distance to the EPA site boundary");
    }

    @Test
    void noBoundaryNotice_linksTheEpaProfileItself() throws Exception {
        String unmapped = page(get("/site/OHR000000002"), 200);
        int notice = unmapped.indexOf("<span class=\"sp-notice-title\">No published boundary</span>");
        String noticeHtml = unmapped.substring(notice, unmapped.indexOf("</div>", notice));
        assertThat(noticeHtml).contains("<a href=\"https://www.epa.gov/superfund/render-test-unmapped\" target=\"_blank\" rel=\"noopener\">View EPA site profile</a>")
                .doesNotContain("below");
    }

    @Test
    void siteSearchWithoutMatch_givesTheAddressLinkATapTarget() throws Exception {
        String none = page(get("/search/sites").param("q", "zzqx no such site"), 200);
        assertThat(none).contains("<a class=\"tt-card-link sp-tap-link\" href=\"/\">Try an address lookup instead</a>");
        assertThat(page(get("/css/site-plain.css"), 200)).containsPattern(
                "\\.sp-tap-link \\{\\s*align-items: center;\\s*display: inline-flex;\\s*min-height: 2\\.75rem;");
    }

    @Test
    void homeAndAbout_linkEachDataSourceWithItsLoadDate() throws Exception {
        for (String path : List.of("/", "/about")) {
            String html = page(get(path), 200).replaceAll("\\s+", " ");
            assertThat(html).as(path)
                    .containsPattern("<a href=\"https://edg\\.epa\\.gov/metadata/catalog/search/resource/details\\.page\\?uuid=%7BFC07D75C-8596-434B-B1A6-0688C9CD45B5%7D\" "
                            + "target=\"_blank\" rel=\"noopener\">EPA NPL Superfund Site Boundaries</a><span class=\"tt-source-date\">loaded [A-Z][a-z]+ \\d{1,2}, \\d{4}</span>")
                    .contains("<a href=\"https://www.epa.gov/superfund/superfund-human-exposure-dashboard\" target=\"_blank\" rel=\"noopener\">"
                            + "EPA Superfund Human Exposure list</a><span class=\"tt-source-date\">loaded October 10, 2026</span>")
                    .contains("<a href=\"https://geocoding.geo.census.gov/geocoder/\" target=\"_blank\" rel=\"noopener\">U.S. Census Geocoder</a>")
                    .contains("<a href=\"https://nominatim.org/\" target=\"_blank\" rel=\"noopener\">OpenStreetMap Nominatim</a>");
        }
    }

    @Test
    void stylesheet_dropsTheTabFocusHaloAlignsSummaryValuesAndTheHomeRule() throws Exception {
        String css = page(get("/css/site-plain.css"), 200);
        assertThat(css).containsPattern("\\.tt-segmented \\.nav-link:focus,\\s*\\.tt-segmented \\.nav-link:focus-visible \\{\\s*box-shadow: none;")
                .containsPattern("\\.tt-kpi-item \\{[^}]*display: flex;\\s*flex-direction: column;")
                .containsPattern("\\.tt-kpi-strip dd \\{[^}]*margin: auto 0 0;")
                .contains("min(calc(100% - var(--bs-gutter-x)), 50rem) 2px no-repeat;");
    }

    /** GET /results with an encrypted link, as POST /lookup would have issued it. */
    private MockHttpServletRequestBuilder results(double lat, double lng, String label, String state, String approx) {
        return get("/results").param("r", resultsTokens.encode(
                new ResultsLink(label, label, lat, lng, state, "census", approx, 1_791_600_000L)));
    }

    private String page(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
                        int expectedStatus) throws Exception {
        return mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private int auditRowCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM lookup_audit", Integer.class);
        return count == null ? 0 : count;
    }

    private void insertBoundary(String epaId, String siteName, String epaUrl) {
        jdbcTemplate.update("""
                INSERT INTO npl_site_boundaries
                  (epa_id, site_name, state_code, npl_status_code, epa_url, geom)
                VALUES
                  (?, ?, 'OH', 'F', ?, ST_Multi(ST_GeomFromText(
                    'POLYGON((-84.06 39.82, -84.05 39.82, -84.05 39.83, -84.06 39.83, -84.06 39.82))',
                    4326
                  )))
                """,
                epaId, siteName, epaUrl);
    }

    private void insertExposure(String epaId, String statusCode, String stateCode, String epaUrl) {
        jdbcTemplate.update("""
                INSERT INTO npl_human_exposure
                  (epa_id, humexposurestscode, npl_status, site_name, state_code, epa_url, loaded_at)
                VALUES (?, ?, 'F', ?, ?, ?, TIMESTAMP '2026-10-10 06:30:00')
                """,
                epaId, statusCode, "UNMAPPED RENDER TEST SITE", stateCode, epaUrl);
    }
}

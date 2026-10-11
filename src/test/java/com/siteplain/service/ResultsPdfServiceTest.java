package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.siteplain.config.SiteProperties;
import com.siteplain.domain.model.NplSite;
import com.siteplain.domain.view.ResultsViewModel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class ResultsPdfServiceTest {

    @Test
    void generateResultsPdf_returnsPdfBytes() {
        ResultsPdfService service = new ResultsPdfService(templateEngine(), siteProperties());
        ResultsViewModel viewModel = viewModel(null);

        byte[] pdf = service.generateResultsPdf(viewModel);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(1_000);
    }

    @Test
    void pdfTemplate_showsExposureAsOfDateAndApproximateNotice() {
        Context context = new Context(Locale.US);
        context.setVariable("viewModel", viewModel("city"));
        String html = templateEngine().process("results-pdf", context);

        assertThat(html).contains("<td>EPA exposure status as of</td>")
                .contains("<td>October 10, 2026</td>")
                .contains("<p class=\"notice-title\">Approximate location</p>");
        assertThat(html.replaceAll("\\s+", " ")).contains("only to the following <span>city</span>: "
                + "<span>57 Salem Street, Woburn, MA 01801</span>.");

        context.setVariable("viewModel", viewModel(null));
        assertThat(templateEngine().process("results-pdf", context)).doesNotContain("Approximate location");
    }

    @Test
    void pdfTemplate_usesTheRestrainedStatusTreatmentAndPluralizesTheCount() {
        Context context = new Context(Locale.US);
        context.setVariable("viewModel", viewModel(null));
        context.setVariable("generatedOn", "October 10, 2026");
        String html = templateEngine().process("results-pdf", context);

        assertThat(html).contains("1 active Superfund site found within 3 miles")
                .doesNotContain("site(s)")
                // HENC: red square and red text, the only alarm color.
                .contains("<span class=\"swatch swatch-red\"></span><span>Human exposure is NOT currently under control</span>")
                .contains("<p class=\"status status-red\">")
                .contains("<td class=\"value alert-text\">1</td>")
                .contains("<td class=\"value\">0.3 mi</td>")
                .contains("Site Plain | site-plain.com | Generated <span>October 10, 2026</span>")
                .doesNotContain("border-radius")
                .doesNotContain("text-transform: uppercase")
                .doesNotContain("\u2014");
    }

    @Test
    void pdfTemplate_escapesEpaPathwayTextAndKeepsOnlyLineBreaks() {
        NplSite site = sampleSite();
        site.setExposurePathwayDescription("Groundwater<br/>Soil <b>vapor</b> & <img src=x onerror=alert(1)>");
        Context context = new Context(Locale.US);
        context.setVariable("viewModel", viewModel(null, site));
        String html = templateEngine().process("results-pdf", context);

        assertThat(html).contains("<span>Groundwater<br/>Soil &lt;b&gt;vapor&lt;/b&gt; &amp; &lt;img src=x onerror=alert(1)&gt;</span>")
                .doesNotContain("<b>vapor")
                .doesNotContain("<img");
        // The escaped text still renders as a PDF.
        assertThat(new ResultsPdfService(templateEngine(), siteProperties()).generateResultsPdf(viewModel(null, site)))
                .startsWith("%PDF-".getBytes(StandardCharsets.ISO_8859_1));
    }

    private static ResultsViewModel viewModel(String approximateMatch) {
        return viewModel(approximateMatch, sampleSite());
    }

    private static ResultsViewModel viewModel(String approximateMatch, NplSite site) {
        return new ResultsViewModel(
                "57 Salem Street, Woburn, MA 01801",
                "57 Salem Street, Woburn, MA 01801",
                false,
                true,
                1,
                List.of(site),
                null,
                "MA",
                42.48,
                -71.15,
                null,
                null,
                1337,
                List.of(),
                15,
                approximateMatch,
                "October 10, 2026"
        );
    }

    private static SpringTemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode("HTML");
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static SiteProperties siteProperties() {
        SiteProperties props = new SiteProperties();
        props.setBaseUrl("https://site-plain.com");
        return props;
    }

    private static NplSite sampleSite() {
        NplSite site = new NplSite();
        site.setEpaId("TEST001");
        site.setSiteName("Test Site");
        site.setStateCode("MA");
        site.setDistanceMeters(500.0);
        site.setExposureStatusCode("HENC");
        site.setExposureStatusLabel("Human exposure is NOT currently under control");
        site.setExposureStatusColor("red");
        site.setExposurePathwayDescription("EPA says exposure is not under control.");
        site.setEpaUrl("https://example.com/site/TEST001");
        return site;
    }
}

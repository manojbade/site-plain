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
                .contains("Approximate location.")
                .contains("only to the following\n      <span>city</span>");

        context.setVariable("viewModel", viewModel(null));
        assertThat(templateEngine().process("results-pdf", context)).doesNotContain("Approximate location.");
    }

    private static ResultsViewModel viewModel(String approximateMatch) {
        return new ResultsViewModel(
                "57 Salem Street, Woburn, MA 01801",
                "57 Salem Street, Woburn, MA 01801",
                false,
                true,
                1,
                List.of(sampleSite()),
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

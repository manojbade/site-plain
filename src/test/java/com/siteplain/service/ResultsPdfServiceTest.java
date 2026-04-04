package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.siteplain.config.SiteProperties;
import com.siteplain.domain.model.NplSite;
import com.siteplain.domain.view.ResultsViewModel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class ResultsPdfServiceTest {

    @Test
    void generateResultsPdf_returnsPdfBytes() {
        ResultsPdfService service = new ResultsPdfService(templateEngine(), siteProperties());
        ResultsViewModel viewModel = new ResultsViewModel(
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
                null
        );

        byte[] pdf = service.generateResultsPdf(viewModel);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(pdf.length).isGreaterThan(1_000);
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

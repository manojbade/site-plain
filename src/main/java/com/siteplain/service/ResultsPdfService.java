package com.siteplain.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.siteplain.config.SiteProperties;
import com.siteplain.domain.view.ResultsViewModel;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

@Service
public class ResultsPdfService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US);

    private final SpringTemplateEngine templateEngine;
    private final SiteProperties siteProperties;

    public ResultsPdfService(SpringTemplateEngine templateEngine, SiteProperties siteProperties) {
        this.templateEngine = templateEngine;
        this.siteProperties = siteProperties;
    }

    public byte[] generateResultsPdf(ResultsViewModel viewModel) {
        Context context = new Context(Locale.US);
        context.setVariable("viewModel", viewModel);
        context.setVariable("generatedOn", LocalDate.now().format(DATE_FORMATTER));
        context.setVariable("siteBaseUrl", siteProperties.getBaseUrl());

        String html = templateEngine.process("results-pdf", context);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withHtmlContent(html, siteProperties.getBaseUrl());
            builder.toStream(outputStream);
            builder.run();
            return outputStream.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to generate results PDF", ex);
        }
    }
}

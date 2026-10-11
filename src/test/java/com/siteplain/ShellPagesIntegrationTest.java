package com.siteplain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Renders the shell pages (home, about, feedback, thank-you, error pages) and checks the markup
 * a strict Content-Security-Policy and the shared design depend on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class ShellPagesIntegrationTest {

    /** A script tag without a src attribute, that is, an inline script block. */
    private static final Pattern INLINE_SCRIPT = Pattern.compile("<script(?![^>]*\\bsrc=)[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_ATTRIBUTE = Pattern.compile("\\sstyle\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_BLOCK = Pattern.compile("<style[\\s>]", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANALYTICS_SCRIPT = Pattern.compile("<script src=\"/js/analytics(-[0-9a-f]{32})?\\.js\"></script>");
    private static final Pattern HOME_SCRIPT = Pattern.compile("<script src=\"/js/home(-[0-9a-f]{32})?\\.js\"></script>");
    private static final Pattern STYLESHEET = Pattern.compile("href=\"/css/site-plain(-[0-9a-f]{32})?\\.css\\?v=3\"");
    private static final String COMMENT_MARKER = "shell-pages-test comment 7f3c";
    private static final String EPA_POPULATION_PDF =
            "https://www.epa.gov/system/files/documents/2023-08/FY22%20Population%20Estimates%20Superfund%20Final.pdf";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM feedback WHERE comments = ?", COMMENT_MARKER);
    }

    @Test
    void shellPages_haveNoInlineScriptsStylesOrEmDashes() throws Exception {
        Map<String, String> pages = Map.of(
                "/", page("/", 200),
                "/about", page("/about", 200),
                "/feedback", page("/feedback", 200),
                "/feedback/thanks", page("/feedback/thanks", 200),
                "/no-such-page", page("/no-such-page", 404),
                "500", serverErrorPage());
        pages.forEach((path, html) -> {
            assertThat(INLINE_SCRIPT.matcher(html).find()).as("inline script on %s", path).isFalse();
            assertThat(STYLE_ATTRIBUTE.matcher(html).find()).as("style attribute on %s", path).isFalse();
            assertThat(STYLE_BLOCK.matcher(html).find()).as("style block on %s", path).isFalse();
            assertThat(html).as("em dash on %s", path).doesNotContain("\u2014").doesNotContain("&mdash;");
            assertThat(ANALYTICS_SCRIPT.matcher(html).find()).as("analytics script on %s", path).isTrue();
            assertThat(STYLESHEET.matcher(html).find()).as("stylesheet on %s", path).isTrue();
            assertThat(html).as("shared assets on %s", path)
                    .contains("integrity=\"sha384-QWTKZyjpPEjISv5WaRU9OFeRpok6YctnYmDr5pNlyT2bRjXh0JMhjY6hW+ALEwIH\"")
                    .doesNotContain("bootstrap.bundle")
                    .contains("class=\"bg-page\"");
            assertThat(html).as("header feedback link on %s", path)
                    .contains("class=\"tt-nav-button tt-btn-secondary\" href=\"/feedback\"")
                    .doesNotContain("href=\"/#feedback\"");
        });
    }

    @Test
    void home_leadsWithTheEpaFigureAndLabelsSitePlainNumbersAsOwnAnalysis() throws Exception {
        String html = page("/", 200);
        int epaFigure = html.indexOf("Approximately 23 million people live within 1 mile of a Superfund site");
        int ownFigure = html.indexOf("19.4 million Americans live within 1 mile of an active Superfund site.");
        assertThat(epaFigure).isPositive();
        assertThat(ownFigure).isGreaterThan(epaFigure);
        assertThat(html).contains("href=\"" + EPA_POPULATION_PDF + "\"")
                .contains("Population Surrounding 1,881 Superfund Sites</a>, updated July 2023.")
                .contains("357 congressional districts, counting DC and territorial delegate districts,")
                .doesNotContain("of 435")
                .contains("Source: Site Plain's own analysis, March 2026.")
                .contains("href=\"/about#methodology\"")
                .contains("<label class=\"form-label mb-2 tt-primary-input-label\" for=\"lookup-address\">")
                .contains("<label class=\"form-label mb-2 tt-primary-input-label\" for=\"site-search-query\">");
        assertThat(HOME_SCRIPT.matcher(html).find()).isTrue();
        assertThat(page("/about", 200)).contains("id=\"methodology\"");
    }

    @Test
    void feedbackPage_rendersTheFormWithContext() throws Exception {
        String general = page("/feedback", 200);
        assertThat(general).contains("action=\"/feedback\" method=\"post\"")
                .contains("<input type=\"hidden\" name=\"pageType\" value=\"general\">")
                .doesNotContain("name=\"epaId\"")
                .contains("name=\"comments\"")
                .contains("name=\"helpful\"")
                .contains("<label class=\"form-label tt-field-label mb-2\" for=\"feedback-message\">Message</label>");

        String fromSite = page("/feedback?pageType=site&epaId=ak1570028646", 200);
        assertThat(fromSite).contains("name=\"pageType\" value=\"site\"")
                .contains("name=\"epaId\" value=\"AK1570028646\"");

        String odd = page("/feedback?pageType=something-much-too-long&epaId=%3Cscript%3E", 200);
        assertThat(odd).contains("name=\"pageType\" value=\"general\"").doesNotContain("name=\"epaId\"");
    }

    @Test
    void feedbackPost_savesTheCommentAndShowsTheThankYouPage() throws Exception {
        mockMvc.perform(post("/feedback")
                        .param("pageType", "general")
                        .param("helpful", "true")
                        .param("comments", COMMENT_MARKER))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/feedback/thanks"));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT page_type, helpful FROM feedback WHERE comments = ?", COMMENT_MARKER);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("page_type", "general").containsEntry("helpful", true);

        String thanks = page("/feedback/thanks", 200);
        assertThat(thanks).contains("<h1 class=\"h2 mb-3\">Thank you for your feedback.</h1>")
                .contains("class=\"tt-masthead\"")
                .contains("class=\"tt-footer\"");
    }

    @Test
    void errorPages_sayNotFoundOnlyForMissingPages() throws Exception {
        String missing = page("/no-such-page", 404);
        assertThat(missing).contains("Page not found").contains("<p class=\"eyebrow mb-2\">404</p>");

        String serverError = serverErrorPage();
        assertThat(serverError).doesNotContain("Page not found")
                .doesNotContain("Not Found")
                .contains("Site Plain could not finish this request");
    }

    private String page(String path, int expectedStatus) throws Exception {
        return mockMvc.perform(get(path))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    /** A POST without the required pageType reaches the catch-all handler, which renders the server error page. */
    private String serverErrorPage() throws Exception {
        return mockMvc.perform(post("/feedback"))
                .andExpect(status().isInternalServerError())
                .andReturn().getResponse().getContentAsString();
    }
}

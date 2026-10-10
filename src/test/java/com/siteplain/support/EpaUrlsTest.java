package com.siteplain.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.siteplain.domain.model.NplSite;
import com.siteplain.domain.model.SeoPageData;
import org.junit.jupiter.api.Test;

class EpaUrlsTest {

    private static final String SEARCH = "https://cumulis.epa.gov/supercpad/cursites/srchsites.cfm?search_string=";

    @Test
    void normalize_addsSchemeToSchemelessEpaUrls() {
        assertThat(EpaUrls.normalize("www.epa.gov/superfund/lowry-landfill"))
                .isEqualTo("https://www.epa.gov/superfund/lowry-landfill");
        assertThat(EpaUrls.normalize("  www.epa.gov/superfund/acm-smelter "))
                .isEqualTo("https://www.epa.gov/superfund/acm-smelter");
    }

    @Test
    void normalize_keepsAbsoluteGovUrls() {
        assertThat(EpaUrls.normalize("https://www.epa.gov/superfund/acm-smelter"))
                .isEqualTo("https://www.epa.gov/superfund/acm-smelter");
        assertThat(EpaUrls.normalize("http://cumulis.epa.gov/supercpad/cursites/csitinfo.cfm?id=0800263"))
                .isEqualTo("http://cumulis.epa.gov/supercpad/cursites/csitinfo.cfm?id=0800263");
        assertThat(EpaUrls.normalize("https://www.oregon.gov/deq/page")).isEqualTo("https://www.oregon.gov/deq/page");
    }

    @Test
    void normalize_dropsMissingNonGovAndNonWebUrls() {
        assertThat(EpaUrls.normalize(null)).isNull();
        assertThat(EpaUrls.normalize(" ")).isNull();
        assertThat(EpaUrls.normalize("example.com/site")).isNull();
        assertThat(EpaUrls.normalize("https://semspub.epa.src/work/08/100002345.pdf")).isNull();
        assertThat(EpaUrls.normalize("javascript:alert(1)")).isNull();
        assertThat(EpaUrls.normalize("ftp://www.epa.gov/file")).isNull();
        assertThat(EpaUrls.normalize("/superfund/lowry-landfill")).isNull();
        assertThat(EpaUrls.normalize("https://user@www.epa.gov/superfund")).isNull();
    }

    @Test
    void viewModels_neverRenderSchemelessLinks() {
        NplSite site = new NplSite();
        site.setEpaId("COD980499248");
        site.setEpaUrl("www.epa.gov/superfund/lowry-landfill");
        assertThat(site.effectiveEpaUrl()).isEqualTo("https://www.epa.gov/superfund/lowry-landfill");

        site.setEpaUrl("https://semspub.epa.src/x");
        assertThat(site.effectiveEpaUrl()).isEqualTo(SEARCH + "COD980499248");

        SeoPageData page = new SeoPageData("WY5571924179", "F. E. WARREN AIR FORCE BASE", "WY", "HEUC", "label", null,
                "www.epa.gov/superfund/warren-air-force-base", true);
        assertThat(page.effectiveEpaUrl()).isEqualTo("https://www.epa.gov/superfund/warren-air-force-base");

        SeoPageData noUrl = new SeoPageData("MTD093291599", "ACM SMELTER", "MT", null, "label", null, null, true);
        assertThat(noUrl.effectiveEpaUrl()).isEqualTo(SEARCH + "MTD093291599");
    }
}

package com.siteplain;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Boots the real application and probes it from inside an ApplicationRunner, which runs at the same
 * point as the startup data refresh: Tomcat is already serving, but readiness must still be down.
 */
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class ReadinessProbeIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void readinessIsDownWhileStartupRunnersRun_andUpAfter_withOnlyHealthExposed() throws Exception {
        AtomicInteger port = new AtomicInteger();
        Map<String, HttpResponse<String>> duringStartup = new ConcurrentHashMap<>();
        SpringApplication application = new SpringApplication(SitePlainApplication.class);
        application.addListeners((ApplicationListener<ApplicationEvent>) event -> {
            if (event instanceof WebServerInitializedEvent initialized) {
                port.set(initialized.getWebServer().getPort());
            }
        });
        ApplicationRunner probe = args -> {
            duringStartup.put("readiness", get(port.get(), "/actuator/health/readiness", false));
            duringStartup.put("liveness", get(port.get(), "/actuator/health/liveness", false));
            duringStartup.put("health", get(port.get(), "/actuator/health", true));
        };
        application.addInitializers(context -> context.getBeanFactory().registerSingleton("startupProbe", probe));

        try (ConfigurableApplicationContext context = application.run(
                "--spring.profiles.active=test",
                "--server.port=0",
                // Like prod: canonical host enforced behind a proxy. The probes are called without the
                // forwarded headers, as Railway's healthcheck does.
                "--siteplain.site.enforceCanonicalHost=true",
                "--server.forward-headers-strategy=framework",
                "--management.endpoint.health.group.readiness.show-components=always")) {

            assertThat(duringStartup.get("readiness").statusCode()).isEqualTo(503);
            assertThat(duringStartup.get("readiness").body()).contains("\"status\":\"OUT_OF_SERVICE\"");
            assertThat(duringStartup.get("liveness").statusCode()).isEqualTo(200);
            // The aggregate endpoint is not gated by readiness: UP as before (it now also lists the groups).
            assertThat(duringStartup.get("health").statusCode()).isEqualTo(200);
            assertThat(duringStartup.get("health").body()).startsWith("{\"status\":\"UP\"");

            HttpResponse<String> ready = get(port.get(), "/actuator/health/readiness", false);
            assertThat(ready.statusCode()).isEqualTo(200);
            assertThat(ready.body()).contains("\"status\":\"UP\"").contains("\"db\"").contains("\"readinessState\"");
            assertThat(get(port.get(), "/actuator/health/liveness", false).statusCode()).isEqualTo(200);

            HttpResponse<String> health = get(port.get(), "/actuator/health", true);
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).doesNotContain("\"components\"");

            HttpResponse<String> discovery = get(port.get(), "/actuator", true);
            assertThat(discovery.statusCode()).isEqualTo(200);
            assertThat(discovery.body()).contains("/actuator/health").doesNotContain("/actuator/env")
                    .doesNotContain("/actuator/info").doesNotContain("/actuator/beans");
            for (String path : new String[] {"/actuator/env", "/actuator/info", "/actuator/beans",
                    "/actuator/configprops", "/actuator/heapdump", "/actuator/loggers"}) {
                assertThat(get(port.get(), path, true).statusCode()).as(path).isEqualTo(404);
            }
        }
    }

    /** canonical=true sends the proxy headers the public site gets, so the canonical-host filter lets it through. */
    private HttpResponse<String> get(int port, String path, boolean canonical) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (canonical) {
            request.header("X-Forwarded-Host", "site-plain.com").header("X-Forwarded-Proto", "https");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

package com.siteplain.data.loader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siteplain.config.HttpClientConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceDownloaderTest {

    private static final String BODY = "{\"type\":\"FeatureCollection\",\"features\":[]}";
    private static final Duration TIMEOUT = Duration.ofSeconds(1);

    @TempDir
    Path tempDir;

    private HttpServer server;
    private SourceDownloader downloader;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/release", exchange -> {
            exchange.getResponseHeaders().add("Location", url("/cdn/file.geojson"));
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/cdn/file.geojson", exchange -> {
            exchange.getResponseHeaders().add("Last-Modified", "Sat, 10 Oct 2026 18:01:01 GMT");
            send(exchange, 200, "application/octet-stream", BODY);
        });
        server.createContext("/missing", exchange -> send(exchange, 404, "text/plain", "Not Found"));
        server.createContext("/html-type", exchange -> send(exchange, 200, "text/html; charset=utf-8", "<html></html>"));
        server.createContext("/html-body", exchange -> send(exchange, 200, "application/json", "\n  <!DOCTYPE html><html>"));
        server.createContext("/empty", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/big-declared", exchange -> send(exchange, 200, "application/json", "{" + "x".repeat(1999)));
        server.createContext("/big-chunked", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (int i = 0; i < 20; i++) {
                    out.write(("{" + "x".repeat(99)).getBytes(StandardCharsets.UTF_8));
                }
            }
        });
        server.createContext("/stall-body", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("{\"features\":[".getBytes(StandardCharsets.UTF_8));
            out.flush();
            sleep(10_000);
            exchange.close();
        });
        server.createContext("/stall-headers", exchange -> {
            sleep(10_000);
            send(exchange, 200, "application/json", BODY);
        });
        server.start();
        downloader = new SourceDownloader(new HttpClientConfig().httpClient(), tempDir);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void followsRedirects_andRecordsSizeShaAndLastModified() throws Exception {
        DownloadedFile file = downloader.download(url("/release"), "Boundary file", TIMEOUT, 1000);
        try (file) {
            assertThat(Files.readString(file.path())).isEqualTo(BODY);
            assertThat(file.bytes()).isEqualTo(BODY.length());
            assertThat(file.sha256()).isEqualTo(sha256(BODY));
            assertThat(file.lastModified()).isEqualTo(Instant.parse("2026-10-10T18:01:01Z"));
            assertThat(file.sourceUrl()).isEqualTo(url("/release"));
        }
        assertThat(file.path()).doesNotExist();
    }

    @Test
    void rejectsNon2xxStatus() {
        assertThatThrownBy(() -> downloader.download(url("/missing"), "Boundary file", TIMEOUT, 1000))
                .hasMessageContaining("HTTP 404 from " + url("/missing"));
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void rejectsHtmlErrorPages() {
        assertThatThrownBy(() -> downloader.download(url("/html-type"), "Exposure feed", TIMEOUT, 1000))
                .hasMessageContaining("returned an HTML page (Content-Type text/html");
        assertThatThrownBy(() -> downloader.download(url("/html-body"), "Exposure feed", TIMEOUT, 1000))
                .hasMessageContaining("returned an HTML or XML page, not JSON");
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void rejectsAnEmptyBody() {
        assertThatThrownBy(() -> downloader.download(url("/empty"), "Exposure feed", TIMEOUT, 1000))
                .hasMessageContaining("returned an empty body");
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void rejectsBodiesOverTheSizeCap_declaredOrStreamed() {
        assertThatThrownBy(() -> downloader.download(url("/big-declared"), "Exposure feed", TIMEOUT, 1000))
                .hasMessageContaining("declares 2000 bytes, over the 1000-byte limit");
        assertThatThrownBy(() -> downloader.download(url("/big-chunked"), "Exposure feed", TIMEOUT, 1000))
                .hasMessageContaining("is larger than the 1000-byte limit");
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void timesOutWhenTheBodyStalls() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> downloader.download(url("/stall-body"), "Boundary file", TIMEOUT, 1000))
                .hasMessageContaining("did not finish within 1 s (13 bytes received)");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(tempDir).isEmptyDirectory();
    }

    @Test
    void timesOutWhenNoResponseArrives() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> downloader.download(url("/stall-headers"), "Boundary file", TIMEOUT, 1000))
                .hasMessageContaining("no response from " + url("/stall-headers") + " within 1 s");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void readsFileUrlsAndPlainPaths() throws Exception {
        Path source = Files.writeString(Files.createTempFile("source", ".json"), BODY);
        try (DownloadedFile byUrl = downloader.download(source.toUri().toString(), "Exposure feed", TIMEOUT, 1000);
             DownloadedFile byPath = downloader.download(source.toString(), "Exposure feed", TIMEOUT, 1000)) {
            assertThat(byUrl.sha256()).isEqualTo(sha256(BODY)).isEqualTo(byPath.sha256());
            assertThat(byUrl.lastModified()).isNull();
            assertThat(byUrl.path()).isNotEqualTo(source);
        } finally {
            Files.delete(source);
        }
        assertThatThrownBy(() -> downloader.download("file:///nonexistent/feed.json", "Exposure feed", TIMEOUT, 1000))
                .hasMessageContaining("file not found");
    }

    @Test
    void parseHttpDate_ignoresGarbage() {
        assertThat(SourceDownloader.parseHttpDate("yesterday")).isNull();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sha256(String body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    }
}

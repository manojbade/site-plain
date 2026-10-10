package com.siteplain.data.loader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Downloads a source file (http, https or file URL, or a plain path) to a temp file. Redirects are
 * followed by the shared HttpClient (GitHub release assets answer 302 to a CDN). The whole download,
 * headers and body, must finish within the timeout, the body must stay under the size cap, the
 * status must be 2xx, and the body must start like a JSON object, so an HTML error page or an empty
 * body is rejected before anything is parsed. The SHA-256 is computed while streaming.
 */
@Component
public class SourceDownloader {

    private static final Logger log = LoggerFactory.getLogger(SourceDownloader.class);
    private static final String USER_AGENT = "site-plain-loader/0.0.1";
    private static final int SNIFF_BYTES = 1024;

    private final HttpClient httpClient;
    private final Path tempDir;

    @Autowired
    public SourceDownloader(HttpClient httpClient) {
        this(httpClient, Path.of(System.getProperty("java.io.tmpdir")));
    }

    SourceDownloader(HttpClient httpClient, Path tempDir) {
        this.httpClient = httpClient;
        this.tempDir = tempDir;
    }

    public DownloadedFile download(String sourceUrl, String label, Duration timeout, long maxBytes)
            throws IOException, InterruptedException {
        URI uri = toUri(sourceUrl);
        Path temp = Files.createTempFile(tempDir, "siteplain-", ".download");
        boolean kept = false;
        try {
            long started = System.nanoTime();
            Copied copied;
            Instant lastModified = null;
            if ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) {
                HttpResponse<InputStream> response = send(uri, label, sourceUrl, timeout);
                try (InputStream body = response.body()) {
                    checkResponse(response, label, sourceUrl, maxBytes);
                    lastModified = response.headers().firstValue("Last-Modified")
                            .map(SourceDownloader::parseHttpDate)
                            .orElse(null);
                    long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
                    Duration remaining = timeout.minusNanos(System.nanoTime() - started);
                    copied = copy(body, temp, label, sourceUrl, maxBytes, declared, timeout, remaining);
                }
            } else {
                Path source = "file".equalsIgnoreCase(uri.getScheme()) ? Path.of(uri) : Path.of(sourceUrl);
                try (InputStream in = Files.newInputStream(source)) {
                    copied = copy(in, temp, label, sourceUrl, maxBytes, -1, null, null);
                } catch (java.nio.file.NoSuchFileException e) {
                    throw new IOException(label + ": file not found: " + source, e);
                }
            }
            checkLooksLikeJson(temp, copied.bytes(), label, sourceUrl);
            log.info("Downloaded {} from {}: {} bytes in {} ms, sha256 {}, Last-Modified {}",
                    label, sourceUrl, copied.bytes(), (System.nanoTime() - started) / 1_000_000,
                    copied.sha256(), lastModified == null ? "none" : lastModified);
            kept = true;
            return new DownloadedFile(sourceUrl, temp, copied.bytes(), copied.sha256(), lastModified);
        } finally {
            if (!kept) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private HttpResponse<InputStream> send(URI uri, String label, String sourceUrl, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpConnectTimeoutException e) {
            throw new IOException(label + ": could not connect to " + sourceUrl + " within "
                    + seconds(httpClient.connectTimeout().orElse(null)), e);
        } catch (HttpTimeoutException e) {
            throw new IOException(label + ": no response from " + sourceUrl + " within " + seconds(timeout), e);
        } catch (ConnectException e) {
            String what = hasCause(e, UnresolvedAddressException.class, UnknownHostException.class)
                    ? "could not resolve the host of " : "could not connect to ";
            throw new IOException(label + ": " + what + sourceUrl + ": " + summarize(e), e);
        } catch (IOException e) {
            // The JDK client often throws with a null message (a refused connection is a
            // ConnectException caused by a ClosedChannelException), so name the exception chain.
            throw new IOException(label + ": request to " + sourceUrl + " failed: " + summarize(e), e);
        }
    }

    private static void checkResponse(HttpResponse<InputStream> response, String label, String sourceUrl, long maxBytes)
            throws IOException {
        int status = response.statusCode();
        if (status < 200 || status > 299) {
            throw new IOException(label + ": HTTP " + status + " from " + response.uri()
                    + (response.uri().toString().equals(sourceUrl) ? "" : " (redirected from " + sourceUrl + ")"));
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.toLowerCase(Locale.ROOT).startsWith("text/html")) {
            throw new IOException(label + ": " + sourceUrl + " returned an HTML page (Content-Type "
                    + contentType + "), not JSON");
        }
        long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > maxBytes) {
            throw new IOException(label + ": " + sourceUrl + " declares " + declared + " bytes, over the "
                    + maxBytes + "-byte limit");
        }
    }

    /**
     * Streams to the temp file. With a deadline, a watchdog closes the stream when it passes, which
     * ends a read that is blocked on a stalled transfer.
     */
    private Copied copy(InputStream in, Path temp, String label, String sourceUrl, long maxBytes, long declared,
                        Duration timeout, Duration remaining) throws IOException {
        MessageDigest digest = sha256();
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean timedOut = new AtomicBoolean(false);
        if (remaining != null) {
            long delayMillis = Math.max(0, remaining.toMillis());
            CompletableFuture.delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(() -> {
                if (finished.compareAndSet(false, true)) {
                    timedOut.set(true);
                    closeQuietly(in);
                }
            });
        }
        long total = 0;
        boolean overLimit = false;
        IOException failure = null;
        try (OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    overLimit = true;
                    break;
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            failure = e;
        }
        // If the watchdog already fired, the stream was closed under the read: whatever the read did
        // next (an exception or end of stream), the body is incomplete.
        if (!finished.compareAndSet(false, true) && timedOut.get()) {
            throw timeout(label, sourceUrl, timeout, total, failure);
        }
        if (overLimit) {
            throw new IOException(label + ": " + sourceUrl + " is larger than the " + maxBytes + "-byte limit");
        }
        if (failure != null) {
            // A transfer cut off mid-body surfaces as a bare "closed" or similar, so say where it stopped.
            throw new IOException(label + ": download from " + sourceUrl + " failed after " + total
                    + (declared >= 0 ? " of " + declared : "") + " bytes: " + summarize(failure), failure);
        }
        return new Copied(total, HexFormat.of().formatHex(digest.digest()));
    }

    private static IOException timeout(String label, String sourceUrl, Duration timeout, long bytes, Throwable cause) {
        return new IOException(label + ": download from " + sourceUrl + " did not finish within " + seconds(timeout)
                + " (" + bytes + " bytes received)", cause);
    }

    /** Rejects an empty body and anything whose first non-blank character is not '{'. */
    private static void checkLooksLikeJson(Path file, long bytes, String label, String sourceUrl) throws IOException {
        if (bytes == 0) {
            throw new IOException(label + ": " + sourceUrl + " returned an empty body");
        }
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(SNIFF_BYTES);
        }
        int index = 0;
        if (head.length >= 3 && (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF) {
            index = 3;
        }
        while (index < head.length && Character.isWhitespace(head[index])) {
            index++;
        }
        if (index == head.length) {
            throw new IOException(label + ": " + sourceUrl + " returned a blank body");
        }
        char first = (char) (head[index] & 0xFF);
        if (first == '<') {
            throw new IOException(label + ": " + sourceUrl + " returned an HTML or XML page, not JSON");
        }
        if (first != '{') {
            throw new IOException(label + ": " + sourceUrl + " does not start with a JSON object (first character '"
                    + first + "')");
        }
    }

    private static URI toUri(String sourceUrl) {
        try {
            return URI.create(sourceUrl);
        } catch (IllegalArgumentException e) {
            return Path.of(sourceUrl).toUri();
        }
    }

    static Instant parseHttpDate(String value) {
        try {
            return DateTimeFormatter.RFC_1123_DATE_TIME.parse(value.trim(), Instant::from);
        } catch (DateTimeParseException e) {
            log.warn("Ignoring unparseable Last-Modified header '{}'", value);
            return null;
        }
    }

    /**
     * Names an exception and its causes, for messages and data_source_state.last_error, since many
     * JDK network exceptions have a null message: "ConnectException, caused by ClosedChannelException".
     */
    public static String summarize(Throwable error) {
        StringBuilder text = new StringBuilder();
        String previous = null;
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String part = t.getClass().getSimpleName() + (t.getMessage() == null ? "" : " (" + t.getMessage() + ")");
            if (!part.equals(previous)) {
                text.append(text.isEmpty() ? "" : ", caused by ").append(part);
            }
            previous = part;
        }
        return text.toString();
    }

    @SafeVarargs
    private static boolean hasCause(Throwable error, Class<? extends Throwable>... types) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            for (Class<? extends Throwable> type : types) {
                if (type.isInstance(t)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String seconds(Duration timeout) {
        return timeout == null ? "the timeout" : timeout.toSeconds() + " s";
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // The copy loop reports the timeout.
        }
    }

    private record Copied(long bytes, String sha256) {
    }
}

package com.siteplain.data.loader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * A source file saved to a temp file, with the identity of what was downloaded. Closing it deletes
 * the temp file. lastModified is the HTTP Last-Modified header, or null when there was none.
 */
public record DownloadedFile(String sourceUrl, Path path, long bytes, String sha256, Instant lastModified)
        implements AutoCloseable {

    @Override
    public void close() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete temp file " + path, e);
        }
    }
}

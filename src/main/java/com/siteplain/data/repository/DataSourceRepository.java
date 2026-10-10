package com.siteplain.data.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** data_source_state (V7): what each dataset was loaded from, and how the last refresh attempt went. */
@Repository
public class DataSourceRepository {

    public static final String BOUNDARIES = "npl_boundaries";
    public static final String EXPOSURE = "human_exposure";

    private final JdbcTemplate jdbcTemplate;

    public DataSourceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<DataSourceRecord> find(String dataset) {
        List<DataSourceRecord> rows = jdbcTemplate.query("""
                SELECT dataset, source_url, sha256, bytes, last_modified, row_count, final_count,
                       loaded_at, last_checked_at, last_status, last_error
                FROM data_source_state
                WHERE dataset = ?
                """, (rs, rowNum) -> new DataSourceRecord(
                rs.getString("dataset"),
                rs.getString("source_url"),
                rs.getString("sha256"),
                (Long) rs.getObject("bytes"),
                instant(rs, "last_modified"),
                (Integer) rs.getObject("row_count"),
                (Integer) rs.getObject("final_count"),
                instant(rs, "loaded_at"),
                instant(rs, "last_checked_at"),
                rs.getString("last_status"),
                rs.getString("last_error")
        ), dataset);
        return rows.stream().findFirst();
    }

    /** A new source went live: replaces the whole identity. Call inside the swap transaction. */
    public void recordLoaded(String dataset, String sourceUrl, String sha256, long bytes, Instant lastModified,
                             int rowCount, int finalCount) {
        jdbcTemplate.update("""
                INSERT INTO data_source_state
                  (dataset, source_url, sha256, bytes, last_modified, row_count, final_count,
                   loaded_at, last_checked_at, last_status, last_error)
                VALUES (?, ?, ?, ?, ?, ?, ?, now(), now(), 'LOADED', NULL)
                ON CONFLICT (dataset) DO UPDATE SET
                  source_url = EXCLUDED.source_url,
                  sha256 = EXCLUDED.sha256,
                  bytes = EXCLUDED.bytes,
                  last_modified = EXCLUDED.last_modified,
                  row_count = EXCLUDED.row_count,
                  final_count = EXCLUDED.final_count,
                  loaded_at = EXCLUDED.loaded_at,
                  last_checked_at = EXCLUDED.last_checked_at,
                  last_status = EXCLUDED.last_status,
                  last_error = NULL
                """,
                dataset, sourceUrl, sha256, bytes, timestamp(lastModified), rowCount, finalCount);
    }

    /**
     * Live data that predates this table: takes the configured URL as its source without reloading.
     * sha256 and bytes stay null until the next real load; loaded_at is when the live rows were loaded.
     */
    public void recordAdopted(String dataset, String sourceUrl, int rowCount, int finalCount, LocalDateTime liveLoadedAt) {
        jdbcTemplate.update("""
                INSERT INTO data_source_state
                  (dataset, source_url, row_count, final_count, loaded_at, last_checked_at, last_status, last_error)
                VALUES (?, ?, ?, ?, ?, now(), 'ADOPTED', NULL)
                ON CONFLICT (dataset) DO UPDATE SET
                  source_url = EXCLUDED.source_url,
                  row_count = EXCLUDED.row_count,
                  final_count = EXCLUDED.final_count,
                  loaded_at = EXCLUDED.loaded_at,
                  last_checked_at = EXCLUDED.last_checked_at,
                  last_status = EXCLUDED.last_status,
                  last_error = NULL
                """,
                dataset, sourceUrl, rowCount, finalCount,
                liveLoadedAt == null ? null : Timestamp.from(liveLoadedAt.atZone(ZoneId.systemDefault()).toInstant()));
    }

    /** The download matched the live source, so nothing was swapped. */
    public void recordUnchanged(String dataset) {
        jdbcTemplate.update("""
                UPDATE data_source_state
                SET last_checked_at = now(), last_status = 'UNCHANGED', last_error = NULL
                WHERE dataset = ?
                """, dataset);
    }

    /** A refresh attempt failed; the identity columns keep describing the data still live. */
    public void recordFailure(String dataset, String error) {
        jdbcTemplate.update("""
                INSERT INTO data_source_state (dataset, last_checked_at, last_status, last_error)
                VALUES (?, now(), 'FAILED', ?)
                ON CONFLICT (dataset) DO UPDATE SET
                  last_checked_at = EXCLUDED.last_checked_at,
                  last_status = EXCLUDED.last_status,
                  last_error = EXCLUDED.last_error
                """, dataset, error);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public record DataSourceRecord(
            String dataset,
            String sourceUrl,
            String sha256,
            Long bytes,
            Instant lastModified,
            Integer rowCount,
            Integer finalCount,
            Instant loadedAt,
            Instant lastCheckedAt,
            String lastStatus,
            String lastError
    ) {
    }
}

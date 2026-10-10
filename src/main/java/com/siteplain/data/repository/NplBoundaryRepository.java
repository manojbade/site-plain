package com.siteplain.data.repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class NplBoundaryRepository {

    private final JdbcTemplate jdbcTemplate;

    public NplBoundaryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void clearStaging() {
        jdbcTemplate.update("TRUNCATE npl_site_boundaries_staging");
    }

    public int stagingCount() {
        return queryCount("npl_site_boundaries_staging");
    }

    public int liveCount() {
        return queryCount("npl_site_boundaries");
    }

    public boolean liveDataPresent() {
        return liveCount() > 0;
    }

    /**
     * Plain INSERT: the loader rejects a file with duplicate EPA_IDs instead of letting the last one
     * win. ST_Multi stores a Polygon feature as the MultiPolygon the column requires.
     */
    public void insertStaging(String epaId,
                              String siteName,
                              String stateCode,
                              String nplStatusCode,
                              String epaUrl,
                              String geometryJson) {
        jdbcTemplate.update("""
                INSERT INTO npl_site_boundaries_staging
                  (epa_id, site_name, state_code, npl_status_code, epa_url, geom, loaded_at)
                VALUES
                  (?, ?, ?, ?, ?, ST_Multi(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)), ?)
                """,
                epaId, siteName, stateCode, nplStatusCode, epaUrl, geometryJson,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    /**
     * Replaces the live rows with staging. Called only inside the refresh transaction: DELETE, not
     * TRUNCATE, so lookups keep reading the old rows (MVCC) until commit instead of blocking on an
     * ACCESS EXCLUSIVE lock.
     */
    public void replaceLiveFromStaging() {
        jdbcTemplate.update("DELETE FROM npl_site_boundaries");
        jdbcTemplate.update("""
                INSERT INTO npl_site_boundaries (epa_id, site_name, state_code, npl_status_code, epa_url, geom, loaded_at)
                SELECT epa_id, site_name, state_code, npl_status_code, epa_url, geom, loaded_at
                FROM npl_site_boundaries_staging
                """);
    }

    /**
     * Run after the refresh commits. A full replace leaves the planner's row estimates stale, and until
     * autovacuum analyzes the new rows the lookup skips idx_npl_boundaries_geog and runs ST_DWithin
     * on every row (about 0.8 s).
     */
    public void analyzeLive() {
        jdbcTemplate.execute("ANALYZE npl_site_boundaries");
    }

    public int countActiveSites() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM active_npl_sites
                """, Integer.class);
        return count == null ? 0 : count;
    }

    public Optional<LocalDate> lastLoadedDate() {
        List<LocalDate> rows = jdbcTemplate.query("""
                SELECT max(loaded_at)::date AS loaded_date
                FROM npl_site_boundaries
                """, (rs, rowNum) -> rs.getObject("loaded_date", LocalDate.class));
        return rows.stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public Optional<LocalDateTime> lastLoadedAt() {
        List<LocalDateTime> rows = jdbcTemplate.query("""
                SELECT max(loaded_at) AS loaded_at
                FROM npl_site_boundaries
                """, (rs, rowNum) -> {
            Timestamp timestamp = rs.getTimestamp("loaded_at");
            return timestamp == null ? null : timestamp.toLocalDateTime();
        });
        return rows.stream().filter(java.util.Objects::nonNull).findFirst();
    }

    public Map<String, String> findGeoJsonByIds(List<String> epaIds) {
        if (epaIds == null || epaIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = epaIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Map<String, String> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT epa_id, ST_AsGeoJSON(geom) AS geojson FROM npl_site_boundaries WHERE epa_id IN (" + placeholders + ")",
                (RowCallbackHandler) rs -> result.put(rs.getString("epa_id"), rs.getString("geojson")),
                epaIds.toArray()
        );
        return result;
    }

    private int queryCount(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }
}

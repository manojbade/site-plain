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

    public List<String> findAllLiveIds() {
        return jdbcTemplate.query("""
                SELECT epa_id
                FROM npl_site_boundaries
                """, (rs, rowNum) -> rs.getString("epa_id"));
    }

    public void upsertStaging(String epaId,
                              String siteName,
                              String stateCode,
                              String nplStatusCode,
                              String epaUrl,
                              String geometryJson) {
        jdbcTemplate.update("""
                INSERT INTO npl_site_boundaries_staging
                  (epa_id, site_name, state_code, npl_status_code, epa_url, geom, loaded_at)
                VALUES
                  (?, ?, ?, ?, ?, ST_SetSRID(ST_GeomFromGeoJSON(?), 4326), ?)
                ON CONFLICT (epa_id) DO UPDATE SET
                  site_name = EXCLUDED.site_name,
                  state_code = EXCLUDED.state_code,
                  npl_status_code = EXCLUDED.npl_status_code,
                  epa_url = EXCLUDED.epa_url,
                  geom = EXCLUDED.geom,
                  loaded_at = EXCLUDED.loaded_at
                """,
                epaId, siteName, stateCode, nplStatusCode, epaUrl, geometryJson,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    public void swapStagingToLive() {
        jdbcTemplate.update("""
                TRUNCATE site_seo_page_cache,
                         state_page_cache,
                         npl_human_exposure,
                         npl_site_boundaries
                """);
        jdbcTemplate.update("""
                INSERT INTO npl_site_boundaries (epa_id, site_name, state_code, npl_status_code, epa_url, geom, loaded_at)
                SELECT epa_id, site_name, state_code, npl_status_code, epa_url, geom, loaded_at
                FROM npl_site_boundaries_staging
                """);
    }

    public int countActiveSites() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM npl_site_boundaries WHERE npl_status_code = 'F'
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

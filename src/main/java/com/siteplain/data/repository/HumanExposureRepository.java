package com.siteplain.data.repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HumanExposureRepository {

    private final JdbcTemplate jdbcTemplate;

    public HumanExposureRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void clearStaging() {
        jdbcTemplate.update("TRUNCATE npl_human_exposure_staging");
    }

    /** Plain INSERT: the loader rejects a feed with duplicate epaids instead of letting the last one win. */
    public void insertStaging(String epaId,
                              String statusCode,
                              String pathwayDescription,
                              String nplStatus,
                              String siteName,
                              String stateCode,
                              String epaUrl) {
        jdbcTemplate.update("""
                INSERT INTO npl_human_exposure_staging
                  (epa_id, humexposurestscode, humanexposurepathdesc, npl_status, site_name, state_code, epa_url, loaded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                epaId, statusCode, pathwayDescription, nplStatus, siteName, stateCode, epaUrl,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    public int stagingCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM npl_human_exposure_staging", Integer.class);
        return count == null ? 0 : count;
    }

    /** Staging row counts per non-null npl_status value, for the bootstrap gates. */
    public Map<String, Integer> stagingStatusCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT npl_status, count(*) AS row_count
                FROM npl_human_exposure_staging
                WHERE npl_status IS NOT NULL
                GROUP BY npl_status
                ORDER BY npl_status
                """, rs -> {
            counts.put(rs.getString("npl_status"), rs.getInt("row_count"));
        });
        return counts;
    }

    /**
     * How many sites active_npl_sites would hold if staging were swapped in now: the same rule as
     * the view (feed status, else the boundary status, equal to 'F'), read against the staged
     * exposure feed and against staged boundaries when boundaries are being reloaded too.
     */
    public int stagingActiveSiteCount(boolean boundariesStaged) {
        String boundaries = boundariesStaged ? "npl_site_boundaries_staging" : "npl_site_boundaries";
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM %s s
                FULL OUTER JOIN npl_human_exposure_staging h ON h.epa_id = s.epa_id
                WHERE COALESCE(h.npl_status, s.npl_status_code) = 'F'
                """.formatted(boundaries), Integer.class);
        return count == null ? 0 : count;
    }

    /** When the exposure feed now in use was loaded, the as-of date for every exposure status shown. */
    public Optional<LocalDate> lastLoadedDate() {
        List<LocalDate> rows = jdbcTemplate.query("""
                SELECT max(loaded_at)::date AS loaded_date
                FROM npl_human_exposure
                """, (rs, rowNum) -> rs.getObject("loaded_date", LocalDate.class));
        return rows.stream().filter(Objects::nonNull).findFirst();
    }

    /** Replaces the live rows with staging; only inside the refresh transaction (DELETE, not TRUNCATE). */
    public void replaceLiveFromStaging() {
        jdbcTemplate.update("DELETE FROM npl_human_exposure");
        jdbcTemplate.update("""
                INSERT INTO npl_human_exposure (epa_id, humexposurestscode, humanexposurepathdesc, npl_status, site_name, state_code, epa_url, loaded_at)
                SELECT epa_id, humexposurestscode, humanexposurepathdesc, npl_status, site_name, state_code, epa_url, loaded_at
                FROM npl_human_exposure_staging
                """);
    }

    public void analyzeLive() {
        jdbcTemplate.execute("ANALYZE npl_human_exposure");
    }
}

package com.siteplain.data.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * Plain INSERT: the loader rejects a feed with duplicate epaids instead of letting the last one win.
     * loaded_at comes from the database clock (LOCALTIMESTAMP, in the session time zone the driver sets
     * from the JVM), the same clock as the now() recorded in data_source_state when the rows go live.
     * liveMatchesRecord compares the two, so with the app clock a few milliseconds ahead of the
     * database the rows looked newer than their own record and an unchanged feed was staged again.
     */
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
                VALUES (?, ?, ?, ?, ?, ?, ?, LOCALTIMESTAMP)
                """,
                epaId, statusCode, pathwayDescription, nplStatus, siteName, stateCode, epaUrl);
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
     * The sites active_npl_sites would hold after the swap: the same rule as the view (feed status,
     * else the boundary status, equal to 'F'), read against staging for each source that is being
     * replaced and against live for the other. Also counts how many of them have both a boundary and
     * a feed row, which falls toward zero when one source's EPA IDs stop matching the other's.
     */
    public ProjectedActiveSites projectActiveSites(boolean boundariesStaged, boolean exposureStaged) {
        String boundaries = boundariesStaged ? "npl_site_boundaries_staging" : "npl_site_boundaries";
        String exposure = exposureStaged ? "npl_human_exposure_staging" : "npl_human_exposure";
        return jdbcTemplate.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE s.epa_id IS NOT NULL AND h.epa_id IS NOT NULL) AS with_both
                FROM %s s
                FULL OUTER JOIN %s h ON h.epa_id = s.epa_id
                WHERE COALESCE(h.npl_status, s.npl_status_code) = 'F'
                """.formatted(boundaries, exposure),
                (rs, rowNum) -> new ProjectedActiveSites(rs.getInt("total"), rs.getInt("with_both")));
    }

    public int liveCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM npl_human_exposure", Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * True when the live rows are still the ones the refresh recorded: the same row count, and none
     * written after the recorded load. A write by anything else (an older app version that reloads the
     * feed on every boot, manual SQL) breaks this, so the next refresh stages the feed again even
     * when the file is unchanged.
     */
    public boolean liveMatchesRecord(int recordedRowCount, Instant recordedLoadedAt) {
        if (recordedLoadedAt == null) {
            return false;
        }
        Boolean matches = jdbcTemplate.queryForObject("""
                SELECT count(*) = ? AND count(*) > 0 AND max(loaded_at) <= ?
                FROM npl_human_exposure
                """, Boolean.class, recordedRowCount, Timestamp.from(recordedLoadedAt));
        return Boolean.TRUE.equals(matches);
    }

    public record ProjectedActiveSites(int total, int withBoth) {
    }

    /**
     * The "EPA exposure status as of" date: the feed's HTTP Last-Modified (when EPA last changed the
     * file) recorded for the live feed in data_source_state, as a UTC date. When the live feed was
     * loaded without a Last-Modified header, or before data_source_state existed, it falls back to
     * when the live rows were loaded (max loaded_at).
     */
    public Optional<LocalDate> statusAsOfDate() {
        List<LocalDate> rows = jdbcTemplate.query("""
                SELECT COALESCE(
                  (SELECT (last_modified AT TIME ZONE 'UTC')::date
                   FROM data_source_state WHERE dataset = 'human_exposure'),
                  (SELECT max(loaded_at)::date FROM npl_human_exposure)) AS as_of
                """, (rs, rowNum) -> rs.getObject("as_of", LocalDate.class));
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

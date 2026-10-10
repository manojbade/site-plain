package com.siteplain.data.repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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

    public void upsertStaging(String epaId,
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
                ON CONFLICT (epa_id) DO UPDATE SET
                  humexposurestscode = EXCLUDED.humexposurestscode,
                  humanexposurepathdesc = EXCLUDED.humanexposurepathdesc,
                  npl_status = EXCLUDED.npl_status,
                  site_name = EXCLUDED.site_name,
                  state_code = EXCLUDED.state_code,
                  epa_url = EXCLUDED.epa_url,
                  loaded_at = EXCLUDED.loaded_at
                """,
                epaId, statusCode, pathwayDescription, nplStatus, siteName, stateCode, epaUrl,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    public int stagingCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM npl_human_exposure_staging", Integer.class);
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

    public void swapStagingToLive() {
        jdbcTemplate.update("TRUNCATE npl_human_exposure");
        jdbcTemplate.update("""
                INSERT INTO npl_human_exposure (epa_id, humexposurestscode, humanexposurepathdesc, npl_status, site_name, state_code, epa_url, loaded_at)
                SELECT epa_id, humexposurestscode, humanexposurepathdesc, npl_status, site_name, state_code, epa_url, loaded_at
                FROM npl_human_exposure_staging
                """);
    }
}

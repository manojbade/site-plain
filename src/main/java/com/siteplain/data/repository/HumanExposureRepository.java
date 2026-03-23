package com.siteplain.data.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
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
                              String siteName) {
        jdbcTemplate.update("""
                INSERT INTO npl_human_exposure_staging
                  (epa_id, humexposurestscode, humanexposurepathdesc, npl_status, site_name, loaded_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (epa_id) DO UPDATE SET
                  humexposurestscode = EXCLUDED.humexposurestscode,
                  humanexposurepathdesc = EXCLUDED.humanexposurepathdesc,
                  npl_status = EXCLUDED.npl_status,
                  site_name = EXCLUDED.site_name,
                  loaded_at = EXCLUDED.loaded_at
                """,
                epaId, statusCode, pathwayDescription, nplStatus, siteName,
                Timestamp.valueOf(LocalDateTime.now()));
    }

    public int stagingCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM npl_human_exposure_staging", Integer.class);
        return count == null ? 0 : count;
    }

    public void swapStagingToLive() {
        jdbcTemplate.update("TRUNCATE npl_human_exposure");
        jdbcTemplate.update("""
                INSERT INTO npl_human_exposure (epa_id, humexposurestscode, humanexposurepathdesc, npl_status, site_name, loaded_at)
                SELECT epa_id, humexposurestscode, humanexposurepathdesc, npl_status, site_name, loaded_at
                FROM npl_human_exposure_staging
                """);
    }
}

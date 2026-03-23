package com.siteplain.data.repository;

import com.siteplain.domain.model.SeoPageData;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SeoPageRepository {

    private final JdbcTemplate jdbcTemplate;

    public SeoPageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void clearSiteCache() {
        jdbcTemplate.update("TRUNCATE site_seo_page_cache");
    }

    public void insertSiteCacheRow(SeoPageData data) {
        jdbcTemplate.update("""
                INSERT INTO site_seo_page_cache
                  (epa_id, site_name, state_code, exposure_status_code, exposure_status_label,
                   exposure_pathway_desc, epa_url, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                data.epaId(),
                data.siteName(),
                data.stateCode(),
                data.exposureStatusCode(),
                data.exposureStatusLabel(),
                data.exposurePathwayDescription(),
                data.epaUrl(),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    public Optional<SeoPageData> findByEpaId(String epaId) {
        List<SeoPageData> rows = jdbcTemplate.query("""
                SELECT epa_id, site_name, state_code, exposure_status_code, exposure_status_label,
                       exposure_pathway_desc, epa_url
                FROM site_seo_page_cache
                WHERE epa_id = ?
                """, (rs, rowNum) -> new SeoPageData(
                rs.getString("epa_id"),
                rs.getString("site_name"),
                rs.getString("state_code"),
                rs.getString("exposure_status_code"),
                rs.getString("exposure_status_label"),
                rs.getString("exposure_pathway_desc"),
                rs.getString("epa_url")
        ), epaId);
        return rows.stream().findFirst();
    }

    public List<SeoPageData> findByStateCode(String stateCode) {
        return jdbcTemplate.query("""
                SELECT epa_id, site_name, state_code, exposure_status_code, exposure_status_label,
                       exposure_pathway_desc, epa_url
                FROM site_seo_page_cache
                WHERE state_code = ?
                ORDER BY lower(site_name) ASC, epa_id ASC
                """, (rs, rowNum) -> new SeoPageData(
                rs.getString("epa_id"),
                rs.getString("site_name"),
                rs.getString("state_code"),
                rs.getString("exposure_status_code"),
                rs.getString("exposure_status_label"),
                rs.getString("exposure_pathway_desc"),
                rs.getString("epa_url")
        ), stateCode);
    }

    public List<String> findAllSiteIds() {
        return jdbcTemplate.query("""
                SELECT epa_id
                FROM site_seo_page_cache
                ORDER BY epa_id ASC
                """, (rs, rowNum) -> rs.getString("epa_id"));
    }

    public int countCachedSites() {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM site_seo_page_cache", Integer.class);
        return count == null ? 0 : count;
    }

    public List<ActiveBoundaryRow> findActiveBoundaryRows() {
        return jdbcTemplate.query("""
                SELECT s.epa_id, s.site_name, s.state_code, s.epa_url,
                       h.humexposurestscode, h.humanexposurepathdesc
                FROM npl_site_boundaries s
                LEFT JOIN npl_human_exposure h ON h.epa_id = s.epa_id
                WHERE s.npl_status_code = 'F'
                ORDER BY s.epa_id ASC
                """, (rs, rowNum) -> new ActiveBoundaryRow(
                rs.getString("epa_id"),
                rs.getString("site_name"),
                rs.getString("state_code"),
                rs.getString("epa_url"),
                rs.getString("humexposurestscode"),
                rs.getString("humanexposurepathdesc")
        ));
    }

    public record ActiveBoundaryRow(
            String epaId,
            String siteName,
            String stateCode,
            String epaUrl,
            String exposureStatusCode,
            String exposurePathwayDesc
    ) {
    }
}

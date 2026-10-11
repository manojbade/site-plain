package com.siteplain.data.repository;

import com.siteplain.domain.model.SeoPageData;
import com.siteplain.domain.view.SiteSearchSuggestion;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SeoPageRepository {

    private final JdbcTemplate jdbcTemplate;

    public SeoPageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * DELETE rather than TRUNCATE: inside the refresh transaction readers keep seeing the old rows
     * until commit, where TRUNCATE's ACCESS EXCLUSIVE lock would block every page that reads the cache.
     */
    public void clearSiteCache() {
        jdbcTemplate.update("DELETE FROM site_seo_page_cache");
    }

    /** Distinct exposure status codes among active sites, so their labels can be computed once each. */
    public List<String> findActiveExposureStatusCodes() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT humexposurestscode
                FROM active_npl_sites
                WHERE humexposurestscode IS NOT NULL
                """, String.class);
    }

    /**
     * Fills the cache from active_npl_sites in one statement. Labels come from the caller (one per
     * code present, from ExposureStatusMapper) so the mapping stays in Java; sites with no code get
     * unreportedLabel.
     */
    public int insertSiteCacheFromActiveSites(Map<String, String> labelsByCode, String unreportedLabel) {
        String[] codes = labelsByCode.keySet().toArray(String[]::new);
        String[] labels = labelsByCode.values().toArray(String[]::new);
        Timestamp computedAt = Timestamp.valueOf(LocalDateTime.now());
        return jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO site_seo_page_cache
                      (epa_id, site_name, state_code, exposure_status_code, exposure_status_label,
                       exposure_pathway_desc, epa_url, has_boundary, computed_at)
                    SELECT a.epa_id, a.site_name, a.state_code, a.humexposurestscode,
                           COALESCE(l.label, ?), a.humanexposurepathdesc, a.epa_url, a.has_boundary, ?
                    FROM active_npl_sites a
                    LEFT JOIN unnest(?::text[], ?::text[]) AS l(code, label) ON l.code = a.humexposurestscode
                    """);
            statement.setString(1, unreportedLabel);
            statement.setTimestamp(2, computedAt);
            statement.setArray(3, connection.createArrayOf("text", codes));
            statement.setArray(4, connection.createArrayOf("text", labels));
            return statement;
        });
    }

    public void analyzeSiteCache() {
        jdbcTemplate.execute("ANALYZE site_seo_page_cache");
    }

    public void insertSiteCacheRow(SeoPageData data) {
        jdbcTemplate.update("""
                INSERT INTO site_seo_page_cache
                  (epa_id, site_name, state_code, exposure_status_code, exposure_status_label,
                   exposure_pathway_desc, epa_url, has_boundary, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                data.epaId(),
                data.siteName(),
                data.stateCode(),
                data.exposureStatusCode(),
                data.exposureStatusLabel(),
                data.exposurePathwayDescription(),
                data.epaUrl(),
                data.hasBoundary(),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    public Optional<SeoPageData> findByEpaId(String epaId) {
        List<SeoPageData> rows = jdbcTemplate.query("""
                SELECT epa_id, site_name, state_code, exposure_status_code, exposure_status_label,
                       exposure_pathway_desc, epa_url, has_boundary
                FROM site_seo_page_cache
                WHERE epa_id = ?
                """, (rs, rowNum) -> new SeoPageData(
                rs.getString("epa_id"),
                rs.getString("site_name"),
                rs.getString("state_code"),
                rs.getString("exposure_status_code"),
                rs.getString("exposure_status_label"),
                rs.getString("exposure_pathway_desc"),
                rs.getString("epa_url"),
                rs.getBoolean("has_boundary")
        ), epaId);
        return rows.stream().findFirst();
    }

    public List<SeoPageData> findByStateCode(String stateCode) {
        return jdbcTemplate.query("""
                SELECT epa_id, site_name, state_code, exposure_status_code, exposure_status_label,
                       exposure_pathway_desc, epa_url, has_boundary
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
                rs.getString("epa_url"),
                rs.getBoolean("has_boundary")
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

    public List<SiteSearchSuggestion> searchSuggestions(String query, int limit) {
        String containsPattern = "%" + query + "%";
        String prefixPattern = query + "%";
        return jdbcTemplate.query("""
                SELECT epa_id, site_name, state_code, exposure_status_label, exposure_status_code
                FROM site_seo_page_cache
                WHERE upper(epa_id) LIKE upper(?)
                   OR lower(site_name) LIKE lower(?)
                ORDER BY CASE
                    WHEN upper(epa_id) = upper(?) THEN 0
                    WHEN lower(site_name) = lower(?) THEN 1
                    WHEN upper(epa_id) LIKE upper(?) THEN 2
                    WHEN lower(site_name) LIKE lower(?) THEN 3
                    ELSE 4
                  END,
                  abs(length(site_name) - length(?)) ASC,
                  lower(site_name) ASC,
                  epa_id ASC
                LIMIT ?
                """, (rs, rowNum) -> new SiteSearchSuggestion(
                rs.getString("epa_id"),
                rs.getString("site_name"),
                rs.getString("state_code"),
                rs.getString("exposure_status_label"),
                rs.getString("exposure_status_code")
        ), containsPattern, containsPattern, query, query, prefixPattern, prefixPattern, query, limit);
    }

    public List<SiteSearchSuggestion> findExactSiteNameMatches(String siteName, int limit) {
        return jdbcTemplate.query("""
                SELECT epa_id, site_name, state_code, exposure_status_label, exposure_status_code
                FROM site_seo_page_cache
                WHERE lower(site_name) = lower(?)
                ORDER BY epa_id ASC
                LIMIT ?
                """, (rs, rowNum) -> new SiteSearchSuggestion(
                rs.getString("epa_id"),
                rs.getString("site_name"),
                rs.getString("state_code"),
                rs.getString("exposure_status_label"),
                rs.getString("exposure_status_code")
        ), siteName, limit);
    }
}

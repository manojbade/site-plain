package com.siteplain.data.repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StatePageRepository {

    private final JdbcTemplate jdbcTemplate;

    public StatePageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void clear() {
        jdbcTemplate.update("TRUNCATE state_page_cache");
    }

    public void insert(String stateCode, String stateName, int siteCount) {
        jdbcTemplate.update("""
                INSERT INTO state_page_cache (state_code, state_name, site_count, computed_at)
                VALUES (?, ?, ?, ?)
                """, stateCode, stateName, siteCount, Timestamp.valueOf(LocalDateTime.now()));
    }

    public Optional<StatePageSummary> findByStateCode(String stateCode) {
        List<StatePageSummary> rows = jdbcTemplate.query("""
                SELECT state_code, state_name, site_count, computed_at
                FROM state_page_cache
                WHERE state_code = ?
                """, (rs, rowNum) -> new StatePageSummary(
                rs.getString("state_code"),
                rs.getString("state_name"),
                rs.getInt("site_count"),
                rs.getTimestamp("computed_at").toLocalDateTime()
        ), stateCode);
        return rows.stream().findFirst();
    }

    public List<String> findAllStateCodes() {
        return jdbcTemplate.query("""
                SELECT state_code
                FROM state_page_cache
                ORDER BY state_code ASC
                """, (rs, rowNum) -> rs.getString("state_code"));
    }

    public List<StateSiteCountRow> findSiteCounts() {
        return jdbcTemplate.query("""
                SELECT state_code, count(*) AS site_count
                FROM site_seo_page_cache
                WHERE state_code IS NOT NULL AND state_code <> ''
                GROUP BY state_code
                ORDER BY state_code ASC
                """, (rs, rowNum) -> new StateSiteCountRow(
                rs.getString("state_code"),
                rs.getInt("site_count")
        ));
    }

    public record StatePageSummary(
            String stateCode,
            String stateName,
            int siteCount,
            LocalDateTime computedAt
    ) {
    }

    public record StateSiteCountRow(String stateCode, int siteCount) {
    }
}

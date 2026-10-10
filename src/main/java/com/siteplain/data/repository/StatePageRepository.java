package com.siteplain.data.repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StatePageRepository {

    private final JdbcTemplate jdbcTemplate;

    public StatePageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** DELETE rather than TRUNCATE, so readers keep the old rows until the refresh commits. */
    public void clear() {
        jdbcTemplate.update("DELETE FROM state_page_cache");
    }

    /** State codes present in the site cache, so their names can be looked up once each. */
    public List<String> findCachedStateCodes() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT state_code
                FROM site_seo_page_cache
                WHERE state_code IS NOT NULL AND state_code <> ''
                """, String.class);
    }

    /** Counts cached sites per state in one statement, with the state names supplied by the caller. */
    public int insertFromSiteCache(Map<String, String> namesByCode) {
        String[] codes = namesByCode.keySet().toArray(String[]::new);
        String[] names = namesByCode.values().toArray(String[]::new);
        Timestamp computedAt = Timestamp.valueOf(LocalDateTime.now());
        return jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO state_page_cache (state_code, state_name, site_count, computed_at)
                    SELECT c.state_code, n.state_name, count(*), ?
                    FROM site_seo_page_cache c
                    JOIN unnest(?::text[], ?::text[]) AS n(state_code, state_name) ON n.state_code = c.state_code
                    GROUP BY c.state_code, n.state_name
                    """);
            statement.setTimestamp(1, computedAt);
            statement.setArray(2, connection.createArrayOf("text", codes));
            statement.setArray(3, connection.createArrayOf("text", names));
            return statement;
        });
    }

    public void analyze() {
        jdbcTemplate.execute("ANALYZE state_page_cache");
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

    public record StatePageSummary(
            String stateCode,
            String stateName,
            int siteCount,
            LocalDateTime computedAt
    ) {
    }
}

package com.siteplain.data.repository;

import java.math.BigDecimal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LookupAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public LookupAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(String stateCode,
                       Integer resultCount,
                       BigDecimal nearestMiles,
                       String geocoderUsed,
                       boolean resolved) {
        jdbcTemplate.update("""
                INSERT INTO lookup_audit (lookup_at, state_code, result_count, nearest_miles, geocoder_used, resolved)
                VALUES (now(), ?, ?, ?, ?, ?)
                """,
                stateCode, resultCount, nearestMiles, geocoderUsed, resolved);
    }
}

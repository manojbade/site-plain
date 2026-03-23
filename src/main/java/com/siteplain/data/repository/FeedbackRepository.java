package com.siteplain.data.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FeedbackRepository {

    private final JdbcTemplate jdbcTemplate;

    public FeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void save(String pageType, String epaId, Boolean helpful, String comments) {
        jdbcTemplate.update("""
                INSERT INTO feedback (submitted_at, page_type, epa_id, helpful, comments)
                VALUES (now(), ?, ?, ?, ?)
                """,
                pageType, epaId, helpful, comments);
    }
}

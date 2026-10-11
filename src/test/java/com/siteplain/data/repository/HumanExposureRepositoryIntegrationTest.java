package com.siteplain.data.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class HumanExposureRepositoryIntegrationTest {

    private static final String EPA_ID = "ZZ9999999901";

    @Autowired
    private HumanExposureRepository humanExposureRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM npl_human_exposure_staging WHERE epa_id = ?", EPA_ID);
    }

    /**
     * Staged rows are stamped by the database clock, the clock data_source_state.loaded_at uses, so
     * liveMatchesRecord never compares the app's clock with the database's.
     */
    @Test
    void stagedRows_areStampedWithTheDatabaseClock() {
        Boolean stampedByDatabase = new TransactionTemplate(transactionManager).execute(status -> {
            humanExposureRepository.insertStaging(EPA_ID, "HEUC", null, "F", "Clock Test Site", "GA", null);
            // Inside one transaction LOCALTIMESTAMP is fixed, so only a database-stamped row equals it.
            return jdbcTemplate.queryForObject(
                    "SELECT loaded_at = LOCALTIMESTAMP FROM npl_human_exposure_staging WHERE epa_id = ?",
                    Boolean.class, EPA_ID);
        });

        assertThat(stampedByDatabase).isTrue();
    }
}

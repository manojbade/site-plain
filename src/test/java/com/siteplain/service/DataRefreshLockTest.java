package com.siteplain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "TEST_DATASOURCE_URL", matches = ".+")
class DataRefreshLockTest {

    @Autowired
    private DataRefreshLock lock;

    @Autowired
    private DataSource dataSource;

    @Test
    void loserSkips_andTheLockIsReleasedEvenWhenTheWorkFails() throws Exception {
        boolean[] innerRan = {false};
        boolean outerRan = lock.runExclusively(() -> {
            // A second instance (another session) cannot take it while the first holds it.
            assertThat(lock.runExclusively(() -> innerRan[0] = true)).isFalse();
            assertThat(otherSessionCanLock()).isFalse();
        });
        assertThat(outerRan).isTrue();
        assertThat(innerRan[0]).isFalse();

        assertThatThrownBy(() -> lock.runExclusively(() -> {
            throw new IllegalStateException("refresh blew up");
        })).hasMessage("refresh blew up");

        assertThat(otherSessionCanLock()).isTrue();
    }

    /** Tries the lock from a fresh session and releases it at once. */
    private boolean otherSessionCanLock() throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet rs = connection.createStatement().executeQuery(
                     "SELECT pg_try_advisory_lock(" + DataRefreshLock.LOCK_KEY + ")")) {
            rs.next();
            boolean locked = rs.getBoolean(1);
            if (locked) {
                connection.createStatement().execute("SELECT pg_advisory_unlock(" + DataRefreshLock.LOCK_KEY + ")");
            }
            return locked;
        }
    }
}

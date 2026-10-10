package com.siteplain.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A Postgres session advisory lock around the whole data refresh, so two app instances (Railway
 * starts the new deploy while the old one still serves, on the same database) or a scheduled run
 * never refresh at the same time. The lock lives on one dedicated pool connection for the whole
 * refresh and is released before that connection goes back to the pool; if the process dies, the
 * session ends and Postgres releases it.
 */
@Component
public class DataRefreshLock {

    /** Fixed key shared by every instance of this app ("SPDATA" in ASCII). */
    static final long LOCK_KEY = 0x535044415441L;

    private static final Logger log = LoggerFactory.getLogger(DataRefreshLock.class);

    private final DataSource dataSource;

    public DataRefreshLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @FunctionalInterface
    public interface Work {
        void run() throws Exception;
    }

    /** Runs the work while holding the lock; returns false, without running it, when another session holds it. */
    public boolean runExclusively(Work work) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (!call(connection, "SELECT pg_try_advisory_lock(?)")) {
                return false;
            }
            try {
                work.run();
                return true;
            } finally {
                try {
                    call(connection, "SELECT pg_advisory_unlock(?)");
                } catch (SQLException e) {
                    // Only a broken connection fails here, and a closed session releases the lock.
                    log.warn("Could not release the data refresh lock", e);
                }
            }
        }
    }

    private static boolean call(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }
}

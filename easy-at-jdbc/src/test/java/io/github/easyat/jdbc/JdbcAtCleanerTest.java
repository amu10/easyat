package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcAtCleanerTest {
    @Test
    void cleansOnlyTerminalHistoryInBoundedBatches() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:cleanup;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE easy_at_global (xid VARCHAR(128) PRIMARY KEY, status VARCHAR(32), updated_at TIMESTAMP)");
            statement.execute("CREATE TABLE easy_at_undo_log (xid VARCHAR(128))");
            statement.execute("CREATE TABLE easy_at_branch (xid VARCHAR(128))");
            statement.execute(
                    "CREATE TABLE easy_at_lock (resource_id VARCHAR(128), table_name VARCHAR(128), pk_value VARCHAR(128), lease_until TIMESTAMP, PRIMARY KEY(resource_id,table_name,pk_value))");
            statement.execute(
                    "INSERT INTO easy_at_global VALUES "
                            + "('committed-1','COMMITTED',TIMESTAMP '2020-01-01 00:00:00'),"
                            + "('committed-2','COMMITTED',TIMESTAMP '2020-01-02 00:00:00'),"
                            + "('rolled','ROLLED_BACK',TIMESTAMP '2020-01-01 00:00:00'),"
                            + "('active','ACTIVE',TIMESTAMP '2020-01-01 00:00:00'),"
                            + "('dirty','DIRTY_WRITE',TIMESTAMP '2020-01-01 00:00:00')");
            statement.execute(
                    "INSERT INTO easy_at_undo_log VALUES ('committed-1'),('committed-2'),('rolled'),('active'),('dirty')");
            statement.execute(
                    "INSERT INTO easy_at_branch VALUES ('committed-1'),('committed-2'),('rolled'),('active'),('dirty')");
            statement.execute(
                    "INSERT INTO easy_at_lock VALUES "
                            + "('r','account','1',TIMESTAMP '2020-01-01 00:00:00'),"
                            + "('r','account','2',TIMESTAMP '2020-01-02 00:00:00'),"
                            + "('r','account','3',TIMESTAMP '2099-01-01 00:00:00')");
        }

        JdbcAtCleaner.CleanupResult result =
                new JdbcAtCleaner(dataSource)
                        .cleanup(
                                System.currentTimeMillis(),
                                System.currentTimeMillis(),
                                System.currentTimeMillis(),
                                1);

        assertEquals(1, result.getCommittedTransactions());
        assertEquals(1, result.getRolledBackTransactions());
        assertEquals(1, result.getExpiredLocks());
        assertEquals(3, count(dataSource, "easy_at_global"));
        assertEquals(3, count(dataSource, "easy_at_undo_log"));
        assertEquals(3, count(dataSource, "easy_at_branch"));
        assertEquals(2, count(dataSource, "easy_at_lock"));
        assertEquals(1, count(dataSource, "easy_at_global WHERE status='ACTIVE'"));
        assertEquals(1, count(dataSource, "easy_at_global WHERE status='DIRTY_WRITE'"));
    }

    private int count(JdbcDataSource dataSource, String tableExpression) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery("SELECT COUNT(*) FROM " + tableExpression)) {
            result.next();
            return result.getInt(1);
        }
    }
}

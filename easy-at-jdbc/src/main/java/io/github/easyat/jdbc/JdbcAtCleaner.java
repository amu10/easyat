package io.github.easyat.jdbc;

import io.github.easyat.core.AtException;
import io.github.easyat.core.AtStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/** Incrementally removes terminal JDBC transaction history and expired lock rows. */
public final class JdbcAtCleaner {
    private final DataSource dataSource;

    public JdbcAtCleaner(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public CleanupResult cleanup(
            long committedBefore, long rolledBackBefore, long expiredLockBefore, int batchSize) {
        if (batchSize <= 0) throw new IllegalArgumentException("batchSize must be positive");
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                int committed =
                        cleanupTransactions(
                                connection, AtStatus.COMMITTED, committedBefore, batchSize);
                int rolledBack =
                        cleanupTransactions(
                                connection, AtStatus.ROLLED_BACK, rolledBackBefore, batchSize);
                int expiredLocks = cleanupLocks(connection, expiredLockBefore, batchSize);
                connection.commit();
                return new CleanupResult(committed, rolledBack, expiredLocks);
            } catch (Exception failure) {
                connection.rollback();
                if (failure instanceof RuntimeException) throw (RuntimeException) failure;
                throw new AtException("Cannot clean easyAt history", failure);
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException failure) {
            throw new AtException("Cannot clean easyAt history", failure);
        }
    }

    private int cleanupTransactions(
            Connection connection, AtStatus status, long updatedBefore, int batchSize)
            throws SQLException {
        List<String> xids = selectXids(connection, status, updatedBefore, batchSize);
        if (xids.isEmpty()) return 0;

        deleteByXids(connection, "easy_at_undo_log", xids);
        deleteByXids(connection, "easy_at_branch", xids);

        String sql =
                "DELETE FROM easy_at_global WHERE status=? AND updated_at<? AND xid IN ("
                        + placeholders(xids.size())
                        + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setTimestamp(2, new Timestamp(updatedBefore));
            bindXids(statement, xids, 3);
            return statement.executeUpdate();
        }
    }

    private List<String> selectXids(
            Connection connection, AtStatus status, long updatedBefore, int batchSize)
            throws SQLException {
        String sql =
                "SELECT xid FROM easy_at_global WHERE status=? AND updated_at<? "
                        + "ORDER BY updated_at,xid LIMIT ?";
        List<String> xids = new ArrayList<String>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setTimestamp(2, new Timestamp(updatedBefore));
            statement.setInt(3, batchSize);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) xids.add(result.getString(1));
            }
        }
        return xids;
    }

    private void deleteByXids(Connection connection, String table, List<String> xids)
            throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE xid IN (" + placeholders(xids.size()) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindXids(statement, xids, 1);
            statement.executeUpdate();
        }
    }

    private int cleanupLocks(Connection connection, long expiredBefore, int batchSize)
            throws SQLException {
        String select =
                "SELECT resource_id,table_name,pk_value FROM easy_at_lock "
                        + "WHERE lease_until<? ORDER BY lease_until LIMIT ?";
        List<LockKey> keys = new ArrayList<LockKey>();
        try (PreparedStatement statement = connection.prepareStatement(select)) {
            statement.setTimestamp(1, new Timestamp(expiredBefore));
            statement.setInt(2, batchSize);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next())
                    keys.add(
                            new LockKey(
                                    result.getString(1), result.getString(2), result.getString(3)));
            }
        }

        String delete =
                "DELETE FROM easy_at_lock WHERE resource_id=? AND table_name=? AND pk_value=? "
                        + "AND lease_until<?";
        int deleted = 0;
        try (PreparedStatement statement = connection.prepareStatement(delete)) {
            for (LockKey key : keys) {
                statement.setString(1, key.resourceId);
                statement.setString(2, key.tableName);
                statement.setString(3, key.primaryKeyValue);
                statement.setTimestamp(4, new Timestamp(expiredBefore));
                statement.addBatch();
            }
            for (int count : statement.executeBatch()) {
                if (count > 0) deleted += count;
            }
        }
        return deleted;
    }

    private static String placeholders(int count) {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) value.append(',');
            value.append('?');
        }
        return value.toString();
    }

    private static void bindXids(PreparedStatement statement, List<String> xids, int start)
            throws SQLException {
        for (int i = 0; i < xids.size(); i++) statement.setString(start + i, xids.get(i));
    }

    private static final class LockKey {
        private final String resourceId;
        private final String tableName;
        private final String primaryKeyValue;

        private LockKey(String resourceId, String tableName, String primaryKeyValue) {
            this.resourceId = resourceId;
            this.tableName = tableName;
            this.primaryKeyValue = primaryKeyValue;
        }
    }

    public static final class CleanupResult {
        private final int committedTransactions;
        private final int rolledBackTransactions;
        private final int expiredLocks;

        CleanupResult(int committedTransactions, int rolledBackTransactions, int expiredLocks) {
            this.committedTransactions = committedTransactions;
            this.rolledBackTransactions = rolledBackTransactions;
            this.expiredLocks = expiredLocks;
        }

        public int getCommittedTransactions() {
            return committedTransactions;
        }

        public int getRolledBackTransactions() {
            return rolledBackTransactions;
        }

        public int getExpiredLocks() {
            return expiredLocks;
        }
    }
}

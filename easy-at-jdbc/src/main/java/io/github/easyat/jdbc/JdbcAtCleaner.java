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

    /**
     * 增量清理已终态（COMMITTED / ROLLED_BACK）的 JDBC 事务历史与过期全局锁。
     *
     * <p>整批在一个本地事务里完成（先删 undo / branch，再删 global，再批量删过期锁）， 任一步失败整体回滚，保证「不留下孤儿记录」。
     *
     * @param committedBefore 清理 updatedAt 早于此时间戳的 COMMITTED 事务
     * @param rolledBackBefore 清理 updatedAt 早于此时间戳的 ROLLED_BACK 事务
     * @param expiredLockBefore 清理 lease_until 早于此时间戳的过期锁
     * @param batchSize 每批最多处理多少条（限流，防止单事务过大）
     * @return 本批次清理的条数统计
     */
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

    /** 删除某一终态、updatedAt 早于给定阈值的事务：先删其子表（undo/branch），再删 global 行。 */
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

    /** 选出本批要清理的事务 xid（按 updated_at、xid 排序，限 batchSize 条）。 */
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

    /** 按 xid 集合批量删除指定表（easy_at_undo_log / easy_at_branch）中的对应行。 */
    private void deleteByXids(Connection connection, String table, List<String> xids)
            throws SQLException {
        String sql = "DELETE FROM " + table + " WHERE xid IN (" + placeholders(xids.size()) + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindXids(statement, xids, 1);
            statement.executeUpdate();
        }
    }

    /** 删除过期的全局锁行：先选出 lease_until 过期的锁，再用「lease_until 仍过期」做条件批量删（防误删刚续租的锁）。 */
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
        /** 资源 id。 */
        private final String resourceId;

        /** 表名。 */
        private final String tableName;

        /** 主键列的值。 */
        private final String primaryKeyValue;

        private LockKey(String resourceId, String tableName, String primaryKeyValue) {
            this.resourceId = resourceId;
            this.tableName = tableName;
            this.primaryKeyValue = primaryKeyValue;
        }
    }

    public static final class CleanupResult {
        /** 本批次清理的 COMMITTED 事务行数。 */
        private final int committedTransactions;

        /** 本批次清理的 ROLLED_BACK 事务行数。 */
        private final int rolledBackTransactions;

        /** 本批次清理的过期全局锁行数。 */
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

package io.github.easyat.dbtests;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.core.*;
import io.github.easyat.jdbc.*;
import java.sql.*;
import java.util.Collections;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * 在<b>真实 PostgreSQL</b> 上跑通完整 AT 链路。
 *
 * <p>PG 与 MySQL 的差异足以让"在一种库上正确"的逻辑在另一种库上出错：JDBC 元数据返回的列标签默认<b>小写</b>、
 * 二进制列类型是 BYTEA 而不是 BLOB、事务隔离级别默认是 READ COMMITTED 且 DDL 事务性不同。这里用真实实例
 * 验证 {@link PostgresAtSqlDialect} 与整个 undo 生成/补偿链路。
 */
class PostgresRealDatabaseIT {

    private static final String[] SCHEMA = {
        "DROP TABLE IF EXISTS easy_at_lock",
        "DROP TABLE IF EXISTS easy_at_undo_log",
        "DROP TABLE IF EXISTS easy_at_global",
        "DROP TABLE IF EXISTS t_account",
        "CREATE TABLE t_account (id BIGINT PRIMARY KEY, balance INT NOT NULL, owner VARCHAR(64))",
        "INSERT INTO t_account(id,balance,owner) VALUES(1,100,'alice')",
        "CREATE TABLE easy_at_global ("
                + "xid VARCHAR(128) PRIMARY KEY,"
                + "name VARCHAR(255),"
                + "status VARCHAR(32) NOT NULL,"
                + "timeout_at TIMESTAMP,"
                + "retry_count INT NOT NULL DEFAULT 0,"
                + "next_retry_at TIMESTAMP,"
                + "version BIGINT NOT NULL DEFAULT 0,"
                + "owner VARCHAR(128) NULL,"
                + "lease_until TIMESTAMP NULL,"
                + "created_at TIMESTAMP,"
                + "updated_at TIMESTAMP)",
        "CREATE TABLE easy_at_undo_log ("
                + "undo_id VARCHAR(128) PRIMARY KEY,"
                + "xid VARCHAR(128) NOT NULL,"
                + "resource_id VARCHAR(255),"
                + "table_name VARCHAR(255),"
                + "pk_name VARCHAR(255),"
                + "pk_value VARCHAR(512),"
                + "rollback_sql VARCHAR(1000),"
                + "rollback_params BYTEA,"
                + "before_image BYTEA,"
                + "after_image BYTEA,"
                + "status VARCHAR(32),"
                + "created_at TIMESTAMP,"
                + "updated_at TIMESTAMP)",
        "CREATE TABLE easy_at_lock ("
                + "resource_id VARCHAR(255) NOT NULL,"
                + "table_name VARCHAR(255) NOT NULL,"
                + "pk_value VARCHAR(512) NOT NULL,"
                + "xid VARCHAR(128) NOT NULL,"
                + "lease_until TIMESTAMP,"
                + "created_at TIMESTAMP,"
                + "PRIMARY KEY(resource_id,table_name,pk_value))"
    };

    @Test
    void detectsPostgresDialect() throws Exception {
        try (RealDatabaseSupport.JdbcTarget target = RealDatabaseSupport.postgres()) {
            AtSqlDialect dialect = AtSqlDialects.detect(target.dataSource());
            assertTrue(
                    dialect instanceof PostgresAtSqlDialect,
                    () -> "PostgreSQL 必须解析出 PG 方言，实际是 " + dialect.getClass().getSimpleName());
        }
    }

    /**
     * PG 的 JDBC 列标签默认小写，而 undo 的 before/after image 是按列标签比对的。若大小写处理有误，
     * 这里会比 H2（MODE=MySQL）更容易暴露出来。
     */
    @Test
    void fullAtRoundTripWithRollback() throws Exception {
        try (RealDatabaseSupport.JdbcTarget target = RealDatabaseSupport.postgres()) {
            DataSource raw = target.dataSource();
            execute(raw, SCHEMA);

            JdbcAtRepository repository = new JdbcAtRepository(raw);
            JdbcGlobalLockManager locks = new JdbcGlobalLockManager(raw, 60000L);
            AtTransactionManager manager =
                    new AtTransactionManager(
                            repository,
                            new JdbcUndoExecutor(
                                    Collections.<String, DataSource>singletonMap("dataSource", raw)),
                            locks,
                            3,
                            "it-node-1",
                            30000L);
            AtDataSource at = new AtDataSource("dataSource", raw, manager, locks);

            AtTransaction tx = manager.begin("pg-it", 60000L);
            try (Connection c = at.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement p =
                        c.prepareStatement("UPDATE t_account SET balance=? WHERE id=?")) {
                    p.setInt(1, 40);
                    p.setLong(2, 1L);
                    assertEquals(1, p.executeUpdate());
                }
                c.commit();
            }

            assertEquals(40, balance(raw));
            assertEquals(1, repository.find(tx.getXid()).get().getUndoRecords().size());

            manager.rollback(tx.getXid());
            assertEquals(100, balance(raw), "真实 PostgreSQL 上全局回滚必须还原原值");
            assertEquals(AtStatus.ROLLED_BACK, repository.find(tx.getXid()).get().getStatus());
            assertEquals(0, rows(raw, "SELECT COUNT(*) FROM easy_at_lock"), "回滚后全局锁必须释放");
            locks.close();
        }
    }

    @Test
    void undoRestoresEveryColumnIncludingStrings() throws Exception {
        try (RealDatabaseSupport.JdbcTarget target = RealDatabaseSupport.postgres()) {
            DataSource raw = target.dataSource();
            execute(raw, SCHEMA);
            JdbcAtRepository repository = new JdbcAtRepository(raw);
            JdbcGlobalLockManager locks = new JdbcGlobalLockManager(raw, 60000L);
            AtTransactionManager manager =
                    new AtTransactionManager(
                            repository,
                            new JdbcUndoExecutor(
                                    Collections.<String, DataSource>singletonMap("dataSource", raw)),
                            locks,
                            3,
                            "it-node-1",
                            30000L);
            AtDataSource at = new AtDataSource("dataSource", raw, manager, locks);

            AtTransaction tx = manager.begin("pg-columns", 60000L);
            try (Connection c = at.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement p =
                        c.prepareStatement("UPDATE t_account SET balance=?, owner=? WHERE id=?")) {
                    p.setInt(1, 5);
                    p.setString(2, "bob");
                    p.setLong(3, 1L);
                    p.executeUpdate();
                }
                c.commit();
            }
            manager.rollback(tx.getXid());

            try (Connection c = raw.getConnection();
                    PreparedStatement p =
                            c.prepareStatement("SELECT balance,owner FROM t_account WHERE id=?")) {
                p.setLong(1, 1L);
                try (ResultSet r = p.executeQuery()) {
                    assertTrue(r.next());
                    assertEquals(100, r.getInt(1), "数值列必须还原");
                    assertEquals("alice", r.getString(2), "字符串列也必须还原");
                }
            }
            locks.close();
        }
    }

    private static int balance(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT balance FROM t_account WHERE id=1")) {
            assertTrue(r.next());
            return r.getInt(1);
        }
    }

    private static long rows(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getLong(1);
        }
    }

    private static void execute(DataSource ds, String[] statements) throws SQLException {
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement()) {
            for (String sql : statements) s.execute(sql);
        }
    }
}

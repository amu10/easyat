package io.github.easyat.dbtests;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.core.*;
import io.github.easyat.jdbc.*;
import java.sql.*;
import java.util.Collections;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * 在<b>真实 MySQL</b> 上跑通完整 AT 链路。
 *
 * <p>为什么必须做：此前所有 jdbc 验证都在 H2 上完成，而 MySQL 方言分支（{@link MysqlAtSqlDialect}）、
 * 真实 JDBC 元数据取主键的行为、InnoDB 事务/隔离级别语义、以及协调表的真实 DDL 类型（BLOB / DATETIME /
 * 主键长度限制）全都<b>从未被执行过</b>。这些差异只能在真库上暴露。
 */
class MysqlRealDatabaseIT {

    /**
     * 注意此处的 easy_at_lock 主键列长度：MySQL InnoDB 单索引上限约 3072 字节，utf8mb4 下每字符 4 字节。
     * 如果照搬 VARCHAR(255)+VARCHAR(255)+VARCHAR(512)，会直接 "Specified key was too long" 建表失败。
     * 这是只有在真实 MySQL 上才会撞到的问题。
     */
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
                + "timeout_at DATETIME,"
                + "retry_count INT NOT NULL DEFAULT 0,"
                + "next_retry_at DATETIME,"
                + "version BIGINT NOT NULL DEFAULT 0,"
                + "owner VARCHAR(128) NULL,"
                + "lease_until DATETIME NULL,"
                + "created_at DATETIME,"
                + "updated_at DATETIME)",
        "CREATE TABLE easy_at_undo_log ("
                + "undo_id VARCHAR(128) PRIMARY KEY,"
                + "xid VARCHAR(128) NOT NULL,"
                + "resource_id VARCHAR(255),"
                + "table_name VARCHAR(255),"
                + "pk_name VARCHAR(255),"
                + "pk_value VARCHAR(512),"
                + "rollback_sql VARCHAR(1000),"
                + "rollback_params BLOB,"
                + "before_image BLOB,"
                + "after_image BLOB,"
                + "status VARCHAR(32),"
                + "created_at DATETIME,"
                + "updated_at DATETIME)",
        "CREATE TABLE easy_at_lock ("
                + "resource_id VARCHAR(100) NOT NULL,"
                + "table_name VARCHAR(100) NOT NULL,"
                + "pk_value VARCHAR(200) NOT NULL,"
                + "xid VARCHAR(128) NOT NULL,"
                + "lease_until DATETIME,"
                + "created_at DATETIME,"
                + "PRIMARY KEY(resource_id,table_name,pk_value))"
    };

    @Test
    void detectsMysqlDialect() throws Exception {
        try (RealDatabaseSupport.JdbcTarget target = RealDatabaseSupport.mysql()) {
            AtSqlDialect dialect = AtSqlDialects.detect(target.dataSource());
            assertNotNull(dialect);
            assertTrue(
                    dialect instanceof MysqlAtSqlDialect,
                    () -> "MySQL 必须解析出 MySQL 方言，实际是 " + dialect.getClass().getSimpleName());
        }
    }

    @Test
    void fullAtRoundTripWithRollback() throws Exception {
        try (RealDatabaseSupport.JdbcTarget target = RealDatabaseSupport.mysql()) {
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

            AtTransaction tx = manager.begin("mysql-it", 60000L);
            try (Connection c = at.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement p =
                        c.prepareStatement("UPDATE t_account SET balance=? WHERE id=?")) {
                    p.setInt(1, 40);
                    p.setLong(2, 1L);
                    assertEquals(1, p.executeUpdate(), "必须命中一行");
                }
                c.commit();
            }

            assertEquals(40, balance(raw), "本地事务提交后是新值");
            assertEquals(
                    1,
                    repository.find(tx.getXid()).get().getUndoRecords().size(),
                    "undo 必须与业务 DML 在同一本地事务里落库");

            manager.rollback(tx.getXid());
            assertEquals(100, balance(raw), "真实 MySQL 上全局回滚必须把余额还原");
            assertEquals(AtStatus.ROLLED_BACK, repository.find(tx.getXid()).get().getStatus());
            assertEquals(0, rows(raw, "SELECT COUNT(*) FROM easy_at_lock"), "回滚后全局锁必须释放");
            locks.close();
        }
    }

    @Test
    void concurrentRowUpdatesSerializeOnGlobalLock() throws Exception {
        try (RealDatabaseSupport.JdbcTarget target = RealDatabaseSupport.mysql()) {
            DataSource raw = target.dataSource();
            execute(raw, SCHEMA);
            JdbcAtRepository repository = new JdbcAtRepository(raw);
            JdbcGlobalLockManager locks = new JdbcGlobalLockManager(raw, 60000L);

            // 关键：锁的持有者必须是 easy_at_global 里真实存在且仍在途（ACTIVE）的事务。
            // 若 XID 不在表中，锁管理器会判其为孤儿事务并允许抢占——那是设计行为，会掩盖真正的互斥性问题。
            long now = System.currentTimeMillis();
            repository.create(new AtTransaction("xid-a", "tx-a", now, now + 60000L));
            repository.create(new AtTransaction("xid-b", "tx-b", now, now + 60000L));

            locks.acquire("dataSource", "t_account", "1", "xid-a", 0L);
            assertThrows(
                    GlobalLockConflictException.class,
                    () -> locks.acquire("dataSource", "t_account", "1", "xid-b", 0L),
                    "真实 MySQL 上同一行仍须互斥");

            locks.releaseByXid("xid-a");
            locks.acquire("dataSource", "t_account", "1", "xid-b", 0L);
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

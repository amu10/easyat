package io.github.easyat.dbtests;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.core.*;
import io.github.easyat.jdbc.*;
import java.sql.*;
import java.util.Collections;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * JOIN / 子查询 DML 在<b>真实数据库</b>上的 AT 回滚。
 *
 * <p>为什么必须放到这里：H2 不支持 MySQL 的多表 UPDATE 语法，也不支持 PostgreSQL 的 {@code UPDATE ... FROM}，
 * 所以 {@code GenericAtSqlTest} 只能在 planner 层断言拼出来的快照 SQL。真正的执行、undo 回放、
 * 以及各家的方言差异（引号、类型、LIMIT 写法）只能在这里验。
 */
class JoinAndSubqueryAtIT {

    /**
     * 协调表必须按方言建：MySQL 用 DATETIME + BLOB，PostgreSQL 用 TIMESTAMP + BYTEA。
     * （MySQL 5.7 在严格模式下对无默认值的 TIMESTAMP 列会直接报 "Invalid default value"。）
     */
    private static String[] schema(boolean postgres) {
        String datetime = postgres ? "TIMESTAMP" : "DATETIME";
        String blob = postgres ? "BYTEA" : "BLOB";
        return new String[] {
            "DROP TABLE IF EXISTS easy_at_lock",
            "DROP TABLE IF EXISTS easy_at_undo_log",
            "DROP TABLE IF EXISTS easy_at_global",
            "DROP TABLE IF EXISTS t_frozen",
            "DROP TABLE IF EXISTS t_account",
            "CREATE TABLE t_account (id BIGINT PRIMARY KEY, balance INT NOT NULL)",
            "INSERT INTO t_account(id,balance) VALUES(1,100),(2,200),(3,300)",
            "CREATE TABLE t_frozen (aid BIGINT PRIMARY KEY, status VARCHAR(20), amount INT)",
            "INSERT INTO t_frozen(aid,status,amount) VALUES(1,'FROZEN',55),(2,'FROZEN',66),(3,'OPEN',77)",
            "CREATE TABLE easy_at_global ("
                    + "xid VARCHAR(128) PRIMARY KEY,"
                    + "name VARCHAR(255),"
                    + "status VARCHAR(32) NOT NULL,"
                    + "timeout_at "
                    + datetime
                    + ","
                    + "retry_count INT NOT NULL DEFAULT 0,"
                    + "next_retry_at "
                    + datetime
                    + ","
                    + "version BIGINT NOT NULL DEFAULT 0,"
                    + "owner VARCHAR(128) NULL,"
                    + "lease_until "
                    + datetime
                    + " NULL,"
                    + "created_at "
                    + datetime
                    + ","
                    + "updated_at "
                    + datetime
                    + ")",
            "CREATE TABLE easy_at_undo_log ("
                    + "undo_id VARCHAR(128) PRIMARY KEY,"
                    + "xid VARCHAR(128) NOT NULL,"
                    + "resource_id VARCHAR(255),"
                    + "table_name VARCHAR(255),"
                    + "pk_name VARCHAR(255),"
                    + "pk_value VARCHAR(512),"
                    + "rollback_sql VARCHAR(1000),"
                    + "rollback_params "
                    + blob
                    + ","
                    + "before_image "
                    + blob
                    + ","
                    + "after_image "
                    + blob
                    + ","
                    + "status VARCHAR(32),"
                    + "created_at "
                    + datetime
                    + ","
                    + "updated_at "
                    + datetime
                    + ")",
            "CREATE TABLE easy_at_lock ("
                    + "resource_id VARCHAR(100) NOT NULL,"
                    + "table_name VARCHAR(100) NOT NULL,"
                    + "pk_value VARCHAR(200) NOT NULL,"
                    + "xid VARCHAR(128) NOT NULL,"
                    + "lease_until "
                    + datetime
                    + ","
                    + "created_at "
                    + datetime
                    + ","
                    + "PRIMARY KEY(resource_id,table_name,pk_value))"
        };
    }

    @Test
    void mysqlJoinUpdateRollsBack() throws Exception {
        try (RealDatabaseSupport.JdbcTarget target = RealDatabaseSupport.mysql()) {
            DataSource raw = target.dataSource();
            execute(raw, schema(false));
            AtTransactionManager manager = manager(raw);
            AtDataSource at = new AtDataSource("dataSource", raw, manager, lockManager(raw));

            AtTransaction tx = manager.begin("join-update", 60000L);
            try (Connection c = at.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement p =
                        c.prepareStatement(
                                "UPDATE t_account a JOIN t_frozen f ON a.id=f.aid SET a.balance=? WHERE f.status=?")) {
                    p.setInt(1, 0);
                    p.setString(2, "FROZEN");
                    assertEquals(2, p.executeUpdate());
                }
                c.commit();
            }
            assertEquals(0, balance(raw, 1));
            assertEquals(0, balance(raw, 2));
            assertEquals(300, balance(raw, 3), "未被 JOIN 命中的行不能被动");

            manager.rollback(tx.getXid());
            assertEquals(100, balance(raw, 1), "JOIN UPDATE 的 undo 必须把整行还原");
            assertEquals(200, balance(raw, 2));
            assertEquals(300, balance(raw, 3));
        }
    }

    @Test
    void mysqlSubqueryDeleteRestoresRows() throws Exception {
        try (RealDatabaseSupport.JdbcTarget target = RealDatabaseSupport.mysql()) {
            DataSource raw = target.dataSource();
            execute(raw, schema(false));
            AtTransactionManager manager = manager(raw);
            AtDataSource at = new AtDataSource("dataSource", raw, manager, lockManager(raw));

            AtTransaction tx = manager.begin("subquery-delete", 60000L);
            try (Connection c = at.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement p =
                        c.prepareStatement(
                                "DELETE FROM t_account WHERE id IN (SELECT aid FROM t_frozen WHERE status=?)")) {
                    p.setString(1, "FROZEN");
                    assertEquals(2, p.executeUpdate());
                }
                c.commit();
            }
            assertFalse(exists(raw, 1));
            assertFalse(exists(raw, 2));

            manager.rollback(tx.getXid());
            assertTrue(exists(raw, 1), "子查询 DELETE 的 undo 必须把整行插回");
            assertTrue(exists(raw, 2));
            assertEquals(100, balance(raw, 1));
            assertEquals(200, balance(raw, 2));
        }
    }

    @Test
    void postgresFromStyleUpdateRollsBack() throws Exception {
        try (RealDatabaseSupport.JdbcTarget target = RealDatabaseSupport.postgres()) {
            DataSource raw = target.dataSource();
            execute(raw, schema(true));
            AtTransactionManager manager = manager(raw);
            AtDataSource at = new AtDataSource("dataSource", raw, manager, lockManager(raw));

            AtTransaction tx = manager.begin("pg-from-update", 60000L);
            try (Connection c = at.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement p =
                        c.prepareStatement(
                                "UPDATE t_account SET balance=? FROM t_frozen f WHERE f.aid=t_account.id AND f.status=?")) {
                    p.setInt(1, 0);
                    p.setString(2, "FROZEN");
                    assertEquals(2, p.executeUpdate());
                }
                c.commit();
            }
            assertEquals(0, balance(raw, 1));
            assertEquals(300, balance(raw, 3));

            manager.rollback(tx.getXid());
            assertEquals(100, balance(raw, 1), "PostgreSQL 上 UPDATE ... FROM 的 undo 必须还原");
            assertEquals(200, balance(raw, 2));
            assertEquals(300, balance(raw, 3));
        }
    }

    private static JdbcGlobalLockManager lockManager(DataSource raw) {
        return new JdbcGlobalLockManager(raw, 60000L);
    }

    private static AtTransactionManager manager(DataSource raw) {
        return new AtTransactionManager(
                new JdbcAtRepository(raw),
                new JdbcUndoExecutor(
                        Collections.<String, DataSource>singletonMap("dataSource", raw)),
                lockManager(raw),
                3,
                "it-node-1",
                30000L);
    }

    private static int balance(DataSource ds, long id) throws SQLException {
        try (Connection c = ds.getConnection();
                PreparedStatement s =
                        c.prepareStatement("SELECT balance FROM t_account WHERE id=?")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                assertTrue(r.next(), "row " + id + " should exist");
                return r.getInt(1);
            }
        }
    }

    private static boolean exists(DataSource ds, long id) throws SQLException {
        try (Connection c = ds.getConnection();
                PreparedStatement s = c.prepareStatement("SELECT 1 FROM t_account WHERE id=?")) {
            s.setLong(1, id);
            try (ResultSet r = s.executeQuery()) {
                return r.next();
            }
        }
    }

    private static void execute(DataSource ds, String[] statements) throws SQLException {
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement()) {
            for (String sql : statements) s.execute(sql);
        }
    }
}

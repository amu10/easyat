package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.core.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.update.Update;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 通用快照路径：子查询、任意条件谓词、多行 INSERT。
 *
 * <p>这些语句此前一律抛 {@link UnsupportedAtSqlException}。现在改成「用原语句的 WHERE 先做 before-image
 * 快照」，因此不需要框架去理解谓词——数据库自己算出受影响的行。每个用例都真跑一次回滚，确认数据真的回到原样， 而不只是"没报错"。
 */
class GenericAtSqlTest {

    @AfterEach
    void clear() {
        AtContext.clear();
        AtContext.endUndo();
    }

    @Test
    void updatesRowsSelectedBySubqueryAndRollsBack() throws Exception {
        try (Fixture f = new Fixture("subqueryUpdate")) {
            AtTransaction tx = f.manager.begin("subquery", 10000);
            try (Connection c = f.dataSource.getConnection();
                    PreparedStatement s =
                            c.prepareStatement(
                                    "UPDATE account SET balance=? WHERE id IN (SELECT aid FROM frozen WHERE status=?)")) {
                s.setInt(1, 0);
                s.setString(2, "FROZEN");
                assertEquals(2, s.executeUpdate());
            }
            assertEquals(0, f.balance(1));
            assertEquals(0, f.balance(2));
            assertEquals(300, f.balance(3));

            f.manager.rollback(tx.getXid());
            assertEquals(100, f.balance(1));
            assertEquals(200, f.balance(2));
            assertEquals(300, f.balance(3));
        }
    }

    @Test
    void deletesRowsSelectedBySubqueryAndRestoresThem() throws Exception {
        try (Fixture f = new Fixture("subqueryDelete")) {
            AtTransaction tx = f.manager.begin("subqueryDelete", 10000);
            try (Connection c = f.dataSource.getConnection();
                    PreparedStatement s =
                            c.prepareStatement(
                                    "DELETE FROM account WHERE id IN (SELECT aid FROM frozen WHERE status=?)")) {
                s.setString(1, "FROZEN");
                assertEquals(2, s.executeUpdate());
            }
            assertFalse(f.exists(1));
            assertFalse(f.exists(2));

            f.manager.rollback(tx.getXid());
            assertTrue(f.exists(1));
            assertTrue(f.exists(2));
            assertEquals(100, f.balance(1));
            assertEquals(200, f.balance(2));
        }
    }

    @Test
    void updatesRowsByArbitraryPredicate() throws Exception {
        try (Fixture f = new Fixture("predicateUpdate")) {
            AtTransaction tx = f.manager.begin("predicate", 10000);
            try (Connection c = f.dataSource.getConnection();
                    PreparedStatement s =
                            c.prepareStatement("UPDATE account SET balance=? WHERE balance > ?")) {
                s.setInt(1, 7);
                s.setInt(2, 150);
                assertEquals(2, s.executeUpdate());
            }
            assertEquals(100, f.balance(1));
            assertEquals(7, f.balance(2));
            assertEquals(7, f.balance(3));

            f.manager.rollback(tx.getXid());
            assertEquals(100, f.balance(1));
            assertEquals(200, f.balance(2));
            assertEquals(300, f.balance(3));
        }
    }

    @Test
    void updatesWithSubqueryInSetClause() throws Exception {
        try (Fixture f = new Fixture("setSubquery")) {
            AtTransaction tx = f.manager.begin("setSubquery", 10000);
            try (Connection c = f.dataSource.getConnection();
                    PreparedStatement s =
                            c.prepareStatement(
                                    "UPDATE account SET balance=(SELECT amount FROM frozen WHERE aid=account.id) WHERE id=?")) {
                s.setLong(1, 1L);
                assertEquals(1, s.executeUpdate());
            }
            assertEquals(55, f.balance(1));

            f.manager.rollback(tx.getXid());
            assertEquals(100, f.balance(1));
        }
    }

    @Test
    void insertsMultipleRowsInOneStatement() throws Exception {
        try (Fixture f = new Fixture("multiRowInsert")) {
            AtTransaction tx = f.manager.begin("multiInsert", 10000);
            try (Connection c = f.dataSource.getConnection();
                    PreparedStatement s =
                            c.prepareStatement(
                                    "INSERT INTO account(id,balance) VALUES (?,?),(?,?)")) {
                s.setLong(1, 10L);
                s.setInt(2, 11);
                s.setLong(3, 20L);
                s.setInt(4, 22);
                assertEquals(2, s.executeUpdate());
            }
            assertEquals(11, f.balance(10));
            assertEquals(22, f.balance(20));

            f.manager.rollback(tx.getXid());
            assertFalse(f.exists(10));
            assertFalse(f.exists(20));
        }
    }

    /** 混合字面量与占位符的多行 INSERT：字面量不消耗占位符下标，参数推进必须按文本顺序。 */
    @Test
    void supportsLiteralValuesInMultiRowInsert() throws Exception {
        try (Fixture f = new Fixture("literalInsert")) {
            AtTransaction tx = f.manager.begin("literalInsert", 10000);
            try (Connection c = f.dataSource.getConnection();
                    PreparedStatement s =
                            c.prepareStatement(
                                    "INSERT INTO account(id,balance) VALUES (?,?),(30,33)")) {
                s.setLong(1, 10L);
                s.setInt(2, 11);
                assertEquals(2, s.executeUpdate());
            }
            assertEquals(11, f.balance(10));
            assertEquals(33, f.balance(30));

            f.manager.rollback(tx.getXid());
            assertFalse(f.exists(10));
            assertFalse(f.exists(30));
        }
    }

    @Test
    void rejectsStatementsAffectingMoreRowsThanTheLimit() throws Exception {
        try (Fixture f = new Fixture("rowCap", 2)) {
            f.manager.begin("rowCap", 10000);
            try (Connection c = f.dataSource.getConnection();
                    PreparedStatement s =
                            c.prepareStatement("UPDATE account SET balance=? WHERE balance > ?")) {
                s.setInt(1, 0);
                s.setInt(2, 0);
                UnsupportedAtSqlException failure =
                        assertThrows(UnsupportedAtSqlException.class, s::executeUpdate);
                assertTrue(failure.getMessage().contains("more than 2 rows"));
            }
        }
    }

    /**
     * 多目标表 UPDATE 无法按单行建 undo，必须拒绝。
     *
     * <p>这里直接测 planner 而不是跑 SQL：H2 不支持 MySQL 的多表 UPDATE 语法，执行阶段根本到不了我们这一层。
     */
    @Test
    void rejectsMultiTargetUpdate() throws Exception {
        Update update =
                (Update)
                        CCJSqlParserUtil.parse(
                                "UPDATE account a, frozen f SET a.balance=?, f.status=? WHERE a.id=f.aid");
        UnsupportedAtSqlException failure =
                assertThrows(
                        UnsupportedAtSqlException.class,
                        () ->
                                GenericSnapshotPlanner.forUpdate(
                                        update, new GenericAtSqlDialect(), 100));
        assertTrue(failure.getMessage().contains("single-target"));
    }

    /**
     * JOIN 的快照 SQL 构造。H2 不支持这些语法，所以在 planner 层验证拼出来的 SELECT 是对的， 真正的执行放到 {@code easy-at-db-tests}
     * 的真实 MySQL / PostgreSQL 上验。
     */
    @Test
    void buildsSnapshotSqlForJoinAndFromForms() throws Exception {
        GenericSnapshotPlanner.Plan join =
                GenericSnapshotPlanner.forUpdate(
                        (Update)
                                CCJSqlParserUtil.parse(
                                        "UPDATE account a JOIN frozen f ON a.id=f.aid SET a.balance=? WHERE f.status=?"),
                        new GenericAtSqlDialect(),
                        100);
        assertEquals(
                "SELECT a.* FROM account a JOIN frozen f ON a.id = f.aid WHERE f.status = ?",
                join.snapshotSql);
        assertEquals(1, join.parameterOffset, "SET 里的 ? 必须被跳过");
        assertEquals(1, join.parameterCount);

        GenericSnapshotPlanner.Plan comma =
                GenericSnapshotPlanner.forUpdate(
                        (Update)
                                CCJSqlParserUtil.parse(
                                        "UPDATE account a, frozen f SET a.balance=? WHERE a.id=f.aid AND f.status=?"),
                        new GenericAtSqlDialect(),
                        100);
        assertEquals(
                "SELECT a.* FROM account a, frozen f WHERE a.id = f.aid AND f.status = ?",
                comma.snapshotSql,
                "逗号连接必须补上逗号，否则拼出来是 FROM a b 这种语法错误");

        GenericSnapshotPlanner.Plan deleteJoin =
                GenericSnapshotPlanner.forDelete(
                        (Delete)
                                CCJSqlParserUtil.parse(
                                        "DELETE a FROM account a JOIN frozen f ON a.id=f.aid WHERE f.status=?"),
                        new GenericAtSqlDialect(),
                        100);
        assertEquals(
                "SELECT a.* FROM account a JOIN frozen f ON a.id = f.aid WHERE f.status = ?",
                deleteJoin.snapshotSql);
        assertEquals(0, deleteJoin.parameterOffset);
        assertTrue(deleteJoin.delete);

        GenericSnapshotPlanner.Plan postgresFrom =
                GenericSnapshotPlanner.forUpdate(
                        (Update)
                                CCJSqlParserUtil.parse(
                                        "UPDATE account SET balance=? FROM frozen f WHERE f.aid=account.id AND f.status=?"),
                        new GenericAtSqlDialect(),
                        100);
        assertEquals(
                "SELECT account.* FROM account,frozen f WHERE f.aid = account.id AND f.status = ?",
                postgresFrom.snapshotSql);
    }

    /** 子查询里的 ? 必须计入，否则参数偏移错位——这是最容易踩的坑，单独钉一个用例。 */
    @Test
    void countsParametersInsideSubqueries() throws Exception {
        GenericSnapshotPlanner.Plan plan =
                GenericSnapshotPlanner.forUpdate(
                        (Update)
                                CCJSqlParserUtil.parse(
                                        "UPDATE account SET balance=(SELECT amount FROM frozen WHERE aid=?) WHERE id IN (SELECT aid FROM frozen WHERE status=? AND tag=?)"),
                        new GenericAtSqlDialect(),
                        100);
        assertEquals(1, plan.parameterOffset, "SET 子查询里的 ? 算 1 个，必须被跳过");
        assertEquals(
                2, plan.parameterCount, "WHERE 子查询里的两个 ? 都要算进来（ExpressionVisitorAdapter 会算成 0）");
    }

    @Test
    void rejectsUpdateThatAssignsThePrimaryKey() throws Exception {
        try (Fixture f = new Fixture("pkAssign")) {
            f.manager.begin("pkAssign", 10000);
            try (Connection c = f.dataSource.getConnection();
                    PreparedStatement s =
                            c.prepareStatement("UPDATE account SET id=? WHERE balance=?")) {
                s.setLong(1, 99L);
                s.setInt(2, 100);
                UnsupportedAtSqlException failure =
                        assertThrows(UnsupportedAtSqlException.class, s::executeUpdate);
                assertTrue(failure.getMessage().contains("primary key column cannot be assigned"));
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JdbcDataSource raw;
        final AtDataSource dataSource;
        final AtTransactionManager manager;

        Fixture(String name) throws SQLException {
            this(name, AtDataSource.DEFAULT_MAX_AFFECTED_ROWS);
        }

        Fixture(String name, int maxAffectedRows) throws SQLException {
            raw = new JdbcDataSource();
            raw.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            try (Connection c = raw.getConnection();
                    Statement s = c.createStatement()) {
                s.execute("CREATE TABLE account (id BIGINT PRIMARY KEY, balance INT)");
                s.execute("INSERT INTO account(id,balance) VALUES(1,100),(2,200),(3,300)");
                s.execute(
                        "CREATE TABLE frozen (aid BIGINT PRIMARY KEY, status VARCHAR(20), amount INT)");
                s.execute(
                        "INSERT INTO frozen(aid,status,amount) VALUES(1,'FROZEN',55),(2,'FROZEN',66),(3,'OPEN',77)");
            }
            AtDataSourceTest.MemoryRepo repo = new AtDataSourceTest.MemoryRepo();
            AtDataSourceTest.MemoryLocks locks = new AtDataSourceTest.MemoryLocks();
            Map<String, DataSource> sources =
                    Collections.<String, DataSource>singletonMap("dataSource", raw);
            manager = new AtTransactionManager(repo, new JdbcUndoExecutor(sources), locks, 3);
            dataSource =
                    new AtDataSource(
                            "dataSource",
                            raw,
                            manager,
                            locks,
                            LocalTransactionBridge.NOOP,
                            false,
                            BranchRegistrar.NOOP,
                            maxAffectedRows);
        }

        int balance(long id) throws SQLException {
            try (Connection c = raw.getConnection();
                    PreparedStatement s =
                            c.prepareStatement("SELECT balance FROM account WHERE id=?")) {
                s.setLong(1, id);
                try (ResultSet r = s.executeQuery()) {
                    assertTrue(r.next(), "row " + id + " should exist");
                    return r.getInt(1);
                }
            }
        }

        boolean exists(long id) throws SQLException {
            try (Connection c = raw.getConnection();
                    PreparedStatement s = c.prepareStatement("SELECT 1 FROM account WHERE id=?")) {
                s.setLong(1, id);
                try (ResultSet r = s.executeQuery()) {
                    return r.next();
                }
            }
        }

        @Override
        public void close() {
            AtContext.clear();
            AtContext.endUndo();
        }
    }
}

package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.core.*;
import java.sql.*;
import java.util.Collections;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 混合存储（storage.type=hybrid）：全局状态在 Redis、undo 在业务库。
 *
 * <p>这里用 {@link AtDataSourceTest.MemoryRepo} 充当"Redis"（它不是 ConnectionBound、独立存储）， {@link
 * JdbcUndoRepository} 充当业务库里的 undo 存储。要证明的核心命题是：
 *
 * <blockquote>
 *
 * undo 必须与业务 DML 在同一个本地事务里提交——业务回滚时 undo 必须一起消失。
 *
 * </blockquote>
 *
 * 否则恢复调度会拿着一条 undo 去回滚一行<b>从未真正改动过</b>的数据。
 */
class HybridUndoStorageTest {

    @AfterEach
    void clear() {
        AtContext.clear();
        AtContext.endUndo();
    }

    @Test
    void undoDisappearsWhenBusinessTransactionRollsBack() throws Exception {
        try (Fixture f = new Fixture("hybrid-rollback")) {
            AtTransaction tx = f.manager.begin("hybrid", 60000L);
            UndoRecord record = f.record(tx.getXid(), "u-1");

            try (Connection c = f.raw.getConnection()) {
                c.setAutoCommit(false);
                f.manager.append(c, record);
                assertEquals(0, f.countUndoElsewhere(), "业务未提交前另一连接必须看不到 undo");
                c.rollback();
            }
            assertEquals(0, f.countUndoElsewhere());
            assertTrue(f.undo.load(tx.getXid()).isEmpty(), "业务回滚后 undo 必须一起消失——这是混合存储存在的理由");
        }
    }

    @Test
    void undoBecomesVisibleAfterBusinessCommit() throws Exception {
        try (Fixture f = new Fixture("hybrid-commit")) {
            AtTransaction tx = f.manager.begin("hybrid", 60000L);
            UndoRecord record = f.record(tx.getXid(), "u-1");
            try (Connection c = f.raw.getConnection()) {
                c.setAutoCommit(false);
                f.manager.append(c, record);
                c.commit();
            }
            assertEquals(1, f.countUndoElsewhere());
            assertEquals(1, f.undo.load(tx.getXid()).size());
        }
    }

    @Test
    void managerReadsUndoFromTheSeparateStore() throws Exception {
        try (Fixture f = new Fixture("hybrid-merge")) {
            AtTransaction tx = f.manager.begin("hybrid", 60000L);
            try (Connection c = f.raw.getConnection()) {
                c.setAutoCommit(false);
                f.manager.append(c, f.record(tx.getXid(), "u-1"));
                c.commit();
            }
            // 全局事务state在 MemoryRepo（模拟 Redis），undo 在业务库；回滚时必须在两侧之间拼起来。
            f.manager.rollback(tx.getXid());
            assertEquals(1, f.executor.count, "补偿必须执行一次");
            assertEquals(AtStatus.ROLLED_BACK, f.repo.find(tx.getXid()).get().getStatus());
        }
    }

    @Test
    void nonAtomicStoreKeepsUndoAfterBusinessRollback() throws Exception {
        try (Fixture f = new Fixture("hybrid-counterexample")) {
            // 单一非连接绑定存储（模拟"undo 也全放 Redis"）：undo 不参与业务事务。
            AtTransactionManager naive =
                    new AtTransactionManager(f.repo, f.executor, f.locks, 3, "node", 30000L);
            AtTransaction tx = naive.begin("naive", 60000L);
            try (Connection c = f.raw.getConnection()) {
                c.setAutoCommit(false);
                naive.append(c, f.record(tx.getXid(), "u-1"));
                c.rollback();
            }
            assertEquals(
                    1,
                    f.repo.find(tx.getXid()).get().getUndoRecords().size(),
                    "反面教员：业务已回滚而 undo 仍在——纯 Redis 存储就是这个行为（PRODUCTION_GAPS §18.4 问题一）");
        }
    }

    @Test
    void replaceAllAndDeleteByXidMaintainTheUndoTable() throws Exception {
        try (Fixture f = new Fixture("hybrid-crud")) {
            String xid = "xid-crud";
            UndoRecord first = f.record(xid, "u-1");
            UndoRecord second = f.record(xid, "u-2");
            f.undo.replaceAll(xid, java.util.Arrays.asList(first, second));
            assertEquals(2, f.undo.load(xid).size());

            f.undo.replaceAll(xid, Collections.singletonList(first));
            assertEquals(1, f.undo.load(xid).size(), "replaceAll 是整体重写，不增量合并");

            f.undo.deleteByXid(xid);
            assertTrue(f.undo.load(xid).isEmpty(), "清理链路靠它级联删除业务库的 undo");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final JdbcDataSource raw;
        final AtDataSourceTest.MemoryRepo repo;
        final AtDataSourceTest.MemoryLocks locks;
        final JdbcUndoRepository undo;
        final AtTransactionManager manager;
        final CountingExecutor executor = new CountingExecutor();

        Fixture(String name) throws SQLException {
            raw = new JdbcDataSource();
            raw.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            try (Connection c = raw.getConnection();
                    Statement s = c.createStatement()) {
                s.execute(
                        "CREATE TABLE easy_at_undo_log ("
                                + "undo_id VARCHAR(128) PRIMARY KEY,"
                                + "xid VARCHAR(128) NOT NULL,"
                                + "resource_id VARCHAR(128) NOT NULL,"
                                + "table_name VARCHAR(128) NOT NULL,"
                                + "pk_name VARCHAR(128) NOT NULL,"
                                + "pk_value VARCHAR(512) NOT NULL,"
                                + "rollback_sql VARCHAR(512) NOT NULL,"
                                + "rollback_params BLOB NOT NULL,"
                                + "before_image BLOB NULL,"
                                + "after_image BLOB NULL,"
                                + "status VARCHAR(32) NOT NULL,"
                                + "created_at TIMESTAMP NOT NULL,"
                                + "updated_at TIMESTAMP NOT NULL)");
            }
            repo = new AtDataSourceTest.MemoryRepo();
            locks = new AtDataSourceTest.MemoryLocks();
            undo = new JdbcUndoRepository(raw);
            manager =
                    new AtTransactionManager(repo, executor, locks, 3, "hybrid-node", 30000L, undo);
        }

        int countUndoElsewhere() throws SQLException {
            try (Connection c = raw.getConnection();
                    PreparedStatement p =
                            c.prepareStatement("SELECT COUNT(*) FROM easy_at_undo_log");
                    ResultSet r = p.executeQuery()) {
                r.next();
                return r.getInt(1);
            }
        }

        UndoRecord record(String xid, String id) {
            return new UndoRecord(
                    id,
                    xid,
                    "dataSource",
                    "t_account",
                    "id",
                    1L,
                    "UPDATE t_account SET balance=? WHERE id=?",
                    new Object[] {100, 1L});
        }

        @Override
        public void close() {
            try (Connection c = raw.getConnection();
                    Statement s = c.createStatement()) {
                s.execute("DROP TABLE easy_at_undo_log");
            } catch (SQLException ignored) {
            }
        }
    }

    private static final class CountingExecutor implements UndoExecutor {
        int count;

        @Override
        public void rollback(UndoRecord record) {
            count++;
        }
    }
}

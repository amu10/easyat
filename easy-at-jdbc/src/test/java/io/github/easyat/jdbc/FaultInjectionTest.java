package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.core.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 崩溃一致性测试：在 JDBC 调用链的确定时点注入崩溃，验证 easyAt 最终是否仍能把数据收敛到一致状态。
 *
 * <p>这是能否上生产的关键证据。读代码只能说明"看起来对"，而 <b>PRODUCTION_GAPS.md §9</b> 列的七个注入点 （undo 写前/写后、DML
 * 前/后、提交前/后、恢复过程中）此前一项都没有验证过，"回滚正确"缺乏证据支撑。
 *
 * <p>崩溃一律用 SQLState {@code 08006} 的 {@link SQLException} 模拟（见 {@link ChaosDataSource#crash}），
 * 走与真实断连完全相同的异常传播路径，才能真正检验 {@link SqlUndoLogGenerator#abort} 等补偿分支。
 */
class FaultInjectionTest {

    @AfterEach
    void clearContext() {
        AtContext.clear();
        AtContext.endUndo();
    }

    // ---------------------------------------------------------------- 注入点 1

    /** undo log 写入之前崩溃：业务 DML 还没开始，业务行必须保持原值。 */
    @Test
    void crashBeforeUndoWriteLeavesBusinessRowUntouched() throws Exception {
        try (Fixture f = fixture("fixtureBeforeUndo")) {
            f.chaos.failBefore("INSERT INTO easy_at_undo_log", 1);
            AtTransaction tx = f.manager.begin("crash-before-undo", 60000);

            assertCrash(() -> f.updateBalance(tx, 40), "undo 写入前的崩溃必须冒泡到业务");
            assertEquals(1, f.chaos.firedCount(), "故障规则必须命中一次");

            f.rollbackQuietly(tx.getXid());
            assertEquals(100, f.balance(), "业务 DML 未执行，余额必须保持原值");
            assertNoUndoRows(f, tx.getXid());
            assertEquals(AtStatus.ROLLED_BACK, f.status(tx.getXid()));
        }
    }

    // ---------------------------------------------------------------- 注入点 2

    /** undo log 写入之后、after image 回填之前崩溃：整条本地事务回滚，undo 与业务变更一起消失。 */
    @Test
    void crashAfterUndoWriteLeavesBusinessRowUntouched() throws Exception {
        try (Fixture f = fixture("fixtureAfterUndo")) {
            f.chaos.failAfter("INSERT INTO easy_at_undo_log", 1);
            AtTransaction tx = f.manager.begin("crash-after-undo", 60000);

            assertCrash(() -> f.updateBalance(tx, 40), "undo 写入后的崩溃必须冒泡到业务");
            assertEquals(1, f.chaos.firedCount());

            f.rollbackQuietly(tx.getXid());
            assertEquals(100, f.balance());
            assertNoUndoRows(f, tx.getXid());
        }
    }

    // ---------------------------------------------------------------- 注入点 3

    /** 业务 DML 执行之前崩溃：已生成的 undo 必须被 {@code abort} 丢弃，不能留下孤儿补偿记录。 */
    @Test
    void crashBeforeBusinessDmlDiscardsUndo() throws Exception {
        try (Fixture f = fixture("fixtureBeforeDml")) {
            f.chaos.failBefore("UPDATE account SET balance", 1);
            AtTransaction tx = f.manager.begin("crash-before-dml", 60000);

            assertCrash(() -> f.updateBalance(tx, 40), "崩溃必须冒泡到业务层");

            f.rollbackQuietly(tx.getXid());
            assertEquals(100, f.balance());
            assertNoUndoRows(
                    f, tx.getXid(), "Execution was aborted, so the undo must be discarded");
        }
    }

    // ---------------------------------------------------------------- 注入点 4

    /** 业务 DML 执行之后崩溃：undo 已存在但 after image 未回填，本地事务回滚后一切作废。 */
    @Test
    void crashAfterBusinessDmlDiscardsUndo() throws Exception {
        try (Fixture f = fixture("fixtureAfterDml")) {
            f.chaos.failAfter("UPDATE account SET balance", 1);
            AtTransaction tx = f.manager.begin("crash-after-dml", 60000);

            assertCrash(() -> f.updateBalance(tx, 40), "崩溃必须冒泡到业务层");

            f.rollbackQuietly(tx.getXid());
            assertEquals(100, f.balance());
            assertNoUndoRows(f, tx.getXid());
        }
    }

    // ---------------------------------------------------------------- 注入点 5

    /** 本地 COMMIT 之前崩溃：undo 与业务 DML 同处一个本地事务，必须一起回滚。 */
    @Test
    void crashBeforeLocalCommitLeavesRowUntouched() throws Exception {
        try (Fixture f = fixture("fixtureBeforeCommit")) {
            f.chaos.failOnCommit();
            AtTransaction tx = f.manager.begin("crash-before-commit", 60000);

            assertCrash(() -> f.updateBalance(tx, 40), "崩溃必须冒泡到业务层");

            f.rollbackQuietly(tx.getXid());
            assertEquals(100, f.balance(), "未提交的本地事务在连接关闭时应由数据库回滚");
            assertEquals(AtStatus.ROLLED_BACK, f.status(tx.getXid()));
        }
    }

    // ---------------------------------------------------------------- 注入点 6

    /**
     * 本地 COMMIT <b>成功之后</b>崩溃（模拟 ACK 丢失 / 进程随即死亡）。
     *
     * <p>这是最凶险的一种：业务数据与 undo 都已落库且不可自动撤销，只有全局回滚能补偿。若这条用例失败， 意味着"本地提交成功、二阶段中断"会造成永久性的资金偏差。
     */
    @Test
    void crashAfterLocalCommitIsCompensatedByGlobalRollback() throws Exception {
        try (Fixture f = fixture("fixtureAfterCommit")) {
            f.chaos.failAfterCommit();
            AtTransaction tx = f.manager.begin("crash-after-commit", 60000);

            assertCrash(() -> f.updateBalance(tx, 40), "崩溃必须冒泡到业务层");
            assertEquals(40, f.balance(), "本地事务已提交，数据此刻确实是新值");

            f.manager.rollback(tx.getXid());
            assertEquals(100, f.balance(), "全局回滚必须用 undo 把已提交的数据补回来");
            assertEquals(AtStatus.ROLLED_BACK, f.status(tx.getXid()));
            assertEquals(1, f.undoExecuted, "undo 必须真正执行一次");
        }
    }

    // ---------------------------------------------------------------- 注入点 7

    /**
     * 卡在 COMMITTING 的事务必须能被恢复机制捞出来并收敛（P1「COMMITTING 黑洞」修复的回归测试）。
     *
     * <p>此前 {@code recoverable} 不返回 COMMITTING 状态的事务，导致本地已提交、全局未完成的事务永久 残留并泄漏其全局锁。这里反向锁定该行为：先制造一个
     * COMMITTING 事务，证明它既在候选集里， 又能被 {@link AtTransactionManager#finishCommit} 幂等推进到终态。
     */
    @Test
    void committingTransactionIsRecoverableAndConverges() throws Exception {
        try (Fixture f = fixture("fixtureCommitting")) {
            AtTransaction tx = f.manager.begin("stuck-committing", 60000);
            f.updateBalance(tx, 40);
            assertEquals(AtStatus.ACTIVE, f.status(tx.getXid()));

            AtTransaction fresh = f.repository.find(tx.getXid()).get();
            assertTrue(
                    f.repository.transition(
                            tx.getXid(), AtStatus.ACTIVE, fresh.getVersion(), AtStatus.COMMITTING),
                    "模拟二阶段第一步已完成");

            List<String> recoverable = f.recoverableIds(System.currentTimeMillis() + 120000);
            assertTrue(
                    recoverable.contains(tx.getXid()),
                    "COMMITTING 的事务必须出现在 recoverable 候选集中，否则永久泄漏");

            long lockCount = f.lockCount(tx.getXid());
            assertTrue(lockCount > 0, "事务进行中应持有全局行锁");

            f.manager.finishCommit(tx.getXid());
            assertEquals(AtStatus.COMMITTED, f.status(tx.getXid()));
            assertEquals(0, f.lockCount(tx.getXid()), "收敛后全局锁必须释放，否则该行永久不可写");

            // 幂等：重复推进不得改变终态
            f.manager.finishCommit(tx.getXid());
            assertEquals(AtStatus.COMMITTED, f.status(tx.getXid()));

            List<String> after = f.recoverableIds(System.currentTimeMillis() + 120000);
            assertFalse(after.contains(tx.getXid()), "已 COMMITTED 的事务不应再被恢复调度捞起");
        }
    }

    // ---------------------------------------------------------------- 注入点 8

    /**
     * 恢复过程中 undo 反复失败时必须收敛到 MANUAL_INTERVENTION，而不是无限重试。
     *
     * <p>分支回滚每次都失败时，框架应自增重试计数、按退避时间重排，耗尽 {@code maxRetries} 后转人工介入 —— 既不失控重试，也不静默丢弃。
     */
    @Test
    void repeatedRecoveryFailuresConvergeToManualIntervention() throws Exception {
        try (Fixture f = Fixture.failingUndo("fixtureRecoveryFailure", 3)) {
            AtTransaction tx = f.manager.begin("recovery-always-fails", 60000);
            f.updateBalance(tx, 40);
            assertEquals(40, f.balance(), "本地已提交");

            for (int attempt = 0; attempt < 6; attempt++) {
                AtTransaction snapshot = f.repository.find(tx.getXid()).get();
                try {
                    f.manager.recover(snapshot);
                } catch (RuntimeException expected) {
                    // 回滚失败应抛出，由下一次恢复周期重试
                }
            }

            assertEquals(
                    AtStatus.MANUAL_INTERVENTION, f.status(tx.getXid()), "重试耗尽后必须转人工介入并停止自动重试");
            assertFalse(
                    f.recoverableIds(System.currentTimeMillis() + 120000).contains(tx.getXid()),
                    "MANUAL_INTERVENTION 不应再被恢复调度反复捞起");
            assertEquals(40, f.balance(), "回滚确实没有成功，数据留给人工处理（诚实反映现状）");
        }
    }

    /** 确认 undo 执行失败会计入重试次数，避免"失败却计数不涨"导致的无限重试。 */
    @Test
    void failedRecoveryAttemptDoesIncrementRetryCounter() throws Exception {
        try (Fixture f = Fixture.failingUndo("fixtureRetryCounting", 5)) {
            AtTransaction tx = f.manager.begin("retry-counting", 60000);
            f.updateBalance(tx, 40);

            try {
                f.manager.recover(f.repository.find(tx.getXid()).get());
            } catch (RuntimeException expected) {
                // expected
            }
            AtTransaction after = f.repository.find(tx.getXid()).get();
            assertEquals(1, after.getRetries(), "第一次恢复失败后重试计数应为 1");
            assertEquals(AtStatus.ROLLBACK_FAILED, after.getStatus());
        }
    }

    // ---------------------------------------------------------------- 支撑代码

    /**
     * 断言注入的崩溃确实冒泡到业务层。
     *
     * <p>框架会把底层 SQLException 包装成 {@link AtException}（见 {@link SqlUndoLogGenerator#capture}
     * 内部的异常转换），这是合理行为。因此这里不断言具体的异常类型，而是穿透 cause 链确认<b>根因正是注入 的那次崩溃</b>——若根因是别的东西（例如 SQL
     * 语法错误），测试同样会失败，不会放过真问题。
     */
    private static void assertCrash(ExceptionThrowingRunnable body, String message) {
        Throwable thrown = assertThrows(Exception.class, body::run, message);
        Throwable root = rootCause(thrown);
        String detail = String.valueOf(root.getMessage());
        assertTrue(
                detail.contains("Simulated crash"),
                message + "；实际根因并非注入的崩溃，而是: " + root.getClass().getName() + ": " + detail);
    }

    private static Throwable rootCause(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current)
            current = current.getCause();
        return current;
    }

    /** 允许抛出受检异常的 lambda 类型，避免每个断言写 try/catch。 */
    private interface ExceptionThrowingRunnable {
        void run() throws Exception;
    }

    private static Fixture fixture(String name) throws Exception {
        return new Fixture(name, false, 3);
    }

    /** 一次性 Fixture：H2 内存库 + chaos 层 + 框架各组件，统一在 {@link #close()} 释放。 */
    private static final class Fixture implements AutoCloseable {
        final JdbcDataSource raw;
        final ChaosDataSource chaos;
        final JdbcAtRepository repository;
        final JdbcGlobalLockManager locks;
        final CountingUndoExecutor undo;
        final CountableReleaseLocks trackedLocks;
        final AtTransactionManager manager;
        final AtDataSource dataSource;
        int undoExecuted;

        Fixture(String name, boolean failUndo, int maxRetries) throws Exception {
            raw = new JdbcDataSource();
            raw.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            createSchema(raw);
            chaos = new ChaosDataSource(raw);
            repository = new JdbcAtRepository(chaos);
            locks = new JdbcGlobalLockManager(chaos, 60000);
            trackedLocks = new CountableReleaseLocks(locks);
            undo = new CountingUndoExecutor(this, failUndo);
            manager = new AtTransactionManager(repository, undo, trackedLocks, maxRetries);
            dataSource = new AtDataSource("dataSource", chaos, manager, trackedLocks);
        }

        /** 构造一个「undo 执行必定失败」的 Fixture，用于验证恢复侧的重试与收敛。 */
        static Fixture failingUndo(String name, int maxRetries) throws Exception {
            return new Fixture(name, true, maxRetries);
        }

        /** 执行一次受 AT 保护的业务 UPDATE（余额原值 100）。 */
        void updateBalance(AtTransaction tx, int newValue) throws SQLException {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement p =
                        c.prepareStatement("UPDATE account SET balance=? WHERE id=?")) {
                    p.setInt(1, newValue);
                    p.setLong(2, 1L);
                    p.executeUpdate();
                }
                c.commit();
            }
        }

        void rollbackQuietly(String xid) {
            try {
                manager.rollback(xid);
            } catch (RuntimeException ignored) {
                // 用例关注的是数据最终状态而非这里的抛出与否
            }
        }

        int balance() throws SQLException {
            try (Connection c = raw.getConnection();
                    Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("SELECT balance FROM account WHERE id=1")) {
                assertTrue(r.next());
                return r.getInt(1);
            }
        }

        AtStatus status(String xid) {
            return repository.find(xid).get().getStatus();
        }

        List<String> recoverableIds(long now) {
            List<String> ids = new ArrayList<String>();
            for (AtTransaction t : repository.recoverable(now, 100)) ids.add(t.getXid());
            return ids;
        }

        long lockCount(String xid) throws SQLException {
            try (Connection c = raw.getConnection();
                    PreparedStatement p =
                            c.prepareStatement("SELECT COUNT(*) FROM easy_at_lock WHERE xid=?")) {
                p.setString(1, xid);
                try (ResultSet r = p.executeQuery()) {
                    r.next();
                    return r.getLong(1);
                }
            }
        }

        public void close() {
            locks.close();
            AtContext.clear();
            AtContext.endUndo();
        }
    }

    /** 统计并被可选地失败化的 undo 执行器。 */
    private static final class CountingUndoExecutor implements UndoExecutor {
        private final Fixture fixture;
        private final boolean fail;

        CountingUndoExecutor(Fixture fixture, boolean fail) {
            this.fixture = fixture;
            this.fail = fail;
        }

        @Override
        public void rollback(UndoRecord record) throws Exception {
            if (fail) throw new SQLException("Simulated undo failure", "40001", 1213);
            new JdbcUndoExecutor(
                            Collections.<String, DataSource>singletonMap(
                                    "dataSource", fixture.chaos))
                    .rollback(record);
            fixture.undoExecuted++;
        }
    }

    /** 透传的锁管理器包装，仅用于统一生命周期。 */
    private static final class CountableReleaseLocks implements GlobalLockManager {
        private final JdbcGlobalLockManager delegate;

        CountableReleaseLocks(JdbcGlobalLockManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public void acquire(String resource, String table, String key, String xid) {
            delegate.acquire(resource, table, key, xid);
        }

        @Override
        public void releaseByXid(String xid) {
            delegate.releaseByXid(xid);
        }
    }

    private static void assertNoUndoRows(Fixture f, String xid) throws SQLException {
        assertNoUndoRows(f, xid, null);
    }

    private static void assertNoUndoRows(Fixture f, String xid, String message)
            throws SQLException {
        try (Connection c = f.raw.getConnection();
                PreparedStatement p =
                        c.prepareStatement("SELECT COUNT(*) FROM easy_at_undo_log WHERE xid=?")) {
            p.setString(1, xid);
            try (ResultSet r = p.executeQuery()) {
                r.next();
                assertEquals(0, r.getInt(1), message == null ? "不应残留 undo 记录" : message);
            }
        }
    }

    private static void createSchema(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement()) {
            s.execute("CREATE TABLE account (id BIGINT PRIMARY KEY, balance INT)");
            s.execute("INSERT INTO account(id,balance) VALUES(1,100)");
            s.execute(
                    "CREATE TABLE easy_at_global (xid VARCHAR(128) PRIMARY KEY,name VARCHAR(255),status VARCHAR(32),timeout_at TIMESTAMP,retry_count INT,next_retry_at TIMESTAMP,version BIGINT,owner VARCHAR(128),lease_until TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP)");
            s.execute(
                    "CREATE TABLE easy_at_undo_log (undo_id VARCHAR(128) PRIMARY KEY,xid VARCHAR(128),resource_id VARCHAR(255),table_name VARCHAR(255),pk_name VARCHAR(255),pk_value VARCHAR(512),rollback_sql VARCHAR(1000),rollback_params BLOB,before_image BLOB,after_image BLOB,status VARCHAR(32),created_at TIMESTAMP,updated_at TIMESTAMP)");
            s.execute(
                    "CREATE TABLE easy_at_lock (resource_id VARCHAR(255),table_name VARCHAR(255),pk_value VARCHAR(512),xid VARCHAR(128),lease_until TIMESTAMP,created_at TIMESTAMP,PRIMARY KEY(resource_id,table_name,pk_value))");
        }
    }
}

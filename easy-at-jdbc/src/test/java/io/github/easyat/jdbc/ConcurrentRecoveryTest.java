package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.core.*;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 多实例并发正确性测试。
 *
 * <p>生产上 easyAt 一定跑在多个实例上：多个 JVM 同时扫描同一张 {@code easy_at_global}、同时回滚同一个 XID、
 * 同时争抢同一行的全局锁。单线程测试全绿完全不能说明这些路径是对的——而"重复补偿"恰恰是分布式事务里最直接 的资金事故来源（同一笔钱被退两次）。
 */
class ConcurrentRecoveryTest {

    @AfterEach
    void clearContext() {
        AtContext.clear();
        AtContext.endUndo();
    }

    /**
     * 多个实例同时回滚同一个全局事务时，补偿必须恰好执行一次。
     *
     * <p>保证来自 {@link AtRepository#transition} 的 CAS：只有先把状态从 ACTIVE 抢成 ROLLING_BACK 的实例 才会真正执行
     * undo，其余实例拿到 false 后放弃。若这里出现两次补偿，意味着钱会被退两遍。
     */
    @Test
    void concurrentRollbackCompensatesExactlyOnce() throws Exception {
        try (Fixture f = Fixture.create("concurrentRollback", 3)) {
            AtTransaction tx = f.manager.begin("concurrent-rollback", 60000);
            f.updateBalance(tx, 40);
            assertEquals(40, f.balance(), "前置条件：本地已提交");

            int threads = 8;
            // CAS 竞争只有一个赢家真正执行补偿；其余线程必须收到明确的冲突信号，
            // 而不是被静默吞掉（那样调用方会误以为自己也回滚成功了）。
            List<Throwable> collisions =
                    runConcurrently(threads, () -> f.manager.rollback(tx.getXid()));

            assertEquals(1, f.undoCount.get(), "补偿必须恰好执行一次，重复补偿等于重复退钱");
            assertEquals(100, f.balance(), "回滚后余额必须回到原值");
            assertEquals(AtStatus.ROLLED_BACK, f.status(tx.getXid()));
            assertAllLosersAreExplicitConflicts(collisions, threads);
        }
    }

    /** 竞争失败方必须抛出可读的冲突异常；沉默或莫名其妙的失败都视为回归。 */
    private static void assertAllLosersAreExplicitConflicts(
            List<Throwable> collisions, int threads) {
        assertTrue(
                collisions.size() >= threads - 1,
                "除赢家外其余线程都应因 CAS 失败而收到冲突信号，实际异常数=" + collisions.size());
        for (Throwable t : collisions) {
            assertTrue(
                    t instanceof AtException && t.getMessage().contains("Conflict rolling back"),
                    "并发回滚的竞争失败方必须抛出明确的冲突异常（让调用方能识别出「另一个实例正在处理」），实际却是: "
                            + t.getClass().getName()
                            + ": "
                            + t.getMessage());
        }
    }

    /**
     * 多个恢复实例同时处理同一个超时事务时也必须只补偿一次。
     *
     * <p>与 {@link #concurrentRollbackCompensatesExactlyOnce} 的区别：这里走的是完整的恢复路径 （自增重试计数 → 写退避时间 →
     * 回滚），多实例竞争发生在更大的时间窗内。
     */
    @Test
    void concurrentRecoveryCompensatesExactlyOnce() throws Exception {
        try (Fixture f = Fixture.create("concurrentRecovery", 5)) {
            AtTransaction tx = f.manager.begin("concurrent-recovery", 1);
            f.updateBalance(tx, 40);

            int threads = 6;
            // 与并发回滚同理：多实例走完整恢复路径时，竞争失败方也必须显式报冲突
            List<Throwable> collisions =
                    runConcurrently(
                            threads, () -> f.manager.recover(f.repository.find(tx.getXid()).get()));

            assertEquals(1, f.undoCount.get(), "多实例并发恢复同样只能补偿一次");
            assertEquals(100, f.balance());
            assertEquals(AtStatus.ROLLED_BACK, f.status(tx.getXid()));
            assertAllLosersAreExplicitConflicts(collisions, threads);
        }
    }

    /**
     * 同一行数据的全局锁只能被一个 XID 持有，其余必须立刻失败而不是排队蒙混过关。
     *
     * <p>这是 AT 模式防脏写的第一道闸门：如果两个并发事务都以为自己拿到了行锁，它们的 undo 就会互相覆盖。
     */
    @Test
    void globalRowLockIsMutuallyExclusive() throws Exception {
        try (Fixture f = Fixture.create("lockContention", 3)) {
            int threads = 8;
            AtomicInteger acquired = new AtomicInteger();
            AtomicInteger conflicts = new AtomicInteger();
            List<Throwable> failures =
                    runConcurrently(
                            threads,
                            () -> {
                                // 关键：锁的持有者必须是 easy_at_global 中真实存在且仍在途（ACTIVE）的事务。
                                // 若 XID 不在表中，锁管理器会把它判为孤儿事务而允许抢占——那是设计行为，
                                // 会掩盖真正的互斥性问题。
                                AtTransaction own = f.manager.begin("lock-holder", 60000);
                                try {
                                    f.locks.acquire("dataSource", "account", "1", own.getXid(), 0L);
                                    acquired.incrementAndGet();
                                } catch (GlobalLockConflictException expected) {
                                    conflicts.incrementAndGet();
                                } finally {
                                    AtContext.clear();
                                }
                            });

            assertTrue(failures.isEmpty(), "不应出现预期外的异常: " + failures);
            assertEquals(1, acquired.get(), "同一行的全局锁只能被一个持有者取得");
            assertEquals(threads - 1, conflicts.get(), "其余竞争者必须得到明确的冲突信号");

            f.locks.releaseByXid(firstLockedXid(f));
            f.locks.acquire("dataSource", "account", "1", "xid-next", 0L);
            assertEquals(1, f.lockCount("xid-next"), "持有者释放后该行必须重新可锁");
        }
    }

    /** 不同主键之间不应互相阻塞，否则并发度会塌到 1。 */
    @Test
    void globalRowLocksAreIndependentPerKey() throws Exception {
        try (Fixture f = Fixture.create("independentLocks", 3)) {
            int threads = 6;
            AtomicInteger acquired = new AtomicInteger();
            List<Throwable> failures =
                    runConcurrently(
                            threads,
                            () -> {
                                String key = String.valueOf(Thread.currentThread().getId());
                                f.locks.acquire("dataSource", "account", key, "xid-" + key, 0L);
                                acquired.incrementAndGet();
                            });

            assertTrue(failures.isEmpty(), "不同主键不应冲突: " + failures);
            assertEquals(threads, acquired.get(), "不同主键必须可并行持有");
        }
    }

    /**
     * 恢复租约必须互斥：同一时刻只有一个实例能接管某个 XID 的恢复工作。
     *
     * <p>租约是"多实例同时扫描同一批待恢复事务"时不重复处理的唯一凭据。它失效意味着同一事务会被重复推进。
     */
    @Test
    void recoveryLeaseIsMutuallyExclusive() throws Exception {
        try (Fixture f = Fixture.create("leaseMutex", 3)) {
            AtTransaction tx = f.manager.begin("lease", 60000);
            String xid = tx.getXid();
            long now = System.currentTimeMillis();

            assertTrue(f.repository.claimLease(xid, "instance-A", now + 30000, now));
            assertFalse(f.repository.claimLease(xid, "instance-B", now + 30000, now), "他人持有期间不得抢占");
            assertTrue(f.repository.claimLease(xid, "instance-A", now + 30000, now), "同一持有者应可重入续租");

            f.repository.releaseLease(xid, "instance-A");
            assertTrue(f.repository.claimLease(xid, "instance-B", now + 30000, now), "释放后必须可被接管");
        }
    }

    /**
     * 并发抢占同一 XID 的恢复租约时，必须恰好一个成功。
     *
     * <p>与上一条互补：单个 CAS 正确不等于高并发下正确（可能读到同一个"空闲"快照后一起写入）。
     */
    @Test
    void concurrentLeaseClaimElectsExactlyOneOwner() throws Exception {
        try (Fixture f = Fixture.create("leaseRace", 3)) {
            AtTransaction tx = f.manager.begin("lease-race", 60000);
            String xid = tx.getXid();
            AtomicInteger winners = new AtomicInteger();
            long until = System.currentTimeMillis() + 30000;

            List<Throwable> failures =
                    runConcurrently(
                            8,
                            () -> {
                                if (f.repository.claimLease(
                                        xid,
                                        "instance-" + Thread.currentThread().getId(),
                                        until,
                                        System.currentTimeMillis())) winners.incrementAndGet();
                            });

            assertTrue(failures.isEmpty(), "不应出现预期外的异常: " + failures);
            assertEquals(1, winners.get(), "并发抢占租约必须恰好一个赢家，否则事务会被重复恢复");
        }
    }

    /**
     * 长事务的锁必须能被续租线程保住，不会被自己过期。
     *
     * <p>这是"业务执行时间超过租约"场景的核心保障：若不续租，另一个实例会误判持有者已死并接管，两个写入者 同时改同一行，AT 的隔离保证就破了。
     */
    @Test
    void longRunningTransactionKeepsItsLockThroughRenewal() throws Exception {
        try (Fixture f = Fixture.create("renewal", 3)) {
            AtTransaction tx = f.manager.begin("long-running", 60000);
            f.locks.acquire("dataSource", "account", "1", tx.getXid(), 0L);
            long initialLease = f.leaseUntil(tx.getXid());

            Thread.sleep(1200); // 远超 lease/3 的续租周期（400ms）
            long renewedLease = f.leaseUntil(tx.getXid());

            assertTrue(
                    renewedLease > initialLease,
                    "续租必须把 lease_until 往后推，否则长事务的锁会被误判过期: initial="
                            + initialLease
                            + ", renewed="
                            + renewedLease);
            assertEquals(AtStatus.ACTIVE, f.status(tx.getXid()));
        }
    }

    /**
     * 持锁事务已终结时，即使租约还有很久，锁也必须能被立即回收。
     *
     * <p>否则一个已回滚事务残留的锁会把该行永久锁死，且没有任何告警。
     */
    @Test
    void terminalTransactionLocksAreReclaimedImmediately() throws Exception {
        try (Fixture f = Fixture.create("terminalReclaim", 3)) {
            AtTransaction tx = f.manager.begin("terminal", 60000);
            f.locks.acquire("dataSource", "account", "1", tx.getXid(), 0L);
            f.manager.rollback(tx.getXid());
            assertEquals(AtStatus.ROLLED_BACK, f.status(tx.getXid()));

            f.locks.acquire("dataSource", "account", "1", "xid-fresh", 0L);
            assertEquals(1, f.lockCount("xid-fresh"), "已终结事务的锁必须立即可被接管");
        }
    }

    /**
     * 跨实例并发恢复（各自独立 owner / 独立租约）时，补偿必须只发生一次。
     *
     * <p>这是真实集群形态：一个实例正在回滚，另一个实例因为该补偿耗时超过租约而接管。<b>修复前</b>后到者会因为 看到状态已是 ROLLING_BACK 而跳过 {@code
     * ACTIVE→ROLLING_BACK} 的 CAS 直接闯进补偿循环，第二次执行同一条 undo 触发脏写校验，把一笔已经正确回滚的事务误报成 {@link
     * AtStatus#DIRTY_WRITE} 丢给人工处理——典型危害是 制造无穷无尽的假性人工介入工单。
     */
    @Test
    void concurrentRecoveryAcrossInstancesCompensatesOnce() throws Exception {
        try (Fixture f = Fixture.create("crossInstanceRecovery", 5)) {
            AtTransaction tx = f.manager.begin("cross-instance", 1);
            f.updateBalance(tx, 40);

            int instances = 5;
            // 模拟 5 个实例各自持有独立 manager（不同 owner）同时扫描到同一笔待恢复事务
            List<AtTransactionManager> managers = new ArrayList<AtTransactionManager>();
            for (int i = 0; i < instances; i++) managers.add(f.newManager("node-" + i, 5));

            List<Throwable> collisions =
                    runConcurrently(
                            instances,
                            () -> {
                                int index = (int) (Thread.currentThread().getId() % instances);
                                AtTransaction snapshot = f.repository.find(tx.getXid()).get();
                                managers.get(Math.abs(index)).recover(snapshot);
                            });

            assertEquals(1, f.undoCount.get(), "跨实例并发恢复也必须只补偿一次");
            assertEquals(100, f.balance(), "数据必须回到原值");
            assertEquals(
                    AtStatus.ROLLED_BACK,
                    f.status(tx.getXid()),
                    "不得因为并发竞争把已正确回滚的事务误判成 DIRTY_WRITE (dirty table="
                            + f.repository.find(tx.getXid()).get().getDirtyWriteTable()
                            + ")");
            assertAllLosersAreExplicitConflicts(collisions, instances);
        }
    }

    // ---------------------------------------------------------------- 支撑代码

    /** 并发执行同一任务 {@code threads} 次，返回所有未被静默处理的异常。 */
    private static List<Throwable> runConcurrently(int threads, Runnable body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<Future<?>>();
        for (int i = 0; i < threads; i++)
            futures.add(
                    pool.submit(
                            () -> {
                                start.await();
                                body.run();
                                return null;
                            }));
        start.countDown();
        List<Throwable> failures = new ArrayList<Throwable>();
        for (Future<?> future : futures) {
            try {
                future.get(20, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                failures.add(e.getCause());
            } catch (TimeoutException e) {
                failures.add(e);
            }
        }
        pool.shutdownNow();
        return failures;
    }

    private static String firstLockedXid(Fixture f) throws SQLException {
        try (Connection c = f.raw.getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery("SELECT xid FROM easy_at_lock")) {
            assertTrue(r.next());
            return r.getString(1);
        }
    }

    /** H2 内存库 + 框架组件的一次性 Fixture。 */
    private static final class Fixture implements AutoCloseable {
        final JdbcDataSource raw;
        final JdbcAtRepository repository;
        final JdbcGlobalLockManager locks;
        final AtomicInteger undoCount = new AtomicInteger();
        final CountingUndoExecutor counting;
        final AtTransactionManager manager;
        final AtDataSource dataSource;

        private Fixture(String name, int maxRetries) throws Exception {
            raw = new JdbcDataSource();
            raw.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            createSchema(raw);
            // 短租约（1.2s）让续租周期落到约 400ms，续租用例无需长时间等待
            locks = new JdbcGlobalLockManager(raw, 1200L);
            repository = new JdbcAtRepository(raw);
            counting = new CountingUndoExecutor(raw, undoCount);
            manager = newManager("node-local", maxRetries);
            dataSource = new AtDataSource("dataSource", raw, manager, locks);
        }

        /** 模拟集群里另一个实例：独立的 manager（独立 owner/租约），共享同一份存储。 */
        AtTransactionManager newManager(String owner, int maxRetries) {
            return new AtTransactionManager(repository, counting, locks, maxRetries, owner, 30000L);
        }

        static Fixture create(String name, int maxRetries) throws Exception {
            return new Fixture(name, maxRetries);
        }

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

        long leaseUntil(String xid) throws SQLException {
            try (Connection c = raw.getConnection();
                    PreparedStatement p =
                            c.prepareStatement(
                                    "SELECT lease_until FROM easy_at_lock WHERE xid=?")) {
                p.setString(1, xid);
                try (ResultSet r = p.executeQuery()) {
                    assertTrue(r.next(), "该 XID 应持有锁");
                    return r.getTimestamp(1).getTime();
                }
            }
        }

        public void close() {
            locks.close();
            AtContext.clear();
            AtContext.endUndo();
        }
    }

    /** 统计 undo 执行次数的 undo 执行器（跨实例场景可共享计数）。 */
    private static final class CountingUndoExecutor implements UndoExecutor {
        private final DataSource target;
        private final AtomicInteger counter;

        CountingUndoExecutor(DataSource target, AtomicInteger counter) {
            this.target = target;
            this.counter = counter;
        }

        @Override
        public void rollback(UndoRecord record) throws Exception {
            new JdbcUndoExecutor(Collections.<String, DataSource>singletonMap("dataSource", target))
                    .rollback(record);
            counter.incrementAndGet();
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

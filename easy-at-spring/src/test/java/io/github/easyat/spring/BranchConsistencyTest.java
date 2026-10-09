package io.github.easyat.spring;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.core.*;
import io.github.easyat.jdbc.JdbcAtRepository;
import io.github.easyat.jdbc.JdbcBranchRepository;
import java.sql.*;
import java.util.List;
import java.util.function.Supplier;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * 分支层与全局层的<b>数据一致性</b>回归测试。这几条曾经都是真实漏洞：rollback 先到会无限重试、 迟到的业务请求会写入无人补偿的悬挂数据、COMMITTING
 * 卡死导致锁泄漏、失败分支被 commit 洗白。
 */
class BranchConsistencyTest {
    private static final String RESOURCE = "db1";

    @Test
    void emptyRollbackPersistsPlaceholderAndBlocksLateDml() throws Exception {
        JdbcDataSource ds = database("emptyRollback");
        JdbcAtRepository globals = new JdbcAtRepository(ds);
        JdbcBranchRepository branches = new JdbcBranchRepository(ds);
        AtTransactionManager manager = new AtTransactionManager(globals, record -> {}, null, 3);
        AtTransaction tx = manager.begin("order", 60000);
        BranchCoordinator coordinator = new BranchCoordinator(branches, manager, null, "svc", null);

        // rollback 先于任何 DML 到达：此刻本地还没有任何分支记录
        assertEquals(
                BranchStatus.ROLLED_BACK,
                coordinator.rollbackBranch("never-registered", tx.getXid(), RESOURCE));

        // 必须留下占位记录，它同时是幂等凭证和"本资源已回滚"的证据
        AtBranch placeholder = branches.findByXidResource(tx.getXid(), RESOURCE).get();
        assertEquals(BranchStatus.ROLLED_BACK, placeholder.getStatus());

        // 迟到的业务 DML 必须被拒绝——否则 undo 会挂在已终结的 XID 下成为永久悬挂数据
        BranchRegistrar registrar = new DefaultBranchRegistrar(supplier(branches), "svc");
        assertThrows(AtException.class, () -> registrar.register(tx.getXid(), RESOURCE));
        assertThrows(
                AtException.class, () -> coordinator.register(tx.getXid(), RESOURCE, "svc", null));
    }

    @Test
    void repeatedEmptyRollbackIsIdempotent() throws Exception {
        JdbcDataSource ds = database("emptyRollbackIdempotent");
        JdbcAtRepository globals = new JdbcAtRepository(ds);
        JdbcBranchRepository branches = new JdbcBranchRepository(ds);
        AtTransactionManager manager = new AtTransactionManager(globals, record -> {}, null, 3);
        AtTransaction tx = manager.begin("order", 60000);
        BranchCoordinator coordinator = new BranchCoordinator(branches, manager, null, "svc", null);

        assertEquals(
                BranchStatus.ROLLED_BACK,
                coordinator.rollbackBranch("late-branch", tx.getXid(), RESOURCE));
        assertEquals(
                BranchStatus.ROLLED_BACK,
                coordinator.rollbackBranch("late-branch", tx.getXid(), RESOURCE));

        // 重复回滚不能产生重复分支行
        assertEquals(1, branches.byXid(tx.getXid()).size());
    }

    @Test
    void failedRollbackIsNotWhitewashedByCommit() throws Exception {
        JdbcDataSource ds = database("noWhitewash");
        JdbcAtRepository globals = new JdbcAtRepository(ds);
        JdbcBranchRepository branches = new JdbcBranchRepository(ds);
        AtTransactionManager manager = new AtTransactionManager(globals, record -> {}, null, 3);
        AtTransaction tx = manager.begin("order", 60000);
        BranchCoordinator coordinator = new BranchCoordinator(branches, manager, null, "svc", null);
        String branchId = coordinator.register(tx.getXid(), RESOURCE, "svc", null);

        assertTrue(
                branches.transition(
                        branchId, BranchStatus.REGISTERED, BranchStatus.ROLLBACK_FAILED));

        // 乱序到达的 commit 不能把回滚失败洗成"已提交"，否则不一致被静默固化
        assertEquals(BranchStatus.ROLLBACK_FAILED, coordinator.commitBranch(branchId));
        assertEquals(BranchStatus.ROLLBACK_FAILED, branches.find(branchId).get().getStatus());
    }

    @Test
    void localRollbackAdvancesBranchStateExactlyOnce() throws Exception {
        JdbcDataSource ds = database("localRollback");
        JdbcAtRepository globals = new JdbcAtRepository(ds);
        JdbcBranchRepository branches = new JdbcBranchRepository(ds);
        AtTransactionManager manager = new AtTransactionManager(globals, record -> {}, null, 3);
        AtTransaction tx = manager.begin("order", 60000);
        BranchCoordinator coordinator = new BranchCoordinator(branches, manager, null, "svc", null);
        String branchId = coordinator.register(tx.getXid(), RESOURCE, "svc", null);

        assertEquals(BranchStatus.ROLLED_BACK, coordinator.rollbackBranch(branchId));
        assertEquals(AtStatus.ROLLED_BACK, globals.find(tx.getXid()).get().getStatus());

        // 终态幂等：重复回滚不改变结果
        assertEquals(BranchStatus.ROLLED_BACK, coordinator.rollbackBranch(branchId));
        assertEquals(BranchStatus.ROLLED_BACK, branches.find(branchId).get().getStatus());
    }

    @Test
    void committingTransactionIsRecoveredAndLocksReleased() throws Exception {
        JdbcDataSource ds = database("committingBlackHole");
        JdbcAtRepository globals = new JdbcAtRepository(ds);
        CountingLocks locks = new CountingLocks();
        AtTransactionManager manager = new AtTransactionManager(globals, record -> {}, locks, 3);
        AtTransaction tx = manager.begin("order", 60000);

        // 模拟本地已提交但 ACTIVE→COMMITTING→COMMITTED 的第二步失败（超时前，故不算超时事务）
        assertTrue(globals.transition(tx.getXid(), AtStatus.ACTIVE, 0, AtStatus.COMMITTING));

        // 卡住的 COMMITTING 必须能被恢复扫描捞到，否则事务和全局锁永久泄漏
        List<AtTransaction> recoverable = globals.recoverable(System.currentTimeMillis(), 10);
        assertEquals(1, recoverable.size());
        assertEquals(tx.getXid(), recoverable.get(0).getXid());

        manager.finishCommit(tx.getXid());
        assertEquals(AtStatus.COMMITTED, globals.find(tx.getXid()).get().getStatus());
        assertEquals(1, locks.releases);
    }

    @Test
    void joinRejectsNonActiveTransaction() throws Exception {
        JdbcDataSource ds = database("joinGuard");
        JdbcAtRepository globals = new JdbcAtRepository(ds);
        AtTransactionManager manager = new AtTransactionManager(globals, record -> {}, null, 3);
        AtTransaction tx = manager.begin("order", 60000);
        manager.rollback(tx.getXid());
        assertEquals(AtStatus.ROLLED_BACK, globals.find(tx.getXid()).get().getStatus());

        // 迟到的跨服务请求若放行，会在已回滚的事务下产生无人补偿的数据
        assertThrows(AtException.class, () -> manager.join(tx.getXid()));
    }

    @Test
    void appendRefusesUndoAfterGlobalRollback() throws Exception {
        JdbcDataSource ds = database("appendGuard");
        JdbcAtRepository globals = new JdbcAtRepository(ds);
        AtTransactionManager manager = new AtTransactionManager(globals, record -> {}, null, 3);
        AtTransaction tx = manager.begin("order", 60000);
        manager.append(undo(tx.getXid(), "u-1"));
        manager.rollback(tx.getXid());

        assertThrows(AtException.class, () -> manager.append(undo(tx.getXid(), "u-2")));
    }

    @Test
    void exhaustedDeliveryRetriesEscalateToManualIntervention() throws Exception {
        JdbcDataSource ds = database("branchRetryLimit");
        JdbcAtRepository globals = new JdbcAtRepository(ds);
        JdbcBranchRepository branches = new JdbcBranchRepository(ds);
        AtTransactionManager manager = new AtTransactionManager(globals, record -> {}, null, 3);
        AtTransaction tx = manager.begin("order", 60000);
        BranchCoordinator coordinator = new BranchCoordinator(branches, manager, null, "svc", null);
        String branchId = coordinator.register(tx.getXid(), RESOURCE, "svc", null);
        assertTrue(
                branches.transition(branchId, BranchStatus.REGISTERED, BranchStatus.ROLLING_BACK));
        branches.updateRecovery(branchId, 9, 0L); // 已重试 9 次

        BranchRetryScheduler scheduler =
                new BranchRetryScheduler(branches, coordinator, 1000L, 10, 2, null);
        try {
            scheduler.triggerNow();
            // 重试耗尽必须收敛到人工介入，而不是无限重试却无人知晓
            assertEquals(
                    BranchStatus.MANUAL_INTERVENTION, branches.find(branchId).get().getStatus());
            assertTrue(branches.pendingActions(System.currentTimeMillis(), 10).isEmpty());
        } finally {
            scheduler.close();
        }
    }

    private static UndoRecord undo(String xid, String id) {
        return new UndoRecord(
                id,
                xid,
                RESOURCE,
                "account",
                "id",
                "1",
                "UPDATE account SET balance=? WHERE id=?",
                new Object[] {100, 1});
    }

    private static Supplier<BranchRepository> supplier(BranchRepository repo) {
        return new Supplier<BranchRepository>() {
            @Override
            public BranchRepository get() {
                return repo;
            }
        };
    }

    private static JdbcDataSource database(String name) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE easy_at_global (xid VARCHAR(128) PRIMARY KEY,name VARCHAR(255),status VARCHAR(32),timeout_at TIMESTAMP,retry_count INT,next_retry_at TIMESTAMP,version BIGINT,owner VARCHAR(128),lease_until TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP)");
            statement.execute(
                    "CREATE TABLE easy_at_undo_log (undo_id VARCHAR(128) PRIMARY KEY,xid VARCHAR(128),resource_id VARCHAR(255),table_name VARCHAR(255),pk_name VARCHAR(255),pk_value VARCHAR(512),rollback_sql VARCHAR(1000),rollback_params BLOB,before_image BLOB,after_image BLOB,status VARCHAR(32),created_at TIMESTAMP,updated_at TIMESTAMP)");
            statement.execute(
                    "CREATE TABLE easy_at_branch (branch_id VARCHAR(128) PRIMARY KEY,xid VARCHAR(128),resource_id VARCHAR(255),status VARCHAR(32),service_name VARCHAR(255),callback_url VARCHAR(512),sequence INT,retry_count INT,next_retry_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP)");
        }
        return dataSource;
    }

    private static final class CountingLocks implements GlobalLockManager {
        int releases;

        @Override
        public void acquire(String resource, String table, String key, String xid) {}

        @Override
        public void releaseByXid(String xid) {
            releases++;
        }
    }
}

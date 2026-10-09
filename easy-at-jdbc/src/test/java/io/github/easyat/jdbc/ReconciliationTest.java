package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.core.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * 影子运行对账的回归用例。
 *
 * <p>为什么必须测：对账报告一旦"永远返回 OK"，上线后所有静默故障都会被当成健康信号。 所以这里刻意造出每一种异常（超时未收敛 / 回滚卡住 / COMMITTING 黑洞 / 锁泄漏 /
 * 人工介入）， 断言报告<b>必须</b>报出来；再断言干净环境报告为空。
 */
class ReconciliationTest {

    @Test
    void cleanEnvironmentReportsNoProblems() throws Exception {
        JdbcDataSource ds = database("recon-clean");
        JdbcAtRepository repository = new JdbcAtRepository(ds);
        JdbcGlobalLockManager locks = new JdbcGlobalLockManager(ds, 60000L);

        long now = System.currentTimeMillis();
        repository.create(new AtTransaction("ok-1", "ok", now, now + 600000L));

        ReconciliationReport report = service(repository, locks).report(now + 1000);
        assertEquals(0, report.problemCount(), "健康环境不得报异常: " + report);
        assertEquals(ReconciliationReport.Level.OK, report.level());
        assertTrue(report.toMap().containsKey("counts"));
        locks.close();
    }

    @Test
    void detectsTimedOutActiveTransaction() throws Exception {
        JdbcDataSource ds = database("recon-active");
        JdbcAtRepository repository = new JdbcAtRepository(ds);
        JdbcGlobalLockManager locks = new JdbcGlobalLockManager(ds, 60000L);

        long now = System.currentTimeMillis();
        // deadline 已过，且又超过了 stuckAfterMillis(60s) 的宽限期仍没人接管
        repository.create(new AtTransaction("late-1", "late", now - 180000, now - 120000));

        ReconciliationReport report = service(repository, locks).report(now);
        assertEquals(1, report.getActiveTimedOut().size());
        assertTrue(report.getActiveTimedOut().contains("late-1"));
        assertEquals(ReconciliationReport.Level.WARN, report.level());
        locks.close();
    }

    @Test
    void detectsStuckRollingBackAndCommitting() throws Exception {
        JdbcDataSource ds = database("recon-stuck");
        JdbcAtRepository repository = new JdbcAtRepository(ds);
        JdbcGlobalLockManager locks = new JdbcGlobalLockManager(ds, 60000L);
        long now = System.currentTimeMillis();

        AtTransaction rolling = new AtTransaction("rb-1", "rb", now, now + 60000L);
        repository.create(rolling);
        assertTrue(repository.transition("rb-1", AtStatus.ACTIVE, 0, AtStatus.ROLLING_BACK));

        AtTransaction committing = new AtTransaction("cm-1", "cm", now, now + 60000L);
        repository.create(committing);
        assertTrue(repository.transition("cm-1", AtStatus.ACTIVE, 0, AtStatus.COMMITTING));

        ReconciliationReport report = service(repository, locks).report(now);
        assertEquals(1, report.getRollingBackStuck().size(), "回滚卡住必须被报出");
        assertEquals(1, report.getCommittingStuck().size(), "COMMITTING 黑洞必须被报出");
        assertTrue(report.getRollingBackStuck().contains("rb-1"));
        assertTrue(report.getCommittingStuck().contains("cm-1"));
        locks.close();
    }

    @Test
    void detectsLeakedLocksAndManualIntervention() throws Exception {
        JdbcDataSource ds = database("recon-leak");
        JdbcAtRepository repository = new JdbcAtRepository(ds);
        JdbcGlobalLockManager locks = new JdbcGlobalLockManager(ds, 60000L);
        long now = System.currentTimeMillis();

        // 事务已终态，锁却还在 —— 其他事务访问同一行会被永久拒绝
        AtTransaction done = new AtTransaction("done-1", "done", now, now + 60000L);
        repository.create(done);
        assertTrue(repository.transition("done-1", AtStatus.ACTIVE, 0, AtStatus.ROLLING_BACK));
        assertTrue(repository.transition("done-1", AtStatus.ROLLING_BACK, 1, AtStatus.ROLLED_BACK));
        locks.acquire("db", "account", "1", "done-1");

        // 孤儿锁：锁表里有 xid，全局表里已经没有
        try (Connection c = ds.getConnection();
                java.sql.PreparedStatement p =
                        c.prepareStatement(
                                "INSERT INTO easy_at_lock(resource_id,table_name,pk_value,xid,lease_until,created_at)"
                                        + " VALUES('db','account','2','ghost-xid',?,?)")) {
            p.setTimestamp(1, new java.sql.Timestamp(now + 60000L));
            p.setTimestamp(2, new java.sql.Timestamp(now));
            p.executeUpdate();
        }

        // 人工介入
        AtTransaction manual = new AtTransaction("mi-1", "mi", now, now + 60000L);
        repository.create(manual);
        assertTrue(repository.transition("mi-1", AtStatus.ACTIVE, 0, AtStatus.MANUAL_INTERVENTION));

        ReconciliationReport report = service(repository, locks).report(now);
        assertEquals(2, report.getLeakedLocks().size(), "终态锁与孤儿锁都要算泄漏");
        assertEquals(1, report.getManualIntervention().size());
        assertEquals(ReconciliationReport.Level.CRITICAL, report.level(), "有锁泄漏或人工介入必须是 CRITICAL");
        assertFalse(report.toMap().get("healthy").equals(Boolean.TRUE));
        locks.close();
    }

    @Test
    void heldLocksSnapshotReadsWholeTable() throws Exception {
        JdbcDataSource ds = database("recon-snapshot");
        JdbcGlobalLockManager locks = new JdbcGlobalLockManager(ds, 60000L);
        locks.acquire("db", "account", "1", "xid-a");
        locks.acquire("db", "account", "2", "xid-a");
        locks.acquire("db", "order", "9", "xid-b");

        List<GlobalLockRef> held = locks.heldLocks();
        assertEquals(3, held.size());
        for (GlobalLockRef ref : held) assertEquals("db", ref.getResourceId());
        assertTrue(held.toString().contains("xid-a"));
        assertTrue(held.toString().contains("xid-b"));
        locks.close();
    }

    @Test
    void detectsDuplicateBranchRegistrationAsIdempotent() throws Exception {
        JdbcDataSource ds = database("recon-branch");
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement()) {
            s.execute(
                    "CREATE TABLE easy_at_branch (branch_id VARCHAR(128) PRIMARY KEY,xid VARCHAR(128) NOT NULL,"
                            + "resource_id VARCHAR(128) NOT NULL,status VARCHAR(32) NOT NULL,service_name VARCHAR(128),"
                            + "callback_url VARCHAR(512),sequence INT NOT NULL,retry_count INT NOT NULL DEFAULT 0,"
                            + "next_retry_at TIMESTAMP NULL,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL,"
                            + "UNIQUE KEY uk_easy_at_branch_xid_resource (xid, resource_id))");
        }
        JdbcBranchRepository branches = new JdbcBranchRepository(ds);
        long now = System.currentTimeMillis();
        AtBranch first =
                new AtBranch(
                        "b-1",
                        "xid-1",
                        "db",
                        "svc",
                        "http://cb",
                        1,
                        BranchStatus.REGISTERED,
                        0,
                        now,
                        now,
                        0);
        AtBranch second =
                new AtBranch(
                        "b-2",
                        "xid-1",
                        "db",
                        "svc",
                        "http://cb",
                        1,
                        BranchStatus.REGISTERED,
                        0,
                        now,
                        now,
                        0);
        branches.register(first);
        // 唯一约束下第二次注册是幂等的：不抛异常、也不产生第二行
        branches.register(second);

        assertEquals(1, branches.byXid("xid-1").size(), "同一 (xid, resource_id) 只能有一条分支");
        assertEquals("b-1", branches.findByXidResource("xid-1", "db").get().getBranchId());

        // 换个资源则是另一条分支
        branches.register(
                new AtBranch(
                        "b-3",
                        "xid-1",
                        "db2",
                        "svc",
                        "http://cb",
                        2,
                        BranchStatus.REGISTERED,
                        0,
                        now,
                        now,
                        0));
        assertEquals(2, branches.byXid("xid-1").size());
    }

    private static ReconciliationService service(
            JdbcAtRepository repository, JdbcGlobalLockManager locks) {
        return new ReconciliationService(repository, null, locks, "test", 60000L, 500);
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
                    "CREATE TABLE easy_at_lock (resource_id VARCHAR(255),table_name VARCHAR(255),pk_value VARCHAR(512),xid VARCHAR(128),lease_until TIMESTAMP,created_at TIMESTAMP,PRIMARY KEY(resource_id,table_name,pk_value))");
        }
        return dataSource;
    }

    private static int rows(JdbcDataSource ds, String sql) throws Exception {
        try (Connection c = ds.getConnection();
                Statement s = c.createStatement();
                ResultSet r = s.executeQuery(sql)) {
            r.next();
            return r.getInt(1);
        }
    }
}

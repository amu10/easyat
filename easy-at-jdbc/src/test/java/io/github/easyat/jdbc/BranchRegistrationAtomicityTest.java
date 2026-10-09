package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.core.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * §4.3 分支状态原子性：分支注册必须与业务 DML、undo log 处于同一个本地事务。
 *
 * <p>反面后果：若分支行走独立连接，业务本地事务回滚后分支记录仍然留下——协调器以为这个资源参与了全局 事务、会去回调它提交/回滚，但业务侧其实什么都没做。这个分支既不会收敛也不会告警。
 */
class BranchRegistrationAtomicityTest {

    @AfterEach
    void clear() {
        AtContext.clear();
        AtContext.endUndo();
    }

    @Test
    void branchRowIsCommittedTogetherWithBusinessTransaction() throws Exception {
        try (Fixture f = new Fixture("branchCommit")) {
            AtTransaction tx = f.manager.begin("commit-case", 10000);
            try (Connection c = f.dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement s =
                        c.prepareStatement("UPDATE account SET balance=? WHERE id=?")) {
                    s.setInt(1, 40);
                    s.setLong(2, 1L);
                    s.executeUpdate();
                }
                c.commit();
            }
            assertTrue(
                    f.branches.findByXidResource(tx.getXid(), "dataSource").isPresent(),
                    "业务提交后分支行必须可见，否则协调器不知道这个资源参与过");
        }
    }

    @Test
    void branchRowDisappearsWhenBusinessTransactionRollsBack() throws Exception {
        try (Fixture f = new Fixture("branchRollback")) {
            AtTransaction tx = f.manager.begin("rollback-case", 10000);
            try (Connection c = f.dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement s =
                        c.prepareStatement("UPDATE account SET balance=? WHERE id=?")) {
                    s.setInt(1, 40);
                    s.setLong(2, 1L);
                    s.executeUpdate();
                }
                c.rollback();
            }
            assertFalse(
                    f.branches.findByXidResource(tx.getXid(), "dataSource").isPresent(),
                    "业务回滚后不能留下分支记录——资源没做成任何事，留下的分支永远无法收敛");
        }
    }

    @Test
    void multipleDmlInOneTransactionRegistersExactlyOneBranch() throws Exception {
        try (Fixture f = new Fixture("branchOnce")) {
            AtTransaction tx = f.manager.begin("once-case", 10000);
            try (Connection c = f.dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement s =
                        c.prepareStatement("UPDATE account SET balance=? WHERE id=?")) {
                    s.setInt(1, 40);
                    s.setLong(2, 1L);
                    s.executeUpdate();
                    s.setInt(1, 70);
                    s.setLong(2, 1L);
                    s.executeUpdate();
                }
                // 唯一的分支行不能被唯一键冲突打断：PostgreSQL 上一句报错会把整个业务事务废掉。
                c.commit();
            }
            assertEquals(1, f.branches.byXid(tx.getXid()).size());
            assertEquals(70, f.balance());
        }
    }

    /** 反向验证：故意退回「独立连接注册」，业务回滚后分支行必须残留——证明上面三个用例确实在测 registerIn 的效果，而不是无论如何都会通过。 */
    @Test
    void withoutBusinessConnectionBranchSurvivesRollback() throws Exception {
        try (Fixture f = new Fixture("branchLegacy")) {
            f.useBusinessConnection = false;
            AtTransaction tx = f.manager.begin("legacy-case", 10000);
            try (Connection c = f.dataSource.getConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement s =
                        c.prepareStatement("UPDATE account SET balance=? WHERE id=?")) {
                    s.setInt(1, 40);
                    s.setLong(2, 1L);
                    s.executeUpdate();
                }
                c.rollback();
            }
            assertTrue(
                    f.branches.findByXidResource(tx.getXid(), "dataSource").isPresent(),
                    "旧行为：分支行走独立连接，业务回滚后残留——这正是要修的问题");
        }
    }

    /** 一个 AT 数据源 + 分支表 + 走业务连接的 BranchRegistrar。 */
    private static final class Fixture implements AutoCloseable {
        final JdbcDataSource raw;
        final JdbcBranchRepository branches;
        final AtDataSource dataSource;
        final AtTransactionManager manager;
        boolean useBusinessConnection = true;

        Fixture(String name) throws SQLException {
            raw = new JdbcDataSource();
            raw.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            try (Connection c = raw.getConnection();
                    Statement s = c.createStatement()) {
                s.execute("CREATE TABLE account (id BIGINT PRIMARY KEY, balance INT)");
                s.execute("INSERT INTO account(id,balance) VALUES(1,100)");
                // 与 db/mysql/easy-at.sql 保持一致，含 (xid, resource_id) 唯一约束
                s.execute(
                        "CREATE TABLE easy_at_branch ("
                                + "branch_id VARCHAR(128) PRIMARY KEY,"
                                + "xid VARCHAR(128) NOT NULL,"
                                + "resource_id VARCHAR(128) NOT NULL,"
                                + "status VARCHAR(32) NOT NULL,"
                                + "service_name VARCHAR(128),"
                                + "callback_url VARCHAR(512),"
                                + "sequence INT NOT NULL,"
                                + "retry_count INT NOT NULL DEFAULT 0,"
                                + "next_retry_at BIGINT,"
                                + "created_at TIMESTAMP NOT NULL,"
                                + "updated_at TIMESTAMP NOT NULL,"
                                + "CONSTRAINT uk_easy_at_branch_xid_resource UNIQUE(xid, resource_id))");
            }
            branches = new JdbcBranchRepository(raw);
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
                            new BranchRegistrar() {
                                @Override
                                public void register(String xid, String resourceId) {
                                    register(xid, resourceId, null);
                                }

                                @Override
                                public void register(
                                        String xid, String resourceId, Object localConnection) {
                                    Object conn = useBusinessConnection ? localConnection : null;
                                    Optional<AtBranch> existing =
                                            branches.findByXidResource(xid, resourceId);
                                    if (existing.isPresent()) return;
                                    AtBranch b =
                                            new AtBranch(
                                                    xid,
                                                    resourceId,
                                                    "svc",
                                                    null,
                                                    branches.byXid(xid).size() + 1);
                                    if (conn == null || !branches.registerIn(b, conn))
                                        branches.register(b);
                                }
                            });
        }

        int balance() throws SQLException {
            try (Connection c = raw.getConnection();
                    Statement s = c.createStatement();
                    ResultSet r = s.executeQuery("SELECT balance FROM account WHERE id=1")) {
                r.next();
                return r.getInt(1);
            }
        }

        @Override
        public void close() {
            AtContext.clear();
            AtContext.endUndo();
        }
    }
}

package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.core.*;
import java.sql.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class JdbcInfrastructureTest {
    @Test
    void persistsTransactionsAndEnforcesRowLocks() throws Exception {
        JdbcDataSource ds = database("infrastructure");
        JdbcAtRepository repository = new JdbcAtRepository(ds);
        AtTransaction tx =
                new AtTransaction(
                        "x-1",
                        "order",
                        System.currentTimeMillis(),
                        System.currentTimeMillis() + 1000);
        repository.create(tx);
        tx.setStatus(AtStatus.COMMITTING);
        repository.save(tx);
        assertEquals(AtStatus.COMMITTING, repository.find("x-1").get().getStatus());
        JdbcGlobalLockManager locks = new JdbcGlobalLockManager(ds, 10000);
        locks.acquire("db", "account", "1", "x-1");
        assertThrows(AtException.class, () -> locks.acquire("db", "account", "1", "x-2"));
        locks.releaseByXid("x-1");
        locks.acquire("db", "account", "1", "x-2");
    }

    @Test
    void immediatelyReclaimsLocksOwnedByRolledBackTransactions() throws Exception {
        JdbcDataSource ds = database("terminalLock");
        JdbcAtRepository repository = new JdbcAtRepository(ds);
        AtTransaction transaction =
                new AtTransaction(
                        "old-xid",
                        "old",
                        System.currentTimeMillis(),
                        System.currentTimeMillis() + 10000);
        repository.create(transaction);
        JdbcGlobalLockManager locks = new JdbcGlobalLockManager(ds, 60000);
        locks.acquire("db", "inventory", "1001", "old-xid");
        assertTrue(repository.transition("old-xid", AtStatus.ACTIVE, 0, AtStatus.ROLLING_BACK));
        assertTrue(
                repository.transition("old-xid", AtStatus.ROLLING_BACK, 1, AtStatus.ROLLED_BACK));

        // The lease still has almost one minute left, but terminal state makes it reclaimable now.
        locks.acquire("db", "inventory", "1001", "new-xid");
        try (Connection connection = ds.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT xid FROM easy_at_lock")) {
            assertTrue(result.next());
            assertEquals("new-xid", result.getString(1));
        }
        locks.close();
    }

    @Test
    void renewalRemovesTerminalLocksAndRepeatedRollbackRetriesCleanup() throws Exception {
        JdbcDataSource ds = database("renewalCleanup");
        JdbcAtRepository repository = new JdbcAtRepository(ds);
        CountingLocks cleanup = new CountingLocks();
        AtTransactionManager manager =
                new AtTransactionManager(repository, record -> {}, cleanup, 3);
        AtTransaction transaction = manager.begin("idempotent", 10000);
        JdbcGlobalLockManager locks = new JdbcGlobalLockManager(ds, 60000);
        locks.acquire("db", "inventory", "1001", transaction.getXid());

        manager.rollback(transaction.getXid());
        manager.rollback(transaction.getXid());
        assertEquals(2, cleanup.releases);

        // Simulate a different service that still remembers the XID in its local renewal set.
        locks.renewAll();
        try (Connection connection = ds.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM easy_at_lock")) {
            assertTrue(result.next());
            assertEquals(0, result.getInt(1));
        }
        locks.close();
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

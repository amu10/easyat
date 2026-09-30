package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.easyat.core.AtContext;
import io.github.easyat.core.AtTransaction;
import io.github.easyat.core.AtTransactionManager;
import io.github.easyat.core.BranchRegistrar;
import io.github.easyat.core.LocalTransactionBridge;
import io.github.easyat.core.UnsupportedAtSqlException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MultiRowAtTest {
    @AfterEach
    void clearContext() {
        AtContext.clear();
        AtContext.endUndo();
    }

    @Test
    void updatesAndRollsBackExplicitPrimaryKeySet() throws Exception {
        Fixture fixture = new Fixture("multiUpdate", 3);
        AtTransaction transaction = fixture.manager.begin("multi-update", 10000);
        try (Connection connection = fixture.dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE account SET balance=balance-? WHERE id IN (?,?)")) {
            statement.setInt(1, 10);
            statement.setLong(2, 2);
            statement.setLong(3, 1);
            assertEquals(2, statement.executeUpdate());
        }
        assertEquals(
                2, fixture.repository.find(transaction.getXid()).get().getUndoRecords().size());
        fixture.manager.rollback(transaction.getXid());
        assertEquals(100, fixture.balance(1));
        assertEquals(200, fixture.balance(2));
    }

    @Test
    void deletesAndRollsBackExplicitPrimaryKeySet() throws Exception {
        Fixture fixture = new Fixture("multiDelete", 3);
        AtTransaction transaction = fixture.manager.begin("multi-delete", 10000);
        try (Connection connection = fixture.dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("DELETE FROM account WHERE id IN (?,?)")) {
            statement.setLong(1, 1);
            statement.setLong(2, 2);
            assertEquals(2, statement.executeUpdate());
        }
        fixture.manager.rollback(transaction.getXid());
        assertEquals(3, fixture.count());
    }

    @Test
    void supportsMultipleRowsByDefaultAndEnforcesConfiguredLimit() throws Exception {
        Fixture defaults = new Fixture("multiDefault");
        AtTransaction transaction = defaults.manager.begin("default-multi", 10000);
        defaults.updateThreeRows();
        assertEquals(0, defaults.balance(1));
        defaults.manager.rollback(transaction.getXid());
        assertEquals(100, defaults.balance(1));
        AtContext.clear();

        Fixture limited = new Fixture("multiLimit", 2);
        limited.manager.begin("limited", 10000);
        assertThrows(UnsupportedAtSqlException.class, limited::updateThreeRows);
    }

    private static final class Fixture {
        final JdbcDataSource raw = new JdbcDataSource();
        final AtDataSourceTest.MemoryRepo repository = new AtDataSourceTest.MemoryRepo();
        final AtDataSourceTest.MemoryLocks locks = new AtDataSourceTest.MemoryLocks();
        final AtTransactionManager manager;
        final AtDataSource dataSource;

        Fixture(String database) throws Exception {
            this(database, null);
        }

        Fixture(String database, Integer maxRows) throws Exception {
            raw.setURL("jdbc:h2:mem:" + database + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            try (Connection connection = raw.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE account (id BIGINT PRIMARY KEY, balance INT)");
                statement.execute("INSERT INTO account VALUES(1,100),(2,200),(3,300)");
            }
            manager =
                    new AtTransactionManager(
                            repository,
                            new JdbcUndoExecutor(
                                    Collections.<String, DataSource>singletonMap(
                                            "dataSource", raw)),
                            locks,
                            3);
            dataSource =
                    maxRows == null
                            ? new AtDataSource("dataSource", raw, manager, locks)
                            : new AtDataSource(
                                    "dataSource",
                                    raw,
                                    manager,
                                    locks,
                                    LocalTransactionBridge.NOOP,
                                    false,
                                    BranchRegistrar.NOOP,
                                    maxRows.intValue());
        }

        void updateThreeRows() throws Exception {
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "UPDATE account SET balance=? WHERE id IN (?,?,?)")) {
                statement.setInt(1, 0);
                statement.setLong(2, 1);
                statement.setLong(3, 2);
                statement.setLong(4, 3);
                statement.executeUpdate();
            }
        }

        int balance(long id) throws Exception {
            try (Connection connection = raw.getConnection();
                    PreparedStatement statement =
                            connection.prepareStatement("SELECT balance FROM account WHERE id=?")) {
                statement.setLong(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getInt(1);
                }
            }
        }

        int count() throws Exception {
            try (Connection connection = raw.getConnection();
                    Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM account")) {
                result.next();
                return result.getInt(1);
            }
        }
    }
}

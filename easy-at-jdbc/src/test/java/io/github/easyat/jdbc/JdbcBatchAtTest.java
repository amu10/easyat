package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.easyat.core.AtContext;
import io.github.easyat.core.AtTransaction;
import io.github.easyat.core.AtTransactionManager;
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

class JdbcBatchAtTest {
    @AfterEach
    void clearContext() {
        AtContext.clear();
        AtContext.endUndo();
    }

    @Test
    void snapshotsEveryParameterSetAndRollsBackTheWholeBatch() throws Exception {
        Fixture fixture = new Fixture("batchRollback");
        AtTransaction transaction = fixture.manager.begin("batch", 10000);

        try (Connection connection = fixture.dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("UPDATE account SET balance=? WHERE id=?")) {
            statement.setInt(1, 70);
            statement.setLong(2, 1);
            statement.addBatch();
            statement.setInt(1, 50);
            statement.setLong(2, 2);
            statement.addBatch();
            assertArrayEquals(new int[] {1, 1}, statement.executeBatch());
        }

        assertEquals(
                2, fixture.repository.find(transaction.getXid()).get().getUndoRecords().size());
        fixture.manager.rollback(transaction.getXid());
        assertEquals(100, fixture.balance(1));
        assertEquals(200, fixture.balance(2));
    }

    @Test
    void rejectsMultipleChangesToTheSameRowBeforeExecutingBatch() throws Exception {
        Fixture fixture = new Fixture("batchDuplicate");
        AtTransaction transaction = fixture.manager.begin("duplicate", 10000);

        try (Connection connection = fixture.dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement("UPDATE account SET balance=? WHERE id=?")) {
            statement.setInt(1, 70);
            statement.setLong(2, 1);
            statement.addBatch();
            statement.setInt(1, 50);
            statement.setLong(2, 1);
            statement.addBatch();
            assertThrows(UnsupportedAtSqlException.class, statement::executeBatch);
        }

        assertEquals(100, fixture.balance(1));
        assertEquals(
                0, fixture.repository.find(transaction.getXid()).get().getUndoRecords().size());
    }

    private static final class Fixture {
        final JdbcDataSource raw = new JdbcDataSource();
        final AtDataSourceTest.MemoryRepo repository = new AtDataSourceTest.MemoryRepo();
        final AtDataSourceTest.MemoryLocks locks = new AtDataSourceTest.MemoryLocks();
        final AtTransactionManager manager;
        final AtDataSource dataSource;

        Fixture(String database) throws Exception {
            raw.setURL("jdbc:h2:mem:" + database + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
            try (Connection connection = raw.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE account (id BIGINT PRIMARY KEY, balance INT)");
                statement.execute("INSERT INTO account VALUES(1,100),(2,200)");
            }
            manager =
                    new AtTransactionManager(
                            repository,
                            new JdbcUndoExecutor(
                                    Collections.<String, DataSource>singletonMap(
                                            "dataSource", raw)),
                            locks,
                            3);
            dataSource = new AtDataSource("dataSource", raw, manager, locks);
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
    }
}

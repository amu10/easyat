package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/**
 * JDBC-backed source of truth for global transactions and undo records. All status changes use CAS.
 */
public final class JdbcAtRepository implements ConnectionBoundAtRepository {
    private final DataSource dataSource;
    private final UndoDataCodec codec;

    public JdbcAtRepository(DataSource dataSource) {
        this(dataSource, new JacksonUndoDataCodec());
    }

    public JdbcAtRepository(DataSource dataSource, UndoDataCodec codec) {
        this.dataSource = dataSource;
        this.codec = codec;
    }

    @Override
    public void create(AtTransaction tx) {
        String sql =
                "INSERT INTO easy_at_global(xid,name,status,timeout_at,retry_count,next_retry_at,version,owner,lease_until,created_at,updated_at) VALUES(?,?,?,?,?,?,0,NULL,NULL,?,?)";
        try (Connection c = dataSource.getConnection();
                PreparedStatement p = c.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            p.setString(1, tx.getXid());
            p.setString(2, tx.getName());
            p.setString(3, tx.getStatus().name());
            p.setTimestamp(4, new Timestamp(tx.getDeadline()));
            p.setInt(5, tx.getRetries());
            p.setTimestamp(6, timestamp(tx.getNextRetryAt()));
            p.setTimestamp(7, new Timestamp(now));
            p.setTimestamp(8, new Timestamp(now));
            p.executeUpdate();
        } catch (SQLException e) {
            throw new AtException("Cannot create AT transaction", e);
        }
    }

    @Override
    public Optional<AtTransaction> find(String xid) {
        String sql =
                "SELECT xid,name,status,timeout_at,retry_count,next_retry_at,version,owner,lease_until,created_at FROM easy_at_global WHERE xid=?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, xid);
            try (ResultSet r = p.executeQuery()) {
                if (!r.next()) return Optional.empty();
                AtTransaction tx =
                        new AtTransaction(
                                r.getString(1),
                                r.getString(2),
                                r.getTimestamp(10).getTime(),
                                r.getTimestamp(4).getTime());
                Timestamp next = r.getTimestamp(6);
                tx.restore(
                        AtStatus.valueOf(r.getString(3)),
                        r.getInt(5),
                        next == null ? 0 : next.getTime());
                tx.setVersion(r.getLong(7));
                String owner = r.getString(8);
                if (owner != null) tx.setOwner(owner);
                Timestamp lease = r.getTimestamp(9);
                if (lease != null) tx.setLeaseUntil(lease.getTime());
                loadUndo(c, tx);
                return Optional.of(tx);
            }
        } catch (SQLException e) {
            throw new AtException("Cannot read AT transaction", e);
        }
    }

    @Override
    public void save(AtTransaction tx) {
        // 注意：save 只重写 undo 记录与重试簿记，不用于变更状态——状态变更必须走 transition 的 CAS。
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            try {
                c.setAutoCommit(false);
                try (PreparedStatement d =
                        c.prepareStatement("DELETE FROM easy_at_undo_log WHERE xid=?")) {
                    d.setString(1, tx.getXid());
                    d.executeUpdate();
                }
                for (UndoRecord undo : tx.getUndoRecords()) insertUndo(c, undo);
                try (PreparedStatement u =
                        c.prepareStatement(
                                "UPDATE easy_at_global SET status=?,version=?,owner=?,lease_until=?,retry_count=?,next_retry_at=?,updated_at=? WHERE xid=?")) {
                    u.setString(1, tx.getStatus().name());
                    u.setLong(2, tx.getVersion());
                    String owner = tx.getOwner();
                    u.setString(3, owner == null ? "" : owner);
                    u.setTimestamp(
                            4, tx.getLeaseUntil() > 0 ? new Timestamp(tx.getLeaseUntil()) : null);
                    u.setInt(5, tx.getRetries());
                    u.setTimestamp(6, timestamp(tx.getNextRetryAt()));
                    u.setTimestamp(7, new Timestamp(System.currentTimeMillis()));
                    u.setString(8, tx.getXid());
                    u.executeUpdate();
                }
                c.commit();
            } catch (Exception e) {
                c.rollback();
                if (e instanceof AtException) throw (AtException) e;
                throw new AtException("Cannot save AT transaction", e);
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new AtException("Cannot save AT transaction", e);
        }
    }

    /**
     * 状态迁移的 CAS（DESIGN.md §4.1）：以「期望状态 + 版本号」作为 WHERE 条件， 成功则 {@code version = version +
     * 1}。并发场景下只有一个实例能命中并更新， 其余返回 0（false），从而避免多实例重复驱动同一事务。
     */
    @Override
    public boolean transition(String xid, AtStatus expected, long expectedVersion, AtStatus next) {
        String sql =
                "UPDATE easy_at_global SET status=?,version=version+1,updated_at=? WHERE xid=? AND status=? AND version=?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, next.name());
            p.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            p.setString(3, xid);
            p.setString(4, expected.name());
            p.setLong(5, expectedVersion);
            return p.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new AtException("Cannot transition AT transaction " + xid, e);
        }
    }

    /**
     * 原子领取恢复租约：仅当「非终态 且 (无主 或 我是主 或 租约已过期)」时更新 owner/lease_until。 这是多实例恢复不重复处理的根基——抢不到租约的实例直接跳过。
     */
    @Override
    public boolean claimLease(String xid, String owner, long leaseUntil, long now) {
        String sql =
                "UPDATE easy_at_global SET owner=?,lease_until=? WHERE xid=? AND status NOT IN('COMMITTED','ROLLED_BACK','MANUAL_INTERVENTION','DIRTY_WRITE') AND (owner IS NULL OR owner=? OR lease_until<?)";
        try (Connection c = dataSource.getConnection();
                PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, owner);
            p.setTimestamp(2, new Timestamp(leaseUntil));
            p.setString(3, xid);
            p.setString(4, owner);
            p.setTimestamp(5, new Timestamp(now));
            return p.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new AtException("Cannot claim recovery lease " + xid, e);
        }
    }

    @Override
    public void releaseLease(String xid, String owner) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement p =
                        c.prepareStatement(
                                "UPDATE easy_at_global SET owner=NULL,lease_until=NULL WHERE xid=? AND (owner=? OR owner IS NULL)")) {
            p.setString(1, xid);
            p.setString(2, owner);
            p.executeUpdate();
        } catch (SQLException e) {
            throw new AtException("Cannot release recovery lease " + xid, e);
        }
    }

    @Override
    public void updateRecovery(String xid, int retries, long nextRetryAt) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement p =
                        c.prepareStatement(
                                "UPDATE easy_at_global SET retry_count=?,next_retry_at=?,updated_at=? WHERE xid=?")) {
            p.setInt(1, retries);
            p.setTimestamp(2, timestamp(nextRetryAt));
            p.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            p.setString(4, xid);
            p.executeUpdate();
        } catch (SQLException e) {
            throw new AtException("Cannot update recovery state " + xid, e);
        }
    }

    @Override
    public List<AtTransaction> recoverable(long now, int limit) {
        String sql =
                "SELECT xid FROM easy_at_global WHERE (status='ACTIVE' AND timeout_at<=?) OR (status='ROLLING_BACK') OR (status='ROLLBACK_FAILED' AND (next_retry_at IS NULL OR next_retry_at<=?)) ORDER BY updated_at ASC";
        List<AtTransaction> result = new ArrayList<AtTransaction>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement p = c.prepareStatement(sql)) {
            p.setTimestamp(1, new Timestamp(now));
            p.setTimestamp(2, new Timestamp(now));
            try (ResultSet r = p.executeQuery()) {
                while (r.next() && result.size() < limit) {
                    Optional<AtTransaction> tx = find(r.getString(1));
                    if (tx.isPresent()) result.add(tx.get());
                }
            }
            return result;
        } catch (SQLException e) {
            throw new AtException("Cannot scan recoverable transactions", e);
        }
    }

    @Override
    public List<AtTransaction> findByStatus(AtStatus status, int limit) {
        String sql = "SELECT xid FROM easy_at_global WHERE status=? ORDER BY updated_at DESC";
        List<AtTransaction> result = new ArrayList<AtTransaction>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, status.name());
            try (ResultSet r = p.executeQuery()) {
                while (r.next() && result.size() < limit) {
                    Optional<AtTransaction> tx = find(r.getString(1));
                    if (tx.isPresent()) result.add(tx.get());
                }
            }
            return result;
        } catch (SQLException e) {
            throw new AtException("Cannot scan transactions by status", e);
        }
    }

    @Override
    public void append(Connection connection, UndoRecord record) {
        try {
            insertUndo(connection, record);
        } catch (SQLException e) {
            throw new AtException("Cannot append connection-bound undo record", e);
        }
    }

    @Override
    public void updateUndo(Connection connection, String xid, String undoId, RowImage after) {
        String lookup = "SELECT resource_id,table_name FROM easy_at_undo_log WHERE undo_id=?";
        try (PreparedStatement l = connection.prepareStatement(lookup)) {
            l.setString(1, undoId);
            try (ResultSet rs = l.executeQuery()) {
                if (rs.next()) {
                    UndoContext ctx = new UndoContext(rs.getString(1), rs.getString(2));
                    String sql =
                            "UPDATE easy_at_undo_log SET after_image=?,updated_at=? WHERE xid=? AND undo_id=?";
                    try (PreparedStatement p = connection.prepareStatement(sql)) {
                        p.setBytes(1, codec.encodeRowImage(after, ctx));
                        p.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
                        p.setString(3, xid);
                        p.setString(4, undoId);
                        if (p.executeUpdate() != 1)
                            throw new AtException("Undo record not found: " + undoId);
                        return;
                    }
                }
            }
            throw new AtException("Undo record not found: " + undoId);
        } catch (SQLException e) {
            throw new AtException("Cannot update connection-bound undo record", e);
        }
    }

    @Override
    public void removeUndo(Connection connection, String xid, String undoId) {
        try (PreparedStatement p =
                connection.prepareStatement(
                        "DELETE FROM easy_at_undo_log WHERE xid=? AND undo_id=?")) {
            p.setString(1, xid);
            p.setString(2, undoId);
            p.executeUpdate();
        } catch (SQLException e) {
            throw new AtException("Cannot discard connection-bound undo record", e);
        }
    }

    private void loadUndo(Connection c, AtTransaction tx) throws SQLException {
        try (PreparedStatement p =
                c.prepareStatement(
                        "SELECT undo_id,resource_id,table_name,pk_name,pk_value,rollback_sql,rollback_params,before_image,after_image,status FROM easy_at_undo_log WHERE xid=? ORDER BY created_at")) {
            p.setString(1, tx.getXid());
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    UndoContext ctx = new UndoContext(r.getString(2), r.getString(3));
                    UndoRecord u =
                            new UndoRecord(
                                    r.getString(1),
                                    tx.getXid(),
                                    r.getString(2),
                                    r.getString(3),
                                    r.getString(4),
                                    r.getString(5),
                                    r.getString(6),
                                    codec.decodeParameters(r.getBytes(7), ctx));
                    u.setBeforeImage(codec.decodeRowImage(r.getBytes(8), ctx));
                    u.setAfterImage(codec.decodeRowImage(r.getBytes(9), ctx));
                    if ("ROLLED_BACK".equals(r.getString(10))) u.markRolledBack();
                    tx.addUndo(u);
                }
            }
        }
    }

    private void insertUndo(Connection c, UndoRecord u) throws SQLException {
        String sql =
                "INSERT INTO easy_at_undo_log(undo_id,xid,resource_id,table_name,pk_name,pk_value,rollback_sql,rollback_params,before_image,after_image,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)";
        UndoContext ctx = new UndoContext(u.getResourceId(), u.getTableName());
        try (PreparedStatement p = c.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            p.setString(1, u.getId());
            p.setString(2, u.getXid());
            p.setString(3, u.getResourceId());
            p.setString(4, u.getTableName());
            p.setString(5, u.getPrimaryKeyColumn());
            p.setString(6, String.valueOf(u.getPrimaryKeyValue()));
            p.setString(7, u.getRollbackSql());
            p.setBytes(8, codec.encodeParameters(u.getParameters(), ctx));
            p.setBytes(9, codec.encodeRowImage(u.getBeforeImage(), ctx));
            p.setBytes(10, codec.encodeRowImage(u.getAfterImage(), ctx));
            p.setString(11, u.isRolledBack() ? "ROLLED_BACK" : "EXECUTED");
            p.setTimestamp(12, new Timestamp(now));
            p.setTimestamp(13, new Timestamp(now));
            p.executeUpdate();
        }
    }

    private static Timestamp timestamp(long value) {
        return value <= 0 ? null : new Timestamp(value);
    }
}

package io.github.easyat.jdbc;

import io.github.easyat.core.AtException;
import io.github.easyat.core.ConnectionBoundUndoRepository;
import io.github.easyat.core.RowImage;
import io.github.easyat.core.UndoContext;
import io.github.easyat.core.UndoDataCodec;
import io.github.easyat.core.UndoRecord;
import io.github.easyat.core.UndoRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * 只负责 {@code easy_at_undo_log} 一张表的 undo 存储，用于<b>混合存储模式</b>
 * （全局事务状态/分支/锁在 Redis，undo 在业务库）。
 *
 * <h2>为什么要有这个独立实现</h2>
 *
 * <p>undo log 必须与业务 DML 在<b>同一个本地事务</b>里提交——业务回滚时 undo 必须一起消失，
 * 否则恢复调度会去回滚一行从未真正改动过的数据。这是 AT 正确性的前提，不是惯例。
 *
 * <p>因此混合模式下 undo 只能落在业务库（通过 {@link #append(Connection, UndoRecord)} 走业务连接），
 * 而不能跟着 Redis 走。本类与 {@link JdbcAtRepository} 的区别是：它<b>不依赖</b>
 * {@code easy_at_global} / {@code easy_at_branch} / {@code easy_at_lock} 三张表，
 * 所以混合模式下业务库不需要建那三张表。
 */
public final class JdbcUndoRepository implements ConnectionBoundUndoRepository {
    private final DataSource dataSource;
    private final UndoDataCodec codec;

    public JdbcUndoRepository(DataSource dataSource) {
        this(dataSource, new JacksonUndoDataCodec());
    }

    public JdbcUndoRepository(DataSource dataSource, UndoDataCodec codec) {
        this.dataSource = dataSource;
        this.codec = codec;
    }

    @Override
    public void append(Connection connection, UndoRecord record) {
        try {
            insertUndo(connection, record);
        } catch (SQLException e) {
            throw new AtException("Cannot append business-bound undo record", e);
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
                    try (PreparedStatement p =
                            connection.prepareStatement(
                                    "UPDATE easy_at_undo_log SET after_image=?,updated_at=? WHERE xid=? AND undo_id=?")) {
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
            throw new AtException("Cannot update business-bound undo record", e);
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
            throw new AtException("Cannot discard business-bound undo record", e);
        }
    }

    @Override
    public List<UndoRecord> load(String xid) {
        try (Connection c = dataSource.getConnection()) {
            return readUndo(c, xid);
        } catch (SQLException e) {
            throw new AtException("Cannot load undo records for " + xid, e);
        }
    }

    @Override
    public void replaceAll(String xid, List<UndoRecord> records) {
        try (Connection c = dataSource.getConnection()) {
            boolean auto = c.getAutoCommit();
            try {
                c.setAutoCommit(false);
                deleteUndo(c, xid);
                for (UndoRecord u : records) insertUndo(c, u);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                if (e instanceof AtException) throw (AtException) e;
                throw new AtException("Cannot replace undo records for " + xid, e);
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new AtException("Cannot replace undo records for " + xid, e);
        }
    }

    @Override
    public void deleteByXid(String xid) {
        try (Connection c = dataSource.getConnection()) {
            deleteUndo(c, xid);
        } catch (SQLException e) {
            throw new AtException("Cannot delete undo records for " + xid, e);
        }
    }

    private void deleteUndo(Connection c, String xid) throws SQLException {
        try (PreparedStatement d = c.prepareStatement("DELETE FROM easy_at_undo_log WHERE xid=?")) {
            d.setString(1, xid);
            d.executeUpdate();
        }
    }

    private List<UndoRecord> readUndo(Connection c, String xid) throws SQLException {
        List<UndoRecord> out = new ArrayList<UndoRecord>();
        try (PreparedStatement p =
                c.prepareStatement(
                        "SELECT undo_id,resource_id,table_name,pk_name,pk_value,rollback_sql,rollback_params,before_image,after_image,status FROM easy_at_undo_log WHERE xid=? ORDER BY created_at")) {
            p.setString(1, xid);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) {
                    UndoContext ctx = new UndoContext(r.getString(2), r.getString(3));
                    UndoRecord u =
                            new UndoRecord(
                                    r.getString(1),
                                    xid,
                                    r.getString(2),
                                    r.getString(3),
                                    r.getString(4),
                                    r.getString(5),
                                    r.getString(6),
                                    codec.decodeParameters(r.getBytes(7), ctx));
                    u.setBeforeImage(codec.decodeRowImage(r.getBytes(8), ctx));
                    u.setAfterImage(codec.decodeRowImage(r.getBytes(9), ctx));
                    if ("ROLLED_BACK".equals(r.getString(10))) u.markRolledBack();
                    out.add(u);
                }
            }
        }
        return out;
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
}

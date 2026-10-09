package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

/** JDBC-backed branch registry with CAS status transitions. */
public final class JdbcBranchRepository implements BranchRepository {
    private final DataSource dataSource;

    public JdbcBranchRepository(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void register(AtBranch b) {
        // (xid, resource_id) 有唯一约束，重复注册是幂等的：先查再插，并用唯一键冲突兜住并发窗口。
        if (findByXidResource(b.getXid(), b.getResourceId()).isPresent()) return;
        try (Connection c = dataSource.getConnection()) {
            insert(c, b);
        } catch (SQLException e) {
            // 并发下两个实例同时 INSERT：唯一键冲突说明别人先注册成功了，按幂等处理。
            if (isDuplicateKey(e)) return;
            throw new AtException("Cannot register AT branch", e);
        }
    }

    /**
     * 在业务本地连接上注册分支，让分支行与业务 DML / undo log 同属一个本地事务。
     *
     * <p>两个必须处理好的点：
     *
     * <ol>
     *   <li>同一个本地事务里通常有多条 DML，每条都会走到这里。所以先在本连接上查一次——本连接能看到 自己尚未提交的行，从而避免重复 INSERT。
     *   <li>若仍撞上唯一键冲突（别的实例并发注册），必须回滚到 savepoint。否则在 PostgreSQL 上， 一条失败语句会把整个业务事务标记为
     *       aborted，后续语句全部报错，业务直接失败。
     * </ol>
     */
    @Override
    public boolean registerIn(AtBranch b, Object localConnection) {
        if (!(localConnection instanceof Connection)) return false;
        Connection c = (Connection) localConnection;
        try {
            if (c.isClosed()) return false;
            // 本连接上的查重：能看见本事务未提交的行，覆盖「同一事务多条 DML」这一最常见场景。
            if (existsOn(c, b.getXid(), b.getResourceId())) return true;
            Savepoint savepoint = null;
            try {
                if (!c.getAutoCommit() && c.getMetaData().supportsSavepoints())
                    savepoint = c.setSavepoint();
                insert(c, b);
                if (savepoint != null) c.releaseSavepoint(savepoint);
                return true;
            } catch (SQLException e) {
                if (!isDuplicateKey(e)) throw new AtException("Cannot register AT branch", e);
                // 幂等：别人先注册成功了。回滚到 savepoint，别污染业务事务。
                if (savepoint != null) {
                    try {
                        c.rollback(savepoint);
                    } catch (SQLException ignored) {
                        // 回滚 savepoint 失败说明事务本身已经有问题，交给上层处理。
                    }
                }
                return true;
            }
        } catch (SQLException e) {
            throw new AtException("Cannot register AT branch on business connection", e);
        }
    }

    private boolean existsOn(Connection c, String xid, String resourceId) throws SQLException {
        try (PreparedStatement p =
                c.prepareStatement("SELECT 1 FROM easy_at_branch WHERE xid=? AND resource_id=?")) {
            p.setString(1, xid);
            p.setString(2, resourceId);
            try (ResultSet r = p.executeQuery()) {
                return r.next();
            }
        }
    }

    /** 在给定连接上插入分支行。注意：不关闭连接——业务连接归调用方管。 */
    private void insert(Connection c, AtBranch b) throws SQLException {
        try (PreparedStatement p =
                c.prepareStatement(
                        "INSERT INTO easy_at_branch(branch_id,xid,resource_id,status,service_name,callback_url,sequence,retry_count,next_retry_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,0,NULL,?,?)")) {
            long now = System.currentTimeMillis();
            p.setString(1, b.getBranchId());
            p.setString(2, b.getXid());
            p.setString(3, b.getResourceId());
            p.setString(4, b.getStatus().name());
            p.setString(5, b.getServiceName());
            p.setString(6, b.getCallbackUrl());
            p.setInt(7, b.getSequence());
            p.setTimestamp(8, new Timestamp(b.getCreatedAt()));
            p.setTimestamp(9, new Timestamp(now));
            p.executeUpdate();
        }
    }

    /**
     * 唯一键冲突判定。SQLState 23xxx 是 SQL 标准的完整性约束冲突（MySQL 1062、PG 23505 都落在里面）； 额外认 MySQL 1062 / SQL
     * Server 2601 的错误码，避免某些驱动不回填 SQLState。
     */
    private static boolean isDuplicateKey(SQLException e) {
        for (SQLException cur = e; cur != null; cur = cur.getNextException()) {
            String state = cur.getSQLState();
            if (state != null && state.startsWith("23")) return true;
            int code = cur.getErrorCode();
            if (code == 1062 || code == 2601 || code == 2627) return true;
        }
        return false;
    }

    @Override
    public Optional<AtBranch> findByXidResource(String xid, String resourceId) {
        if (xid == null || resourceId == null) return Optional.empty();
        String sql =
                "SELECT branch_id,xid,resource_id,status,service_name,callback_url,sequence,retry_count,created_at,updated_at,next_retry_at FROM easy_at_branch WHERE xid=? AND resource_id=?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, xid);
            p.setString(2, resourceId);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? Optional.of(map(r)) : Optional.<AtBranch>empty();
            }
        } catch (SQLException e) {
            throw new AtException("Cannot read AT branch by (xid, resourceId)", e);
        }
    }

    @Override
    public Optional<AtBranch> find(String branchId) {
        String sql =
                "SELECT branch_id,xid,resource_id,status,service_name,callback_url,sequence,retry_count,created_at,updated_at,next_retry_at FROM easy_at_branch WHERE branch_id=?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, branchId);
            try (ResultSet r = p.executeQuery()) {
                return r.next() ? Optional.of(map(r)) : Optional.<AtBranch>empty();
            }
        } catch (SQLException e) {
            throw new AtException("Cannot read AT branch", e);
        }
    }

    @Override
    public boolean transition(String branchId, BranchStatus expected, BranchStatus next) {
        String sql =
                "UPDATE easy_at_branch SET status=?,updated_at=? WHERE branch_id=? AND status=?";
        try (Connection c = dataSource.getConnection();
                PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, next.name());
            p.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            p.setString(3, branchId);
            p.setString(4, expected.name());
            return p.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new AtException("Cannot transition AT branch", e);
        }
    }

    @Override
    public List<AtBranch> byXid(String xid) {
        String sql =
                "SELECT branch_id,xid,resource_id,status,service_name,callback_url,sequence,retry_count,created_at,updated_at,next_retry_at FROM easy_at_branch WHERE xid=? ORDER BY sequence";
        List<AtBranch> out = new ArrayList<AtBranch>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, xid);
            try (ResultSet r = p.executeQuery()) {
                while (r.next()) out.add(map(r));
            }
            return out;
        } catch (SQLException e) {
            throw new AtException("Cannot list AT branches", e);
        }
    }

    @Override
    public List<AtBranch> pendingActions(long now, int limit) {
        String sql =
                "SELECT branch_id,xid,resource_id,status,service_name,callback_url,sequence,retry_count,created_at,updated_at,next_retry_at FROM easy_at_branch WHERE status IN('ROLLING_BACK','ROLLBACK_FAILED') AND (next_retry_at IS NULL OR next_retry_at<=?) ORDER BY sequence";
        List<AtBranch> out = new ArrayList<AtBranch>();
        try (Connection c = dataSource.getConnection();
                PreparedStatement p = c.prepareStatement(sql)) {
            p.setTimestamp(1, new Timestamp(now));
            try (ResultSet r = p.executeQuery()) {
                while (r.next() && out.size() < limit) out.add(map(r));
            }
            return out;
        } catch (SQLException e) {
            throw new AtException("Cannot scan pending branches", e);
        }
    }

    @Override
    public void updateRecovery(String branchId, int retries, long nextRetryAt) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement p =
                        c.prepareStatement(
                                "UPDATE easy_at_branch SET retry_count=?,next_retry_at=?,updated_at=? WHERE branch_id=?")) {
            p.setInt(1, retries);
            p.setTimestamp(2, nextRetryAt <= 0 ? null : new Timestamp(nextRetryAt));
            p.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            p.setString(4, branchId);
            p.executeUpdate();
        } catch (SQLException e) {
            throw new AtException("Cannot update branch recovery", e);
        }
    }

    @Override
    public void save(AtBranch b) {
        Optional<AtBranch> existing = find(b.getBranchId());
        if (existing.isPresent()) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement p =
                            c.prepareStatement(
                                    "UPDATE easy_at_branch SET status=?,updated_at=? WHERE branch_id=?")) {
                p.setString(1, b.getStatus().name());
                p.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
                p.setString(3, b.getBranchId());
                p.executeUpdate();
            } catch (SQLException e) {
                throw new AtException("Cannot save AT branch", e);
            }
        } else register(b);
    }

    private AtBranch map(ResultSet r) throws SQLException {
        Timestamp next = r.getTimestamp(11);
        return new AtBranch(
                r.getString(1),
                r.getString(2),
                r.getString(3),
                r.getString(5),
                r.getString(6),
                r.getInt(7),
                BranchStatus.valueOf(r.getString(4)),
                r.getInt(8),
                r.getTimestamp(9).getTime(),
                r.getTimestamp(10).getTime(),
                next == null ? 0 : next.getTime());
    }
}

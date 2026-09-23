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
        String sql =
                "INSERT INTO easy_at_branch(branch_id,xid,resource_id,status,service_name,callback_url,sequence,retry_count,next_retry_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,0,NULL,?,?)";
        try (Connection c = dataSource.getConnection();
                PreparedStatement p = c.prepareStatement(sql)) {
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
        } catch (SQLException e) {
            throw new AtException("Cannot register AT branch", e);
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

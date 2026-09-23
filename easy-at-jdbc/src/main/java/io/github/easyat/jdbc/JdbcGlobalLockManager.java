package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import java.sql.*;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/** Database global lock using the primary key of easy_at_lock as the atomic conflict point. */
public final class JdbcGlobalLockManager implements GlobalLockManager, AutoCloseable {
    private final DataSource dataSource;
    private final long leaseMillis;
    private final long waitMillis;
    private final ScheduledExecutorService renewer;
    private final Set<String> held = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public JdbcGlobalLockManager(DataSource dataSource, long leaseMillis) {
        this(dataSource, leaseMillis, 0L);
    }

    public JdbcGlobalLockManager(DataSource dataSource, long leaseMillis, long waitMillis) {
        this.dataSource = dataSource;
        this.leaseMillis = leaseMillis;
        this.waitMillis = waitMillis;
        // 单线程续租调度器：周期 = lease/3，确保锁不会因业务过长而意外过期
        this.renewer =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "easy-at-lock-renew");
                            t.setDaemon(true);
                            return t;
                        });
        long period = Math.max(1000L, leaseMillis / 3);
        this.renewer.scheduleWithFixedDelay(this::renewAll, period, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public void acquire(String resource, String table, String key, String xid) {
        acquire(resource, table, key, xid, waitMillis);
    }

    /**
     * 获取全局行锁。核心是「{@code easy_at_lock} 表主键唯一约束」充当原子冲突点：
     *
     * <ol>
     *   <li>{@link #tryInsert} 尝试插入锁行，插入成功即持锁；
     *   <li>同 XID 重入则直接续租；
     *   <li>他人持锁且租约未过期 → 按 waitMillis 短暂自旋等待，超时抛冲突；
     *   <li>租约过期 → 查全局事务状态：已收敛则安全清除陈旧锁，仍在途则拒绝接管（防误删）。
     * </ol>
     */
    @Override
    public void acquire(String resource, String table, String key, String xid, long waitMillis) {
        long deadline =
                waitMillis <= 0 ? 0 : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis);
        while (!closed.get()) {
            if (tryInsert(resource, table, key, xid)) return;
            LockRow existing = read(resource, table, key);
            if (existing == null) continue;
            if (existing.xid.equals(xid)) {
                renew(resource, table, key, xid);
                return;
            }
            long now = System.currentTimeMillis();
            if (existing.leaseUntil < now) {
                String status = globalStatus(existing.xid);
                if (status == null || isConverged(status)) {
                    deleteStale(resource, table, key, existing.xid, existing.leaseUntil);
                    continue;
                }
                throw new GlobalLockConflictException(
                        "Lock on "
                                + resource
                                + "/"
                                + table
                                + "/"
                                + key
                                + " is protected by in-flight transaction "
                                + existing.xid);
            }
            if (waitMillis > 0 && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new AtException("Interrupted while acquiring global lock", ie);
                }
                continue;
            }
            throw new GlobalLockConflictException(
                    "Global lock conflict: "
                            + resource
                            + "/"
                            + table
                            + "/"
                            + key
                            + " held by "
                            + existing.xid);
        }
        throw new AtException("Lock manager is closed");
    }

    @Override
    public void releaseByXid(String xid) {
        held.remove(xid);
        try (Connection c = dataSource.getConnection();
                PreparedStatement p = c.prepareStatement("DELETE FROM easy_at_lock WHERE xid=?")) {
            p.setString(1, xid);
            p.executeUpdate();
        } catch (SQLException e) {
            throw new AtException("Cannot release JDBC locks", e);
        }
    }

    private boolean tryInsert(String r, String t, String k, String xid) {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection();
                PreparedStatement p =
                        c.prepareStatement(
                                "INSERT INTO easy_at_lock(resource_id,table_name,pk_value,xid,lease_until,created_at) VALUES(?,?,?,?,?,?)")) {
            p.setString(1, r);
            p.setString(2, t);
            p.setString(3, k);
            p.setString(4, xid);
            p.setTimestamp(5, new Timestamp(now + leaseMillis));
            p.setTimestamp(6, new Timestamp(now));
            p.executeUpdate();
            held.add(xid);
            return true;
        } catch (SQLException duplicate) {
            return false;
        }
    }

    private LockRow read(String r, String t, String k) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement p =
                        c.prepareStatement(
                                "SELECT xid,lease_until FROM easy_at_lock WHERE resource_id=? AND table_name=? AND pk_value=?")) {
            p.setString(1, r);
            p.setString(2, t);
            p.setString(3, k);
            try (ResultSet rs = p.executeQuery()) {
                if (!rs.next()) return null;
                return new LockRow(rs.getString(1), rs.getTimestamp(2).getTime());
            }
        } catch (SQLException e) {
            throw new AtException("Cannot read JDBC global lock", e);
        }
    }

    private void renew(String r, String t, String k, String xid) {
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection();
                PreparedStatement p =
                        c.prepareStatement(
                                "UPDATE easy_at_lock SET lease_until=? WHERE resource_id=? AND table_name=? AND pk_value=? AND xid=?")) {
            p.setTimestamp(1, new Timestamp(now + leaseMillis));
            p.setString(2, r);
            p.setString(3, t);
            p.setString(4, k);
            p.setString(5, xid);
            p.executeUpdate();
        } catch (SQLException e) {
            throw new AtException("Cannot renew JDBC global lock", e);
        }
    }

    private boolean deleteStale(String r, String t, String k, String oldXid, long oldLease) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement p =
                        c.prepareStatement(
                                "DELETE FROM easy_at_lock WHERE resource_id=? AND table_name=? AND pk_value=? AND xid=? AND lease_until<=?")) {
            p.setString(1, r);
            p.setString(2, t);
            p.setString(3, k);
            p.setString(4, oldXid);
            p.setTimestamp(5, new Timestamp(oldLease));
            return p.executeUpdate() >= 0;
        } catch (SQLException e) {
            throw new AtException("Cannot clear stale JDBC global lock", e);
        }
    }

    private String globalStatus(String xid) {
        try (Connection c = dataSource.getConnection();
                PreparedStatement p =
                        c.prepareStatement("SELECT status FROM easy_at_global WHERE xid=?")) {
            p.setString(1, xid);
            try (ResultSet rs = p.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new AtException("Cannot read global status for lock takeover", e);
        }
    }

    private void renewAll() {
        if (closed.get() || held.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (String xid : held) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement p =
                            c.prepareStatement(
                                    "UPDATE easy_at_lock SET lease_until=? WHERE xid=? AND lease_until<?")) {
                p.setTimestamp(1, new Timestamp(now + leaseMillis));
                p.setString(2, xid);
                p.setTimestamp(3, new Timestamp(now + leaseMillis));
                p.executeUpdate();
            } catch (SQLException ignored) {
            }
        }
    }

    private static boolean isConverged(String status) {
        return "COMMITTED".equals(status)
                || "ROLLED_BACK".equals(status)
                || "MANUAL_INTERVENTION".equals(status)
                || "DIRTY_WRITE".equals(status);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) renewer.shutdownNow();
    }

    private static final class LockRow {
        final String xid;
        final long leaseUntil;

        LockRow(String xid, long leaseUntil) {
            this.xid = xid;
            this.leaseUntil = leaseUntil;
        }
    }
}

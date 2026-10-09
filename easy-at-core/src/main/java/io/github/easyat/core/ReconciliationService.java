package io.github.easyat.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 影子运行对账：把"静默故障"变成可观测的数字。
 *
 * <p>框架里所有会导致数据不一致的情形都有一个共同点——<b>不抛异常、不告警、不收敛</b>： 事务卡在中间态没人推进、锁还挂着但事务已经结束、反复重试失败后转人工介入。这些只能靠定期
 * 对账发现，所以投产前必须连续跑（建议 ≥7 天）并把下面几项做成告警：
 *
 * <pre>
 *   counts.manualIntervention  > 0   → CRITICAL，有数据需要人工修复
 *   counts.dirtyWrite          > 0   → CRITICAL，回滚时发现行已被别人改过
 *   counts.leakedLocks         > 0   → CRITICAL，同行的其他事务会被永久拒绝
 *   counts.activeTimedOut      > 0   → WARN，超时事务没有被恢复调度接管
 *   counts.rollingBackStuck    > 0   → WARN，回滚卡住且没有有效租约
 *   counts.committingStuck     > 0   → WARN，本地已提交但全局未收敛
 * </pre>
 *
 * <p>本类只依赖 {@link AtRepository} / {@link BranchRepository} / {@link GlobalLockManager}， 因此
 * JDBC、File、Redis 三种存储都能对账；锁的全量扫描由 {@link GlobalLockManager#heldLocks()} 提供（目前只有 JDBC 实现，Redis
 * 侧请配合运维手册里的 {@code redis-cli --scan} 片段）。
 */
public final class ReconciliationService {
    private final AtRepository repository;
    private final BranchRepository branches;
    private final GlobalLockManager locks;
    private final String node;
    private final long stuckAfterMillis;
    private final int scanLimit;

    public ReconciliationService(
            AtRepository repository,
            BranchRepository branches,
            GlobalLockManager locks,
            String node,
            long stuckAfterMillis,
            int scanLimit) {
        this.repository = repository;
        this.branches = branches;
        this.locks = locks;
        this.node = node == null ? "unknown" : node;
        this.stuckAfterMillis = stuckAfterMillis <= 0 ? 60000L : stuckAfterMillis;
        this.scanLimit = scanLimit <= 0 ? 500 : scanLimit;
    }

    public ReconciliationService(
            AtRepository repository,
            BranchRepository branches,
            GlobalLockManager locks,
            String node) {
        this(repository, branches, locks, node, 60000L, 500);
    }

    public ReconciliationReport report() {
        return report(System.currentTimeMillis());
    }

    public ReconciliationReport report(long now) {
        ReconciliationReport r = new ReconciliationReport(node, stuckAfterMillis);
        int scanned = 0;

        for (AtStatus status : AtStatus.values()) {
            List<AtTransaction> txs;
            try {
                txs = repository.findByStatus(status, scanLimit);
            } catch (Exception e) {
                // 单个状态扫描失败不能让整份对账报告出不来
                continue;
            }
            for (AtTransaction tx : txs) {
                scanned++;
                switch (tx.getStatus()) {
                    case ACTIVE:
                        // 已超过 deadline 并且又过了 stuckAfterMillis 还没被恢复调度接管
                        if (tx.getDeadline() > 0 && tx.getDeadline() + stuckAfterMillis < now)
                            r.getActiveTimedOut().add(tx.getXid());
                        break;
                    case ROLLING_BACK:
                        // 没有租约或租约已过期 ⇒ 当前没有任何实例在推进这次回滚
                        if (tx.getLeaseUntil() <= 0 || tx.getLeaseUntil() < now)
                            r.getRollingBackStuck().add(tx.getXid());
                        break;
                    case COMMITTING:
                        // COMMITTING 是瞬时状态：本地已提交、只剩全局推进。长期停留即异常。
                        if (tx.getNextRetryAt() <= now) r.getCommittingStuck().add(tx.getXid());
                        break;
                    case ROLLBACK_FAILED:
                        r.getRollbackFailed().add(tx.getXid());
                        break;
                    case DIRTY_WRITE:
                        r.getDirtyWrite().add(tx.getXid());
                        break;
                    case MANUAL_INTERVENTION:
                        r.getManualIntervention().add(tx.getXid());
                        break;
                    default:
                        break;
                }
            }
        }
        r.setScannedTransactions(scanned);

        scanLocks(r, now);
        scanBranches(r);
        return r;
    }

    /** 锁泄漏：锁还在，但它所属的事务已经终态或者压根不存在。 */
    private void scanLocks(ReconciliationReport r, long now) {
        if (locks == null) return;
        List<GlobalLockRef> held;
        try {
            held = locks.heldLocks();
        } catch (Exception e) {
            return; // 存储不支持全量扫描：交给运维脚本兜底
        }
        r.setScannedLocks(held.size());
        for (GlobalLockRef lock : held) {
            AtTransaction tx = repository.find(lock.getXid()).orElse(null);
            if (tx == null) {
                r.getLeakedLocks()
                        .add(
                                lock.getResourceId()
                                        + "/"
                                        + lock.getTableName()
                                        + "/"
                                        + lock.getPrimaryKey()
                                        + " <- orphan xid "
                                        + lock.getXid());
                continue;
            }
            if (tx.getStatus().isTerminal()) {
                r.getLeakedLocks()
                        .add(
                                lock.getResourceId()
                                        + "/"
                                        + lock.getTableName()
                                        + "/"
                                        + lock.getPrimaryKey()
                                        + " <- xid "
                                        + lock.getXid()
                                        + " is "
                                        + tx.getStatus());
            }
        }
    }

    /** 分支悬挂：全局事务仍在途，但它的某个分支已经终态（该分支的本地结果不会被再驱动）。 */
    private void scanBranches(ReconciliationReport r) {
        if (branches == null) return;
        List<String> inFlightXids = new ArrayList<String>();
        for (AtStatus status : new AtStatus[] {AtStatus.ACTIVE, AtStatus.COMMITTING}) {
            List<AtTransaction> txs;
            try {
                txs = repository.findByStatus(status, scanLimit);
            } catch (Exception e) {
                continue;
            }
            for (AtTransaction tx : txs) inFlightXids.add(tx.getXid());
        }
        for (String xid : inFlightXids) {
            List<AtBranch> list;
            try {
                list = branches.byXid(xid);
            } catch (Exception e) {
                continue;
            }
            for (AtBranch b : list)
                if (b.getStatus() == BranchStatus.ROLLED_BACK
                        || b.getStatus() == BranchStatus.COMMITTED)
                    r.getHangingBranches().add(xid + " branch " + b.getBranchId());
        }
    }
}

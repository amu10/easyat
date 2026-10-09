package io.github.easyat.core;

import java.util.List;
import java.util.Optional;

/** Persistence for AT branches (one participating DataSource per global transaction). */
public interface BranchRepository {
    void register(AtBranch branch);

    Optional<AtBranch> find(String branchId);

    /** CAS on branch status. Returns true if the row matched expected status and was updated. */
    boolean transition(String branchId, BranchStatus expected, BranchStatus next);

    List<AtBranch> byXid(String xid);

    /** Branches that still need a commit/rollback callback delivered (due for retry). */
    List<AtBranch> pendingActions(long now, int limit);

    void updateRecovery(String branchId, int retries, long nextRetryAt);

    void save(AtBranch branch);

    /**
     * 按 (xid, resourceId) 定位分支——同一资源在同一全局事务下只允许存在一条分支。
     *
     * <p>用于两件事：① 空回滚时判断该资源是否已经有分支记录（避免重复占位）； ② 防悬挂判断某资源是否已被回滚。
     */
    default Optional<AtBranch> findByXidResource(String xid, String resourceId) {
        if (xid == null || resourceId == null) return Optional.empty();
        for (AtBranch b : byXid(xid))
            if (resourceId.equals(b.getResourceId())) return Optional.of(b);
        return Optional.empty();
    }
}

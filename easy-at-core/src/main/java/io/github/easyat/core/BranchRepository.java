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
}

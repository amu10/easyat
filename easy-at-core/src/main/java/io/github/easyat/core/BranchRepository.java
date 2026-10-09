package io.github.easyat.core;

import java.util.List;
import java.util.Optional;

/** Persistence for AT branches (one participating DataSource per global transaction). */
public interface BranchRepository {
    void register(AtBranch branch);

    /**
     * 在调用方给定的「业务本地连接」上注册分支，使分支行与业务 DML、undo log 处于同一个本地事务。
     *
     * <p>参数是 {@code Object} 而不是 {@code java.sql.Connection}，是为了让 core 保持存储无关 （core 不依赖 JDBC）。JDBC
     * 实现在识别到 {@code java.sql.Connection} 时接管，其余情况返回 {@code false}，调用方回退到 {@link
     * #register(AtBranch)}。
     *
     * <p>语义要求：业务本地事务回滚时，分支行必须一起消失——资源没做成任何事，就不该留下分支记录。 否则会留下一个永远不会被提交也不会被回滚的悬挂分支。
     *
     * @return true 表示已在该连接上完成注册（含幂等命中）；false 表示不支持，调用方需自行回退
     */
    default boolean registerIn(AtBranch branch, Object localConnection) {
        return false;
    }

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

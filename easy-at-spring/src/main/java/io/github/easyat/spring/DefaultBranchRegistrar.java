package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.MDC;

/**
 * Registers a LOCAL branch (no callback URL) for a resource the first time it executes DML inside
 * an active global transaction. Idempotent per (xid, resourceId). The {@link BranchRepository} is
 * resolved lazily through a {@link Supplier} so constructing this registrar does not force an early
 * DataSource dependency (which would break the DataSource BeanPostProcessor cycle).
 */
public final class DefaultBranchRegistrar implements BranchRegistrar {
    private final Supplier<BranchRepository> repository;
    private final String appName;

    public DefaultBranchRegistrar(Supplier<BranchRepository> repository, String appName) {
        this.repository = repository;
        this.appName = appName;
    }

    @Override
    public void register(String xid, String resourceId) {
        BranchRepository repo = repository.get();
        if (repo == null) return;
        Optional<AtBranch> existing = repo.findByXidResource(xid, resourceId);
        if (existing.isPresent()) {
            // 防悬挂（Anti-hanging）：rollback 先于本次 DML 到达时，占位记录已把本资源标记为已回滚。
            // 此时必须拒绝执行——否则这次 DML 产生的 undo 会挂在一个已终结的全局事务下，
            // 既不会被回滚，也不会被告警，成为永久的悬挂数据。
            if (existing.get().getStatus().isRolledBack())
                throw new AtException(
                        "Rejected DML on already-rolled-back branch: xid="
                                + xid
                                + " resource="
                                + resourceId);
            return; // already registered
        }
        repo.register(new AtBranch(xid, resourceId, appName, null, repo.byXid(xid).size() + 1));
        MDC.put("easyAtResourceId", resourceId);
    }
}

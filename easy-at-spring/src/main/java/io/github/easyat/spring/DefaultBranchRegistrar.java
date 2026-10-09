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
        register(xid, resourceId, null);
    }

    /**
     * 传入业务本地连接时优先把分支行写进业务事务（JDBC 存储），保证「分支注册 / undo log / 业务 DML」 三者同生共死。存储不支持时（Redis / File
     * 没有本地事务概念）自动回退到独立连接注册。
     */
    @Override
    public void register(String xid, String resourceId, Object localConnection) {
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
        AtBranch branch = new AtBranch(xid, resourceId, appName, null, repo.byXid(xid).size() + 1);
        if (localConnection == null || !repo.registerIn(branch, localConnection))
            repo.register(branch);
        MDC.put("easyAtResourceId", resourceId);
    }
}

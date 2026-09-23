package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.util.List;
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
        List<AtBranch> existing = repo.byXid(xid);
        for (AtBranch b : existing)
            if (b.getResourceId().equals(resourceId)) return; // already registered
        repo.register(new AtBranch(xid, resourceId, appName, null, existing.size() + 1));
        MDC.put("easyAtResourceId", resourceId);
    }
}

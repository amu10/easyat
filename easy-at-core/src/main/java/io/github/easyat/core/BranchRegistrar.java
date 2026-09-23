package io.github.easyat.core;

/**
 * Hook invoked by the AT DataSource proxy the first time a participating resource executes DML
 * inside an active global transaction. Implementations register a branch so the coordination layer
 * knows about this resource's work. The default {@link #NOOP} does nothing (single-service mode).
 */
public interface BranchRegistrar {
    /** Register a branch for (xid, resourceId) if not already registered. Must be idempotent. */
    void register(String xid, String resourceId);

    BranchRegistrar NOOP =
            new BranchRegistrar() {
                @Override
                public void register(String xid, String resourceId) {
                    /* no-op */
                }
            };
}

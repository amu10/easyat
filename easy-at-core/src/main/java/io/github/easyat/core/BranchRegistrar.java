package io.github.easyat.core;

/**
 * Hook invoked by the AT DataSource proxy the first time a participating resource executes DML
 * inside an active global transaction. Implementations register a branch so the coordination layer
 * knows about this resource's work. The default {@link #NOOP} does nothing (single-service mode).
 */
public interface BranchRegistrar {
    /** Register a branch for (xid, resourceId) if not already registered. Must be idempotent. */
    void register(String xid, String resourceId);

    /**
     * 同 {@link #register(String, String)}，但传入当前业务连接，让实现有机会把分支行写进业务本地事务。
     *
     * <p>默认实现忽略连接、走两参数版本（保持既有实现的二进制兼容）。
     */
    default void register(String xid, String resourceId, Object localConnection) {
        register(xid, resourceId);
    }

    BranchRegistrar NOOP =
            new BranchRegistrar() {
                @Override
                public void register(String xid, String resourceId) {
                    /* no-op */
                }
            };
}

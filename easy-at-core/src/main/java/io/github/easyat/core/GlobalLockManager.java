package io.github.easyat.core;

public interface GlobalLockManager {
    /**
     * Acquire the global row lock. Implementations may block up to a configured wait before
     * giving up. Throws {@link GlobalLockConflictException} if the lock cannot be obtained.
     */
    void acquire(String resourceId, String tableName, String primaryKey, String xid);
    /** Acquire with an explicit wait in milliseconds (0 = fail fast). */
    default void acquire(String resourceId, String tableName, String primaryKey, String xid, long waitMillis){ acquire(resourceId, tableName, primaryKey, xid); }
    void releaseByXid(String xid);
}

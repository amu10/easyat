package io.github.easyat.core;

public interface GlobalLockManager {
    void acquire(String resourceId, String tableName, String primaryKey, String xid);
    void releaseByXid(String xid);
}

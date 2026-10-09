package io.github.easyat.core;

import java.util.Collections;
import java.util.List;

public interface GlobalLockManager {
    /**
     * Acquire the global row lock. Implementations may block up to a configured wait before giving
     * up. Throws {@link GlobalLockConflictException} if the lock cannot be obtained.
     */
    void acquire(String resourceId, String tableName, String primaryKey, String xid);

    /** Acquire with an explicit wait in milliseconds (0 = fail fast). */
    default void acquire(
            String resourceId, String tableName, String primaryKey, String xid, long waitMillis) {
        acquire(resourceId, tableName, primaryKey, xid);
    }

    void releaseByXid(String xid);

    /**
     * 全量快照当前持有的锁，供对账判断"锁泄漏"（锁还在、但所属事务已终态或已不存在）。
     *
     * <p>默认返回空列表：全量扫描并非所有存储都能高效支持（Redis 需要 SCAN 且难以跨 jedis 版本稳定调用）， 这种情况下请配合运维手册里的脚本对账。
     */
    default List<GlobalLockRef> heldLocks() {
        return Collections.emptyList();
    }
}

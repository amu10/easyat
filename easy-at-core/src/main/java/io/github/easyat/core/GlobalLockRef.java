package io.github.easyat.core;

/** 一条全局行锁的快照，供对账（{@link ReconciliationService}）判断锁是否泄漏。 */
public final class GlobalLockRef {
    /** 资源 id（对应哪个数据源）。 */
    private final String resourceId;

    /** 被锁的表名。 */
    private final String tableName;

    /** 被锁的主键值。 */
    private final String primaryKey;

    /** 持有该锁的全局事务 xid。 */
    private final String xid;

    /** 锁租约到期时间（毫秒时间戳）；用于判断租约是否过期。 */
    private final long leaseUntil;

    public GlobalLockRef(
            String resourceId, String tableName, String primaryKey, String xid, long leaseUntil) {
        this.resourceId = resourceId;
        this.tableName = tableName;
        this.primaryKey = primaryKey;
        this.xid = xid;
        this.leaseUntil = leaseUntil;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getTableName() {
        return tableName;
    }

    public String getPrimaryKey() {
        return primaryKey;
    }

    public String getXid() {
        return xid;
    }

    public long getLeaseUntil() {
        return leaseUntil;
    }

    @Override
    public String toString() {
        return resourceId
                + "/"
                + tableName
                + "/"
                + primaryKey
                + " <- "
                + xid
                + " leaseUntil="
                + leaseUntil;
    }
}

package io.github.easyat.core;

/** 一条全局行锁的快照，供对账（{@link ReconciliationService}）判断锁是否泄漏。 */
public final class GlobalLockRef {
    private final String resourceId;
    private final String tableName;
    private final String primaryKey;
    private final String xid;
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

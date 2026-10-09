package io.github.easyat.core;

import java.io.Serializable;
import java.util.UUID;

/** A branch is one participating DataSource's AT work for a global transaction. */
public final class AtBranch implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 分支的五个标识/归属字段：分支 id（UUID）、所属全局事务 xid、资源 id、 发起方服务名、回调地址（TCC 类分支用于反向通知）。 */
    private final String branchId, xid, resourceId, serviceName, callbackUrl;

    /** 同一全局事务下分支的执行顺序，回滚/提交按此排序。 */
    private final int sequence;

    /** 分支当前状态（见 {@link BranchStatus} 的合法迁移表）。 */
    private BranchStatus status = BranchStatus.REGISTERED;

    /** 已重试次数（TCC 分支回调失败后的补偿重试）。 */
    private int retries;

    /** 创建时间、最后更新时间、下一次重试时间点（毫秒时间戳）。 */
    private long createdAt, updatedAt, nextRetryAt;

    public AtBranch(
            String xid, String resourceId, String serviceName, String callbackUrl, int sequence) {
        this.branchId = UUID.randomUUID().toString();
        this.xid = xid;
        this.resourceId = resourceId;
        this.serviceName = serviceName;
        this.callbackUrl = callbackUrl;
        this.sequence = sequence;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public AtBranch(
            String branchId,
            String xid,
            String resourceId,
            String serviceName,
            String callbackUrl,
            int sequence,
            BranchStatus status,
            int retries,
            long createdAt,
            long updatedAt,
            long nextRetryAt) {
        this.branchId = branchId;
        this.xid = xid;
        this.resourceId = resourceId;
        this.serviceName = serviceName;
        this.callbackUrl = callbackUrl;
        this.sequence = sequence;
        this.status = status;
        this.retries = retries;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.nextRetryAt = nextRetryAt;
    }

    public String getBranchId() {
        return branchId;
    }

    public String getXid() {
        return xid;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getServiceName() {
        return serviceName;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public int getSequence() {
        return sequence;
    }

    public BranchStatus getStatus() {
        return status;
    }

    public void setStatus(BranchStatus s) {
        this.status = s;
    }

    public void applyTransition(BranchStatus s) {
        this.status = s;
    }

    public int getRetries() {
        return retries;
    }

    public void setRetries(int r) {
        retries = r;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long v) {
        updatedAt = v;
    }

    public long getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(long v) {
        nextRetryAt = v;
    }
}

package io.github.easyat.core;

import java.io.Serializable;
import java.util.*;

/**
 * Persisted global transaction. The version column is the optimistic lock that makes all status
 * transitions safe under concurrent recovery instances (see {@link AtRepository#transition}).
 */
public final class AtTransaction implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 全局事务 id（UUID）与业务可读名称。 */
    private final String xid, name;

    /** 创建时间、超时时间点（毫秒时间戳）。超时未提交则进入恢复扫描。 */
    private final long createdAt, deadline;

    /** 当前状态（见 {@link AtStatus} 的合法迁移表）。 */
    private AtStatus status = AtStatus.ACTIVE;

    /** 已重试次数。 */
    private int retries;

    /** 下一次允许重试的时间点（毫秒时间戳）。 */
    private long nextRetryAt;

    /** 乐观锁版本号：每次状态迁移 +1，CAS 据此保证并发恢复不重复驱动同一事务。 */
    private long version;

    /** 当前持有恢复租约的实例标识；为空表示无主。 */
    private String owner;

    /** 恢复租约到期时间（毫秒时间戳）；到期后方可被其他实例接管。 */
    private long leaseUntil;

    /** 进入 DIRTY_WRITE 时记录的问题表名与主键，供人工介入定位。 */
    private String dirtyWriteTable;

    private String dirtyWriteKey;

    /** 本事务的全部 undo 记录（内存快照，持久化由存储层负责）。 */
    private final List<UndoRecord> undoRecords = new ArrayList<UndoRecord>();

    public AtTransaction(String xid, String name, long now, long deadline) {
        this.xid = xid;
        this.name = name;
        this.createdAt = now;
        this.deadline = deadline;
    }

    public String getXid() {
        return xid;
    }

    public String getName() {
        return name;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public long getDeadline() {
        return deadline;
    }

    public AtStatus getStatus() {
        return status;
    }

    public void setStatus(AtStatus s) {
        AtStatus.validate(this.status, s);
        this.status = s;
    }

    public int getRetries() {
        return retries;
    }

    public void incrementRetries() {
        retries++;
    }

    public void setRetries(int r) {
        retries = r;
    }

    public long getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(long v) {
        nextRetryAt = v;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long v) {
        version = v;
    }

    public long versionAfter() {
        return version + 1;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String o) {
        owner = o;
    }

    public long getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(long v) {
        leaseUntil = v;
    }

    public String getDirtyWriteTable() {
        return dirtyWriteTable;
    }

    public void setDirtyWriteTable(String t) {
        dirtyWriteTable = t;
    }

    public String getDirtyWriteKey() {
        return dirtyWriteKey;
    }

    public void setDirtyWriteKey(String k) {
        dirtyWriteKey = k;
    }

    public List<UndoRecord> getUndoRecords() {
        return Collections.unmodifiableList(undoRecords);
    }

    public void addUndo(UndoRecord r) {
        undoRecords.add(r);
    }

    public void removeUndo(String undoId) {
        for (Iterator<UndoRecord> it = undoRecords.iterator(); it.hasNext(); )
            if (it.next().getId().equals(undoId)) {
                it.remove();
                return;
            }
    }

    /** Restores persisted state without transition validation (used when loading from storage). */
    public void restore(AtStatus status, int retries, long nextRetryAt) {
        this.status = status;
        this.retries = retries;
        this.nextRetryAt = nextRetryAt;
    }

    public void applyTransition(AtStatus next) {
        this.status = next;
        this.version++;
    }
}

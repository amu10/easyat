package io.github.easyat.core;

import java.sql.Connection;
import java.util.*;

/**
 * 全局事务的驱动器：负责事务的创建、undo 记录追加、提交、回滚与恢复。
 *
 * <p>这是框架的「指挥中枢」，但本身不保存任何业务状态——所有决定恢复结果的状态 （状态机、undo 记录、租约、重试计数）都持久化到 {@link AtRepository}。 内存里的
 * {@link AtTransaction} 只是存储快照，真正的并发正确性由存储层的 CAS 保证。
 *
 * <p>关键设计（对应 DESIGN.md §4）：
 *
 * <ul>
 *   <li><b>状态迁移必须 CAS</b>：{@link #transition} 把「期望状态 + 版本号」作为 WHERE 条件 提交给存储层，多实例并发回滚同一 XID
 *       时只有一个实例能改成功，其余拿不到 （返回 false）从而放弃，避免重复补偿。
 *   <li><b>回滚按逆序执行 undo</b>：先写后回滚，符合 AT 的补偿顺序。
 *   <li><b>失败进入退避重试</b>：{@link #backoff} 指数退避，上限 5 分钟，配合 {@link #recover} 与 {@link
 *       AtRecoveryScheduler} 实现自动恢复。
 * </ul>
 */
public final class AtTransactionManager {
    private final AtRepository repository;
    private final UndoExecutor undoExecutor;
    private final GlobalLockManager lockManager;
    private final int maxRetries;

    public AtTransactionManager(AtRepository r, UndoExecutor u, int maxRetries) {
        this(r, u, null, maxRetries);
    }

    public AtTransactionManager(
            AtRepository r, UndoExecutor u, GlobalLockManager locks, int maxRetries) {
        repository = r;
        undoExecutor = u;
        lockManager = locks;
        this.maxRetries = maxRetries;
    }

    /** 开启一个全局事务：生成 XID、持久化 ACTIVE 行、绑定到当前线程上下文。 */
    public AtTransaction begin(String name, long timeout) {
        long now = System.currentTimeMillis();
        AtTransaction tx =
                new AtTransaction(UUID.randomUUID().toString(), name, now, now + timeout);
        repository.create(tx);
        AtContext.bind(tx.getXid());
        return tx;
    }

    /** 加入调用方已创建的全局事务（跨服务传播场景）。要求两个服务共享同一个 Repository。 */
    public void join(String xid) {
        required(xid);
        AtContext.bind(xid);
    }

    /** 追加一条 undo 记录（非连接绑定路径，如 File 存储）。 */
    public void append(UndoRecord record) {
        AtTransaction tx = required(record.getXid());
        tx.addUndo(record);
        repository.save(tx);
    }

    /** 通过业务连接持久化 undo（连接绑定路径，与业务 DML 同连接、同事务提交）。 */
    public void append(Connection connection, UndoRecord record) {
        if (repository instanceof ConnectionBoundAtRepository) {
            ((ConnectionBoundAtRepository) repository).append(connection, record);
            return;
        }
        append(record);
    }

    public void updateUndo(String xid, String undoId, RowImage after) {
        AtTransaction tx = required(xid);
        for (UndoRecord r : tx.getUndoRecords())
            if (r.getId().equals(undoId)) {
                r.setAfterImage(after);
                repository.save(tx);
                return;
            }
        throw new AtException("Undo record not found: " + undoId);
    }

    public void updateUndo(Connection connection, String xid, String undoId, RowImage after) {
        if (repository instanceof ConnectionBoundAtRepository) {
            ((ConnectionBoundAtRepository) repository).updateUndo(connection, xid, undoId, after);
            return;
        }
        updateUndo(xid, undoId, after);
    }

    public void discardUndo(Connection connection, String xid, String undoId) {
        if (repository instanceof ConnectionBoundAtRepository) {
            ((ConnectionBoundAtRepository) repository).removeUndo(connection, xid, undoId);
            return;
        }
        AtTransaction tx = required(xid);
        tx.removeUndo(undoId);
        repository.save(tx);
    }

    /** 提交：ACTIVE→COMMITTING→COMMITTED 两步 CAS，成功后释放该 XID 持有的所有全局锁。 */
    public void commit(String xid) {
        AtTransaction tx = required(xid);
        if (!transition(tx, AtStatus.COMMITTING))
            throw new AtException("Conflict committing " + xid + " from " + tx.getStatus());
        if (!transition(tx, AtStatus.COMMITTED))
            throw new AtException("Conflict committing " + xid + " from " + tx.getStatus());
        release(xid);
    }

    public void rollback(String xid) {
        rollback(xid, true);
    }

    /**
     * 回滚：进入 ROLLING_BACK 后，把 undo 记录<b>逆序</b>逐条执行。 任一条 undo 抛出 {@link DirtyWriteException} 则整笔转
     * DIRTY_WRITE（脏写，人工介入）； 其他异常转 ROLLBACK_FAILED 并按退避时间写入下一次重试点。
     */
    public void rollback(String xid, boolean releaseLockOnConverge) {
        AtTransaction tx = required(xid);
        if (tx.getStatus() == AtStatus.ROLLED_BACK) return;
        if (tx.getStatus() != AtStatus.ROLLING_BACK && !transition(tx, AtStatus.ROLLING_BACK))
            throw new AtException("Conflict rolling back " + xid + " from " + tx.getStatus());
        List<UndoRecord> records = new ArrayList<UndoRecord>(tx.getUndoRecords());
        Collections.reverse(records);
        try {
            for (UndoRecord r : records) {
                if (!r.isRolledBack()) {
                    undoExecutor.rollback(r);
                    r.markRolledBack();
                    repository.save(tx);
                }
            }
            if (!transition(tx, AtStatus.ROLLED_BACK))
                throw new AtException("Conflict finalizing rollback " + xid);
            if (releaseLockOnConverge) release(xid);
        } catch (DirtyWriteException dirty) {
            tx.setDirtyWriteTable(dirty.getMessage());
            if (!transition(tx, AtStatus.DIRTY_WRITE))
                throw new AtException("Conflict recording dirty write " + xid);
            throw dirty;
        } catch (Exception e) {
            if (!transition(tx, AtStatus.ROLLBACK_FAILED))
                throw new AtException("Conflict recording rollback failure " + xid, e);
            tx.setNextRetryAt(System.currentTimeMillis() + backoff(tx.getRetries()));
            repository.updateRecovery(xid, tx.getRetries(), tx.getNextRetryAt());
            throw new AtException("AT rollback failed: " + xid, e);
        }
    }

    /** 恢复一个待处理事务：重试次数耗尽则转 MANUAL_INTERVENTION（人工介入）， 否则自增重试计数、写退避后的下一次重试时间，再执行回滚。 */
    public void recover(AtTransaction tx) {
        if (tx.getRetries() >= maxRetries) {
            if (transition(tx, AtStatus.MANUAL_INTERVENTION)) return;
            throw new AtException("Cannot move " + tx.getXid() + " to MANUAL_INTERVENTION");
        }
        tx.incrementRetries();
        repository.updateRecovery(
                tx.getXid(),
                tx.getRetries(),
                System.currentTimeMillis() + backoff(tx.getRetries()));
        rollback(tx.getXid(), true);
    }

    /** 管理端驱动的状态变更（审计由调用方负责，见 ManagementService）。 */
    public boolean forceTransition(String xid, AtStatus to) {
        AtTransaction tx = required(xid);
        return transition(tx, to);
    }

    public boolean claimLease(String xid, String owner, long leaseUntil, long now) {
        return repository.claimLease(xid, owner, leaseUntil, now);
    }

    public void releaseLease(String xid, String owner) {
        repository.releaseLease(xid, owner);
    }

    /** 状态迁移核心：先做内存合法性校验，再以 CAS 提交到存储层。 CAS 成功才更新内存快照的版本号，失败返回 false（说明被其他实例抢先）。 */
    private boolean transition(AtTransaction tx, AtStatus to) {
        if (!tx.getStatus().canTransitionTo(to))
            throw new AtException("Illegal AT status transition: " + tx.getStatus() + " -> " + to);
        if (!repository.transition(tx.getXid(), tx.getStatus(), tx.getVersion(), to)) return false;
        tx.applyTransition(to);
        return true;
    }

    private AtTransaction required(String xid) {
        return repository
                .find(xid)
                .orElseThrow(() -> new AtException("Transaction not found: " + xid));
    }

    private void release(String xid) {
        if (lockManager != null) lockManager.releaseByXid(xid);
    }

    /** 指数退避：1s、2s、4s… 上限 5 分钟（重试次数超过 8 次后不再翻倍）。 */
    private long backoff(int retries) {
        return Math.min(300000L, 1000L << Math.min(retries, 8));
    }
}

package io.github.easyat.core;

import java.sql.Connection;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    /** 回滚期间跨实例抢占租约的默认时长。 */
    public static final long DEFAULT_ROLLBACK_LEASE_MILLIS = 30000L;

    private final AtRepository repository;
    private final UndoExecutor undoExecutor;
    private final GlobalLockManager lockManager;
    private final int maxRetries;

    /**
     * undo 的独立存储（混合存储模式）。为 null 时表示 undo 跟随 {@link #repository}。
     *
     * <p>混合模式下 undo 必须在业务库（跟着业务本地事务提交），而全局状态在 Redis，两者不是同一个存储。
     */
    private final UndoRepository undoRepository;

    /** 本实例标识；为 null 表示不启用跨实例租约保护（单实例嵌入式用法）。 */
    private final String owner;

    private final long rollbackLeaseMillis;

    /** 本进程内正在回滚的 XID，用于拦截同一 JVM 多线程并发回滚。 */
    private final Set<String> rollbackInFlight = ConcurrentHashMap.newKeySet();

    /** 哪些 XID 的租约是本次调用自己抢来的（结束时才需要释放）。 */
    private final Map<String, Boolean> selfClaimed = new ConcurrentHashMap<String, Boolean>();

    public AtTransactionManager(AtRepository r, UndoExecutor u, int maxRetries) {
        this(r, u, null, maxRetries);
    }

    public AtTransactionManager(
            AtRepository r, UndoExecutor u, GlobalLockManager locks, int maxRetries) {
        this(r, u, locks, maxRetries, null, DEFAULT_ROLLBACK_LEASE_MILLIS);
    }

    public AtTransactionManager(
            AtRepository r,
            UndoExecutor u,
            GlobalLockManager locks,
            int maxRetries,
            String owner,
            long rollbackLeaseMillis) {
        this(r, u, locks, maxRetries, owner, rollbackLeaseMillis, null);
    }

    /**
     * 混合存储构造器：全局状态走 {@code repository}，undo 走 {@code undoRepository}。
     *
     * <p>{@code undoRepository} 传 null 等价于"undo 跟随 repository"的既有行为，因此历史构造器语义不变。
     */
    public AtTransactionManager(
            AtRepository r,
            UndoExecutor u,
            GlobalLockManager locks,
            int maxRetries,
            String owner,
            long rollbackLeaseMillis,
            UndoRepository undoRepository) {
        repository = r;
        undoExecutor = u;
        lockManager = locks;
        this.maxRetries = maxRetries;
        this.owner = owner;
        this.rollbackLeaseMillis = rollbackLeaseMillis;
        this.undoRepository = undoRepository;
    }

    /**
     * undo 到底写哪里：显式注入优先，否则若 {@code AtRepository} 自己也实现了 {@link UndoRepository} （{@code
     * JdbcAtRepository} 就是这种），则直接用它——这样单存储模式不需要额外装配。
     */
    private UndoRepository undoStore() {
        if (undoRepository != null) return undoRepository;
        if (repository instanceof UndoRepository) return (UndoRepository) repository;
        return null;
    }

    /**
     * undo + 重试簿记的持久化出口。
     *
     * <p>单一存储时原样 {@code repository.save}（一个本地事务里写完 undo 与簿记）；混合存储时 undo 写业务库、簿记写
     * Redis——两者不在同一个事务里，但簿记字段（重试次数/下次重试时间）本来就允许最终一致， 真正要求与业务原子的是 undo 本身。
     */
    private void persist(AtTransaction tx) {
        UndoRepository store = undoStore();
        if (store == null || store == repository) {
            repository.save(tx);
            return;
        }
        store.replaceAll(tx.getXid(), tx.getUndoRecords());
        repository.updateRecovery(tx.getXid(), tx.getRetries(), tx.getNextRetryAt());
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

    /**
     * 加入调用方已创建的全局事务（跨服务传播场景）。要求两个服务共享同一个 Repository。
     *
     * <p>防悬挂：只接受仍处于 ACTIVE 的事务。事务一旦进入 COMMITTING/ROLLING_BACK/终态，说明本次请求
     * 来得太晚（常见于上游已超时回滚后请求才到达），必须直接拒绝而不是继续执行业务 SQL。
     */
    public void join(String xid) {
        AtTransaction tx = required(xid);
        if (!tx.getStatus().isJoinable())
            throw new AtException(
                    "Cannot join AT transaction " + xid + " in status " + tx.getStatus());
        AtContext.bind(xid);
    }

    /** 追加一条 undo 记录（非连接绑定路径，如 File 存储）。 */
    public void append(UndoRecord record) {
        AtTransaction tx = required(record.getXid());
        ensureModifiable(tx);
        tx.addUndo(record);
        persist(tx);
    }

    /**
     * 通过业务连接持久化 undo（连接绑定路径，与业务 DML 同连接、同事务提交）。
     *
     * <p>这里同样执行 {@link #ensureModifiable}：长事务被恢复调度超时回滚之后，业务线程如果还在继续 执行 DML，必须在此刻失败，而不是把 undo
     * 追加到一个已终结的事务上。
     */
    public void append(Connection connection, UndoRecord record) {
        AtTransaction tx = required(record.getXid());
        ensureModifiable(tx);
        UndoRepository store = undoStore();
        if (store instanceof ConnectionBoundUndoRepository) {
            ((ConnectionBoundUndoRepository) store).append(connection, record);
            return;
        }
        tx.addUndo(record);
        persist(tx);
    }

    /** 只有 ACTIVE 的事务还能继续产生 undo。 */
    private void ensureModifiable(AtTransaction tx) {
        if (!tx.getStatus().isJoinable())
            throw new AtException(
                    "AT transaction "
                            + tx.getXid()
                            + " is "
                            + tx.getStatus()
                            + "; refusing further DML to avoid hanging data");
    }

    public void updateUndo(String xid, String undoId, RowImage after) {
        AtTransaction tx = required(xid);
        for (UndoRecord r : tx.getUndoRecords())
            if (r.getId().equals(undoId)) {
                r.setAfterImage(after);
                persist(tx);
                return;
            }
        throw new AtException("Undo record not found: " + undoId);
    }

    public void updateUndo(Connection connection, String xid, String undoId, RowImage after) {
        UndoRepository store = undoStore();
        if (store instanceof ConnectionBoundUndoRepository) {
            ((ConnectionBoundUndoRepository) store).updateUndo(connection, xid, undoId, after);
            return;
        }
        updateUndo(xid, undoId, after);
    }

    public void discardUndo(Connection connection, String xid, String undoId) {
        UndoRepository store = undoStore();
        if (store instanceof ConnectionBoundUndoRepository) {
            ((ConnectionBoundUndoRepository) store).removeUndo(connection, xid, undoId);
            return;
        }
        AtTransaction tx = required(xid);
        tx.removeUndo(undoId);
        persist(tx);
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
    /**
     * 回滚：进入 ROLLING_BACK 后，把 undo 记录<b>逆序</b>逐条执行。 任一条 undo 抛出 {@link DirtyWriteException} 则整笔转
     * DIRTY_WRITE（脏写，人工介入）； 其他异常转 ROLLBACK_FAILED 并按退避时间写入下一次重试点。
     *
     * <p><b>补偿执行权必须独占</b>。注意状态 ACTIVE→ROLLING_BACK 的 CAS 并不等价于"拿到补偿执行权"： 若一个工作者读到状态已经是
     * ROLLING_BACK，CAS 会被短路跳过，直接闯进补偿循环；两个执行者并发跑同一批 undo 时，后到的那个会在脏写校验里看到"当前行不等于 after
     * image"（其实是被自己的同伴改的），于是把一笔 明明已正确回滚的事务误判成 DIRTY_WRITE 交给人工。这在"长回滚期间租约过期、另一实例接管"的真实场景里必现。
     *
     * <p>因此这里用两道互斥：本进程内用 {@link #rollbackInFlight} 拦住并发线程，跨实例用恢复租约拦住其他
     * 实例。租约过期时允许后来者接管，保证持有者崩溃后仍有人继续推进。
     */
    public void rollback(String xid, boolean releaseLockOnConverge) {
        AtTransaction tx = required(xid);
        if (tx.getStatus() == AtStatus.ROLLED_BACK) {
            // Rollback is idempotent, but lock cleanup must be retried. The previous process may
            // have persisted the terminal state and failed before deleting its locks.
            if (releaseLockOnConverge) release(xid);
            return;
        }
        if (!enterRollback(tx))
            throw new AtException(
                    "Conflict rolling back " + xid + ": another worker already owns this rollback");
        try {
            if (tx.getStatus() != AtStatus.ROLLING_BACK && !transition(tx, AtStatus.ROLLING_BACK))
                throw new AtException("Conflict rolling back " + xid + " from " + tx.getStatus());
            compensate(tx, xid, releaseLockOnConverge);
        } finally {
            exitRollback(xid);
        }
    }

    /** 逆序执行全部 undo 并收敛终态；失败时按异常类型转 DIRTY_WRITE / ROLLBACK_FAILED。 */
    private void compensate(AtTransaction tx, String xid, boolean releaseLockOnConverge) {
        List<UndoRecord> records = new ArrayList<UndoRecord>(tx.getUndoRecords());
        Collections.reverse(records);
        try {
            for (UndoRecord r : records) {
                if (!r.isRolledBack()) {
                    undoExecutor.rollback(r);
                    r.markRolledBack();
                    persist(tx);
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

    /**
     * 取得本次回滚的独占执行权。
     *
     * <ol>
     *   <li>本进程已有线程在回滚 → 拒绝（拦住单 JVM 内的并发调用）；
     *   <li>租约归自己 → 放行（恢复调度器已抢到租约后再调用本方法的常规路径）；
     *   <li>租约归他人且未过期 → 拒绝；
     *   <li>无主或租约已过期 → 抢占后放行（覆盖持有者崩溃后的接管）。
     * </ol>
     */
    private boolean enterRollback(AtTransaction tx) {
        String xid = tx.getXid();
        if (!rollbackInFlight.add(xid)) return false;
        long now = System.currentTimeMillis();
        String current = tx.getOwner();
        if (owner != null && owner.equals(current)) {
            // 调度器通常以同一 owner 先抢占租约，再驱动本方法；此时不应重复释放它的租约。
            selfClaimed.put(xid, Boolean.FALSE);
            return true;
        }
        if (current != null && !current.isEmpty() && tx.getLeaseUntil() >= now) {
            rollbackInFlight.remove(xid);
            return false;
        }
        if (owner != null && !repository.claimLease(xid, owner, now + rollbackLeaseMillis, now)) {
            rollbackInFlight.remove(xid);
            return false;
        }
        selfClaimed.put(xid, Boolean.TRUE);
        return true;
    }

    private void exitRollback(String xid) {
        try {
            Boolean leased = selfClaimed.remove(xid);
            if (leased != null && leased.booleanValue() && owner != null) {
                try {
                    repository.releaseLease(xid, owner);
                } catch (RuntimeException ignored) {
                    /* lease bookkeeping must not mask the rollback result */
                }
            }
        } finally {
            rollbackInFlight.remove(xid);
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

    /**
     * 收敛卡在 COMMITTING 的事务：本地事务已经提交，但 {@code ACTIVE→COMMITTING→COMMITTED} 的第二步没走完 （进程崩溃、DB
     * 抖动、版本号被并发抢走）。
     *
     * <p>这类事务不能回滚（数据已落库），只能幂等往前推进到 COMMITTED 并释放全局锁。反复推进失败达到重试 上限后转
     * MANUAL_INTERVENTION，否则该事务连同它持有的全局锁会永久泄漏，且运维没有任何告警。
     */
    public void finishCommit(String xid) {
        AtTransaction tx = required(xid);
        AtStatus status = tx.getStatus();
        if (status == AtStatus.COMMITTED) {
            // 前一轮可能刚写完终态就崩了，锁清理必须能重试。
            release(xid);
            return;
        }
        if (status != AtStatus.COMMITTING) return; // 已被其他路径收敛
        if (tx.getRetries() >= maxRetries) {
            transition(tx, AtStatus.MANUAL_INTERVENTION);
            return;
        }
        if (transition(tx, AtStatus.COMMITTED)) {
            release(xid);
            return;
        }
        tx.incrementRetries();
        repository.updateRecovery(
                tx.getXid(),
                tx.getRetries(),
                System.currentTimeMillis() + backoff(tx.getRetries()));
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
        AtTransaction tx =
                repository
                        .find(xid)
                        .orElseThrow(() -> new AtException("Transaction not found: " + xid));
        // 混合存储：全局状态在 Redis、undo 在业务库。只有显式注入时才合并，避免单存储模式重复加载。
        if (undoRepository != null && tx.getUndoRecords().isEmpty())
            for (UndoRecord u : undoRepository.load(xid)) tx.addUndo(u);
        return tx;
    }

    private void release(String xid) {
        if (lockManager != null) lockManager.releaseByXid(xid);
    }

    /** 指数退避：1s、2s、4s… 上限 5 分钟（重试次数超过 8 次后不再翻倍）。 */
    private long backoff(int retries) {
        return Math.min(300000L, 1000L << Math.min(retries, 8));
    }
}

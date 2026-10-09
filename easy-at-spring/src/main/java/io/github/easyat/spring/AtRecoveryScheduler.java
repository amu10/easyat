package io.github.easyat.spring;

import io.github.easyat.core.*;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.concurrent.*;

/**
 * Local recovery worker. Each instance claims a recovery lease before processing a transaction, so
 * in a cluster only one instance drives any given rollback/commit to completion. If the owning
 * instance crashes, other instances can take over once the lease expires. After the local recovery
 * it also drives any registered branches so cross-service rollback is coordinated.
 */
public final class AtRecoveryScheduler implements AutoCloseable {
    /** 事务/分支存储，用于拉取可恢复事务、抢租约、查状态。 */
    private final AtRepository repository;

    /** 全局事务管理器，负责本地提交/回滚与恢复推进。 */
    private final AtTransactionManager manager;

    /** 分支协调器，本地恢复完成后由其驱动跨服务分支回滚（可为 null，表示只做本地恢复）。 */
    private final BranchCoordinator coordinator;

    /** 指标采集器（可为 null）。 */
    private final EasyAtMetrics metrics;

    /** 本实例的归属标识；抢到的恢复租约会记在该 owner 名下。 */
    private final String owner;

    /** 恢复扫描间隔（毫秒）。 */
    private final long intervalMillis;

    /** 单次拉取的可恢复事务批大小。 */
    private final int batchSize;

    /** 恢复租约时长（毫秒）。持有租约期间其它实例不会重复处理同一事务。 */
    private final long leaseMillis;

    /** 单线程调度器（守护线程），保证恢复串行、互不干扰。 */
    private final ScheduledExecutorService executor;

    /** 当前已注册的调度任务句柄。 */
    private volatile ScheduledFuture<?> task;

    public AtRecoveryScheduler(
            AtRepository repository,
            AtTransactionManager manager,
            String owner,
            long intervalMillis,
            int batchSize,
            long leaseMillis) {
        this(repository, manager, null, null, owner, intervalMillis, batchSize, leaseMillis);
    }

    public AtRecoveryScheduler(
            AtRepository repository,
            AtTransactionManager manager,
            BranchCoordinator coordinator,
            EasyAtMetrics metrics,
            String owner,
            long intervalMillis,
            int batchSize,
            long leaseMillis) {
        this.repository = repository;
        this.manager = manager;
        this.coordinator = coordinator;
        this.metrics = metrics;
        this.owner = owner;
        this.intervalMillis = intervalMillis;
        this.batchSize = batchSize;
        this.leaseMillis = leaseMillis;
        this.executor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "easy-at-recovery");
                            t.setDaemon(true);
                            return t;
                        });
    }

    /**
     * 启动定时恢复。仅在尚未启动（{@code task == null}）时注册固定延迟任务，重复调用幂等。
     */
    public synchronized void start() {
        if (task == null)
            task =
                    executor.scheduleWithFixedDelay(
                            this::recoverSafely,
                            intervalMillis,
                            intervalMillis,
                            TimeUnit.MILLISECONDS);
    }

    /**
     * 立即触发一次恢复（不经过定时调度），常用于运维手动触发或测试。
     */
    public void recoverNow() {
        recoverSafely();
    }

    /**
     * 单次恢复扫描：拉取可恢复事务 → 抢租约 → 推进本地状态 → 驱动跨服务分支，
     * 全程吞掉存储临时不可用的异常，保证下一次扫描仍能继续。
     */
    private void recoverSafely() {
        try {
            long now = System.currentTimeMillis();
            // 拉取一批「待恢复」事务：超时的 ACTIVE、卡在 ROLLING_BACK 的、以及到点重试的 ROLLBACK_FAILED
            List<AtTransaction> transactions = repository.recoverable(now, batchSize);
            if (metrics != null) metrics.setRecoveryQueueDepth(transactions.size());
            Timer.Sample sample = metrics != null ? metrics.startRecovery() : null;
            for (AtTransaction tx : transactions) {
                // 先抢恢复租约，抢不到说明别的实例在处理，直接跳过——这是多实例不重复恢复的关键
                if (!repository.claimLease(tx.getXid(), owner, now + leaseMillis, now))
                    continue; // owned by another instance
                try {
                    AtStatus current = currentStatus(tx.getXid());
                    if (current == AtStatus.COMMITTING)
                        // 本地已提交，不能回滚，只能幂等推进到 COMMITTED 并释放锁
                        manager.finishCommit(tx.getXid());
                    else manager.recover(tx);
                } catch (RuntimeException ignored) {
                    /* persisted retry state is the source of truth */
                } finally {
                    try {
                        AtStatus after = currentStatus(tx.getXid());
                        // MANUAL_INTERVENTION / DIRTY_WRITE 是留给人工的终态。这里若再驱动分支，
                        // 会把状态拉回 ROLLING_BACK，使重试上限失效、人工介入标记被抹掉。
                        if (coordinator != null
                                && after != null
                                && after != AtStatus.MANUAL_INTERVENTION
                                && after != AtStatus.DIRTY_WRITE) coordinator.rollback(tx.getXid());
                    } catch (RuntimeException ignored) {
                        /* best effort */
                    }
                    repository.releaseLease(tx.getXid(), owner);
                }
                if (metrics != null) {
                    AtStatus s = currentStatus(tx.getXid());
                    if (s == AtStatus.MANUAL_INTERVENTION || s == AtStatus.DIRTY_WRITE)
                        metrics.recordManualIntervention();
                }
            }
            if (metrics != null && sample != null) metrics.stopRecovery(sample);
        } catch (RuntimeException ignored) {
            /* database/filesystem may be temporarily unavailable */
        }
    }

    /**
     * 读取某全局事务的当前状态；查询异常时返回 {@code null} 而非抛出，避免中断恢复循环。
     *
     * @param xid 全局事务 id
     * @return 当前状态，查询失败或不存在时返回 {@code null}
     */
    private AtStatus currentStatus(String xid) {
        try {
            return repository.find(xid).map(AtTransaction::getStatus).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 关闭恢复调度器：取消任务并立即关闭线程池。实现 {@link AutoCloseable}。
     */
    @Override
    public synchronized void close() {
        if (task != null) task.cancel(false);
        executor.shutdownNow();
    }
}

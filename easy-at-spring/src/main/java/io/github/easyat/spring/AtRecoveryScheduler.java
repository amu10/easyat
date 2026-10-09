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
    private final AtRepository repository;
    private final AtTransactionManager manager;
    private final BranchCoordinator coordinator;
    private final EasyAtMetrics metrics;
    private final String owner;
    private final long intervalMillis;
    private final int batchSize;
    private final long leaseMillis;
    private final ScheduledExecutorService executor;
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

    public synchronized void start() {
        if (task == null)
            task =
                    executor.scheduleWithFixedDelay(
                            this::recoverSafely,
                            intervalMillis,
                            intervalMillis,
                            TimeUnit.MILLISECONDS);
    }

    public void recoverNow() {
        recoverSafely();
    }

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

    private AtStatus currentStatus(String xid) {
        try {
            return repository.find(xid).map(AtTransaction::getStatus).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public synchronized void close() {
        if (task != null) task.cancel(false);
        executor.shutdownNow();
    }
}

package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * Drives reliable branch delivery. Scans branches that still need a commit/rollback callback (due
 * for retry), and re-invokes the {@link BranchCoordinator}. On failure it backs off with an
 * exponential schedule persisted to the branch store so delivery resumes after a restart.
 */
public final class BranchRetryScheduler implements AutoCloseable {
    /** 默认最大重试次数（重试耗尽后收敛到人工介入）。 */
    private static final int DEFAULT_MAX_RETRIES = 20;

    /** 分支存储，用于拉取到点待重试的分支。 */
    private final BranchRepository branches;

    /** 分支协调器，重新执行分支的 commit/rollback 投递。 */
    private final BranchCoordinator coordinator;

    /** 重试扫描间隔（毫秒）。 */
    private final long intervalMillis;

    /** 单次拉取待重试分支的批大小。 */
    private final int batchSize;

    /** 最大重试次数（<=0 时回落到 {@link #DEFAULT_MAX_RETRIES}）。 */
    private final int maxRetries;

    /** 指标采集器（可为 null）。 */
    private final EasyAtMetrics metrics;

    /** 单线程调度器（守护线程），保证重试串行。 */
    private final ScheduledExecutorService executor;

    /** 当前已注册的调度任务句柄。 */
    private volatile ScheduledFuture<?> task;

    public BranchRetryScheduler(
            BranchRepository branches,
            BranchCoordinator coordinator,
            long intervalMillis,
            int batchSize) {
        this(branches, coordinator, intervalMillis, batchSize, DEFAULT_MAX_RETRIES, null);
    }

    public BranchRetryScheduler(
            BranchRepository branches,
            BranchCoordinator coordinator,
            long intervalMillis,
            int batchSize,
            int maxRetries,
            EasyAtMetrics metrics) {
        this.branches = branches;
        this.coordinator = coordinator;
        this.intervalMillis = intervalMillis;
        this.batchSize = batchSize;
        this.maxRetries = maxRetries > 0 ? maxRetries : DEFAULT_MAX_RETRIES;
        this.metrics = metrics;
        this.executor =
                Executors.newSingleThreadScheduledExecutor(
                        r -> {
                            Thread t = new Thread(r, "easy-at-branch-retry");
                            t.setDaemon(true);
                            return t;
                        });
    }

    /** 启动定时重试。仅在尚未启动（{@code task == null}）时注册固定延迟任务，重复调用幂等。 */
    public synchronized void start() {
        if (task == null)
            task =
                    executor.scheduleWithFixedDelay(
                            this::retryPending,
                            intervalMillis,
                            intervalMillis,
                            TimeUnit.MILLISECONDS);
    }

    /** 立即触发一次重试扫描（不经过定时调度）。 */
    public void triggerNow() {
        retryPending();
    }

    /**
     * 单次重试扫描：拉取已到点且未终结的分支；重试耗尽则收敛到 {@code MANUAL_INTERVENTION}， 否则重新驱动回滚并刷新退避后的下一次重试点。存储临时不可用时吞掉异常。
     */
    private void retryPending() {
        try {
            long now = System.currentTimeMillis();
            List<AtBranch> pending = branches.pendingActions(now, batchSize);
            for (AtBranch b : pending) {
                if (b.getStatus().isTerminal()) continue;
                int attempts = b.getRetries();
                if (attempts >= maxRetries) {
                    // 投递重试耗尽：必须收敛到人工介入并告警。否则这条分支会按退避上限无限重试，
                    // 既不成功也不失败，运维完全无感知（此前这就是隐性数据不一致的来源）。
                    branches.transition(
                            b.getBranchId(), b.getStatus(), BranchStatus.MANUAL_INTERVENTION);
                    if (metrics != null) metrics.recordManualIntervention();
                    continue;
                }
                BranchStatus result =
                        coordinator.rollbackBranch(
                                b.getBranchId(),
                                b.getXid(),
                                b.getResourceId()); // 带上 xid/resourceId，让对端在分支缺失时
                // 也能完成空回滚；commit 分支由发起方驱动，这里的重试只负责回滚收敛
                if (result == BranchStatus.ROLLBACK_FAILED || result == BranchStatus.ROLLING_BACK) {
                    int retries = attempts + 1;
                    branches.updateRecovery(
                            b.getBranchId(),
                            retries,
                            System.currentTimeMillis() + backoff(retries));
                }
            }
        } catch (RuntimeException ignored) {
            /* storage may be temporarily unavailable */
        }
    }

    /**
     * 计算下一次重试的退避间隔：1s、2s、4s… 指数增长，封顶 5 分钟。
     *
     * @param retries 已重试次数
     * @return 退避毫秒数（最大 300000）
     */
    private long backoff(int retries) {
        return Math.min(300000L, 1000L << Math.min(retries, 8));
    }

    /** 关闭重试调度器：取消任务并立即关闭线程池。实现 {@link AutoCloseable}。 */
    @Override
    public synchronized void close() {
        if (task != null) task.cancel(false);
        executor.shutdownNow();
    }
}

package io.github.easyat.spring;

import io.github.easyat.jdbc.JdbcAtCleaner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Periodically deletes a bounded batch of terminal JDBC history and expired lock rows. */
public final class AtCleanupScheduler implements AutoCloseable {
    /** 真正执行物理删除的清理器（删除 JDBC 历史表 / 过期锁行）。 */
    private final JdbcAtCleaner cleaner;

    /** 清理循环的固定间隔（毫秒）。定时线程按此周期触发一次清理。 */
    private final long intervalMillis;

    /** 已提交事务的保留期（毫秒）。超过 now - 该值的已提交行会被删除。 */
    private final long committedRetentionMillis;

    /** 已回滚事务的保留期（毫秒）。超过 now - 该值的已回滚行会被删除。 */
    private final long rolledBackRetentionMillis;

    /** 过期全局锁的保留期（毫秒）。超过 now - 该值的锁行被视为过期并清理。 */
    private final long expiredLockRetentionMillis;

    /** 单次清理最多删除的行数，限制单批删除对数据库的冲击。 */
    private final int batchSize;

    /** 单线程调度器（守护线程），保证清理任务串行执行、互不并发。 */
    private final ScheduledExecutorService executor;

    /** 当前已注册的调度任务句柄；用 volatile 保证 start/close 间的可见性。 */
    private volatile ScheduledFuture<?> task;

    /**
     * 构造清理调度器。
     *
     * @param cleaner 执行物理删除的 {@link JdbcAtCleaner}
     * @param intervalMillis 调度间隔（毫秒）
     * @param committedRetentionMillis 已提交行保留期（毫秒）
     * @param rolledBackRetentionMillis 已回滚行保留期（毫秒）
     * @param expiredLockRetentionMillis 过期锁保留期（毫秒）
     * @param batchSize 单批删除上限
     */
    public AtCleanupScheduler(
            JdbcAtCleaner cleaner,
            long intervalMillis,
            long committedRetentionMillis,
            long rolledBackRetentionMillis,
            long expiredLockRetentionMillis,
            int batchSize) {
        this.cleaner = cleaner;
        this.intervalMillis = intervalMillis;
        this.committedRetentionMillis = committedRetentionMillis;
        this.rolledBackRetentionMillis = rolledBackRetentionMillis;
        this.expiredLockRetentionMillis = expiredLockRetentionMillis;
        this.batchSize = batchSize;
        this.executor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "easy-at-cleanup");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    /** 启动定时清理。仅在尚未启动（{@code task == null}）时注册一个固定延迟任务， 保证重复调用是幂等的。 */
    public synchronized void start() {
        if (task == null)
            task =
                    executor.scheduleWithFixedDelay(
                            this::cleanupSafely,
                            intervalMillis,
                            intervalMillis,
                            TimeUnit.MILLISECONDS);
    }

    /**
     * 立即执行一次清理：以「当前时间减去各保留期」作为截止点，按 {@code batchSize} 删除过期数据。
     *
     * @return 本次清理的结果（删除条数等），由底层 {@link JdbcAtCleaner} 返回
     */
    public JdbcAtCleaner.CleanupResult cleanupNow() {
        long now = System.currentTimeMillis();
        return cleaner.cleanup(
                now - committedRetentionMillis,
                now - rolledBackRetentionMillis,
                now - expiredLockRetentionMillis,
                batchSize);
    }

    private void cleanupSafely() {
        try {
            cleanupNow();
        } catch (RuntimeException ignored) {
            // A later run retries the same terminal rows after transient database failures.
        }
    }

    /** 关闭调度器：取消已注册任务并立即关闭线程池（{@code shutdownNow}）。 实现 {@link AutoCloseable}，便于在 Spring 销毁时释放资源。 */
    @Override
    public synchronized void close() {
        if (task != null) task.cancel(false);
        executor.shutdownNow();
    }
}

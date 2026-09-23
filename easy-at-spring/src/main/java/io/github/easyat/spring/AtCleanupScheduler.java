package io.github.easyat.spring;

import io.github.easyat.jdbc.JdbcAtCleaner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Periodically deletes a bounded batch of terminal JDBC history and expired lock rows. */
public final class AtCleanupScheduler implements AutoCloseable {
    private final JdbcAtCleaner cleaner;
    private final long intervalMillis;
    private final long committedRetentionMillis;
    private final long rolledBackRetentionMillis;
    private final long expiredLockRetentionMillis;
    private final int batchSize;
    private final ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> task;

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

    public synchronized void start() {
        if (task == null)
            task =
                    executor.scheduleWithFixedDelay(
                            this::cleanupSafely,
                            intervalMillis,
                            intervalMillis,
                            TimeUnit.MILLISECONDS);
    }

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

    @Override
    public synchronized void close() {
        if (task != null) task.cancel(false);
        executor.shutdownNow();
    }
}

package io.github.easyat.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 与存储无关的清理调度器。
 *
 * <p>既有的 {@code AtCleanupScheduler} 把清理器硬绑成 JDBC，导致 Redis / 混合存储根本没有回收手段。 这里抽出一层：清理动作抽象成 {@link
 * Task}，被删掉的 xid 交给 {@code Consumer} 做级联处理—— 混合存储模式下正好用它去删业务库里的 {@code easy_at_undo_log}。
 */
public final class CleanupScheduler implements AutoCloseable {

    /** 一次清理动作：返回本批次被删除的事务 xid。 */
    public interface Task {
        List<String> run();
    }

    private final Task task;
    private final Consumer<List<String>> cascade;
    private final long intervalMillis;
    private final ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> scheduled;

    public CleanupScheduler(Task task, Consumer<List<String>> cascade, long intervalMillis) {
        this.task = task;
        this.cascade = cascade;
        this.intervalMillis = intervalMillis;
        this.executor =
                Executors.newSingleThreadScheduledExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "easy-at-cleanup");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    public synchronized void start() {
        if (scheduled == null)
            scheduled =
                    executor.scheduleWithFixedDelay(
                            this::runSafely, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    /** 立即执行一次清理，返回本次删除的 xid。异常不外抛——清理失败不该影响业务线程。 */
    public List<String> runNow() {
        List<String> removed;
        try {
            removed = task.run();
        } catch (RuntimeException e) {
            return Collections.emptyList();
        }
        if (!removed.isEmpty() && cascade != null)
            try {
                cascade.accept(removed);
            } catch (RuntimeException ignored) {
                // 级联清理失败不影响主链路，下一轮会重试
            }
        return removed;
    }

    private void runSafely() {
        try {
            runNow();
        } catch (RuntimeException ignored) {
            // 后台任务永不因异常退出
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    public static List<String> empty() {
        return new ArrayList<String>();
    }
}

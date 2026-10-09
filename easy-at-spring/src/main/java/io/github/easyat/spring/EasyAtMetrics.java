package io.github.easyat.spring;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer metrics for the AT runtime. Safe to use with a no-op registry when none is provided.
 */
public final class EasyAtMetrics {
    /** Micrometer 注册表；未提供时退化为 SimpleMeterRegistry（no-op 度量）。 */
    private final MeterRegistry registry;

    /** 计数类指标：提交数、回滚数、回滚失败数、锁冲突数、人工介入数。 */
    private final Counter commits, rollbacks, rollbackFailures, lockConflicts, manualInterventions;

    /** 恢复耗时计时器。 */
    private final Timer recovery;

    /** 恢复队列深度（Gauge 观测的瞬时值）。 */
    private final AtomicInteger recoveryQueueDepth = new AtomicInteger(0);

    /** 当前活跃事务数（Gauge 观测的瞬时值）。 */
    private final AtomicInteger activeTransactions = new AtomicInteger(0);

    /**
     * 构造指标采集器。
     *
     * @param registry Micrometer 注册表；为 null 时使用 {@link SimpleMeterRegistry}（空操作）
     */
    public EasyAtMetrics(MeterRegistry registry) {
        this.registry = registry == null ? new SimpleMeterRegistry() : registry;
        this.commits = Counter.builder("easyat.transactions.commits").register(this.registry);
        this.rollbacks = Counter.builder("easyat.transactions.rollbacks").register(this.registry);
        this.rollbackFailures =
                Counter.builder("easyat.transactions.rollback.failures").register(this.registry);
        this.lockConflicts = Counter.builder("easyat.locks.conflicts").register(this.registry);
        this.manualInterventions =
                Counter.builder("easyat.transactions.manual.interventions").register(this.registry);
        this.recovery = Timer.builder("easyat.recovery.duration").register(this.registry);
        Gauge.builder("easyat.recovery.queue.depth", recoveryQueueDepth, AtomicInteger::get)
                .register(this.registry);
        Gauge.builder("easyat.transactions.active", activeTransactions, AtomicInteger::get)
                .register(this.registry);
    }

    public MeterRegistry registry() {
        return registry;
    }

    public void recordCommit() {
        commits.increment();
    }

    public void recordRollback() {
        rollbacks.increment();
    }

    public void recordRollbackFailure() {
        rollbackFailures.increment();
    }

    public void recordLockConflict() {
        lockConflicts.increment();
    }

    public void recordManualIntervention() {
        manualInterventions.increment();
    }

    public void setRecoveryQueueDepth(int depth) {
        recoveryQueueDepth.set(depth);
    }

    public void setActiveTransactions(int n) {
        activeTransactions.set(n);
    }

    public Timer.Sample startRecovery() {
        return Timer.start(registry);
    }

    public void stopRecovery(Timer.Sample sample) {
        if (sample != null) sample.stop(recovery);
    }
}

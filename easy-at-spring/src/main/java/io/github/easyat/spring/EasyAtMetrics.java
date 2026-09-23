package io.github.easyat.spring;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer metrics for the AT runtime. Safe to use with a no-op registry when none is provided.
 */
public final class EasyAtMetrics {
    private final MeterRegistry registry;
    private final Counter commits, rollbacks, rollbackFailures, lockConflicts, manualInterventions;
    private final Timer recovery;
    private final AtomicInteger recoveryQueueDepth = new AtomicInteger(0);
    private final AtomicInteger activeTransactions = new AtomicInteger(0);

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

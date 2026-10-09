package io.github.easyat.spring;

import io.github.easyat.core.LocalTransactionBridge;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bridges easyAt to Spring's local transaction via {@link TransactionSynchronizationManager}. */
public final class SpringTransactionBridge implements LocalTransactionBridge {
    /**
     * 判断当前线程是否真的处于 Spring 本地事务中。
     *
     * @return 存在真实（已激活）本地事务时返回 true
     */
    @Override
    public boolean isActive() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    @Override
    public void afterCommit(final Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No active synchronization: fall back to immediate execution (e.g. auto-commit path).
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }
}

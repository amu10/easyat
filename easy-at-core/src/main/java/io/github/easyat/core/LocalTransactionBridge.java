package io.github.easyat.core;

/**
 * Abstraction over the surrounding local (Spring) transaction. The JDBC layer uses it to verify
 * that business DML and undo log share one local transaction, and to register post-commit work
 * (e.g. advancing branch status) without depending on Spring directly.
 */
public interface LocalTransactionBridge {
    /** 当前执行线程是否真的绑定了一个本地事务（Spring 的 @Transactional）。dev / 自动提交模式下返回 false。 */
    boolean isActive();

    /** 在环绕的本地事务成功提交后执行 {@code action}（如推进分支状态），不直接依赖 Spring 事务同步器。 */
    void afterCommit(Runnable action);

    /** A bridge that always reports "no transaction", used for dev / auto-commit mode. */
    LocalTransactionBridge NOOP =
            new LocalTransactionBridge() {
                public boolean isActive() {
                    return false;
                }

                public void afterCommit(Runnable action) {
                    action.run();
                }
            };
}

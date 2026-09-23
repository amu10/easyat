package io.github.easyat.core;

/**
 * Abstraction over the surrounding local (Spring) transaction. The JDBC layer uses it to verify
 * that business DML and undo log share one local transaction, and to register post-commit work
 * (e.g. advancing branch status) without depending on Spring directly.
 */
public interface LocalTransactionBridge {
    /** Whether a real local transaction is currently bound to the executing thread. */
    boolean isActive();

    /** Run {@code action} after the surrounding local transaction commits successfully. */
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

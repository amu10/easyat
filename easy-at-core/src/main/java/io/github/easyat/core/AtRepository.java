package io.github.easyat.core;

import java.util.*;

/**
 * Source of truth for global transactions and their undo records.
 *
 * <p>Status changes MUST use {@link #transition} (compare-and-set on status + version) so that
 * multiple recovery instances cannot drive the same transaction forward simultaneously. Plain
 * {@link #save} only persists undo records and retry bookkeeping and must never be used to change
 * the status of a JDBC-backed transaction.
 */
public interface AtRepository {
    void create(AtTransaction tx);

    Optional<AtTransaction> find(String xid);

    /** Persists undo records and retry bookkeeping for the whole transaction (File path). */
    void save(AtTransaction tx);

    /**
     * Candidate global transactions that need recovery (timeout / rollback-failed / still rolling
     * back).
     */
    List<AtTransaction> recoverable(long now, int limit);

    /** All global transactions currently in the given status (used by the management API). */
    List<AtTransaction> findByStatus(AtStatus status, int limit);

    /**
     * Compare-and-set the status. Updates {@code version = version + 1} on success.
     *
     * @return true if the row matched expected status + version and was updated.
     */
    boolean transition(String xid, AtStatus expected, long expectedVersion, AtStatus next);

    /**
     * Atomically take over recovery ownership. Succeeds when free, already owned by us, or lease
     * expired.
     */
    boolean claimLease(String xid, String owner, long leaseUntil, long now);

    /** Release the recovery lease only if we still own it. */
    void releaseLease(String xid, String owner);

    /** Persist retry bookkeeping during recovery. */
    void updateRecovery(String xid, int retries, long nextRetryAt);
}

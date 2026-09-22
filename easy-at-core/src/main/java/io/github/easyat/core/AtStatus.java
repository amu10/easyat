package io.github.easyat.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Global AT transaction status and its legal state transitions.
 *
 * <p>All status changes must go through a compare-and-set ({@code transition}) so that
 * multiple recovery instances cannot drive the same transaction forward at the same time.
 */
public enum AtStatus {
    ACTIVE,
    COMMITTING,
    COMMITTED,
    ROLLING_BACK,
    ROLLED_BACK,
    ROLLBACK_FAILED,
    /** A branch found the current row no longer equals the after-image; needs human intervention. */
    DIRTY_WRITE,
    /** Exhausted automatic retries; needs human intervention. */
    MANUAL_INTERVENTION;

    private static final Map<AtStatus, Set<AtStatus>> TRANSITIONS = new HashMap<AtStatus, Set<AtStatus>>();
    static {
        TRANSITIONS.put(ACTIVE, EnumSet.of(COMMITTING, ROLLING_BACK, DIRTY_WRITE, MANUAL_INTERVENTION));
        TRANSITIONS.put(COMMITTING, EnumSet.of(COMMITTED));
        TRANSITIONS.put(ROLLING_BACK, EnumSet.of(ROLLED_BACK, ROLLBACK_FAILED, DIRTY_WRITE));
        TRANSITIONS.put(ROLLBACK_FAILED, EnumSet.of(ROLLING_BACK, MANUAL_INTERVENTION));
        TRANSITIONS.put(COMMITTED, EnumSet.noneOf(AtStatus.class));
        TRANSITIONS.put(ROLLED_BACK, EnumSet.noneOf(AtStatus.class));
        TRANSITIONS.put(DIRTY_WRITE, EnumSet.of(MANUAL_INTERVENTION));
        TRANSITIONS.put(MANUAL_INTERVENTION, EnumSet.of(ROLLING_BACK, DIRTY_WRITE));
    }

    /** Terminal states require no further automated processing. */
    public boolean isTerminal() {
        return this == COMMITTED || this == ROLLED_BACK || this == MANUAL_INTERVENTION;
    }

    /** True if moving from this status to {@code next} is a legal transition. */
    public boolean canTransitionTo(AtStatus next) {
        Set<AtStatus> allowed = TRANSITIONS.get(this);
        return allowed != null && allowed.contains(next);
    }

    /** Throws if the transition is illegal; used to fail fast on corrupted state. */
    public static void validate(AtStatus from, AtStatus to) {
        if (from == to) return;
        if (!from.canTransitionTo(to)) {
            throw new AtException("Illegal AT status transition: " + from + " -> " + to);
        }
    }

    public static Set<AtStatus> legalTargets(AtStatus from) {
        Set<AtStatus> allowed = TRANSITIONS.get(from);
        return allowed == null ? Collections.<AtStatus>emptySet() : Collections.unmodifiableSet(allowed);
    }
}

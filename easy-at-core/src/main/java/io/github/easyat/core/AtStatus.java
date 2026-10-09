package io.github.easyat.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Global AT transaction status and its legal state transitions.
 *
 * <p>All status changes must go through a compare-and-set ({@code transition}) so that multiple
 * recovery instances cannot drive the same transaction forward at the same time.
 */
public enum AtStatus {
    ACTIVE,
    COMMITTING,
    COMMITTED,
    ROLLING_BACK,
    ROLLED_BACK,
    ROLLBACK_FAILED,
    /**
     * A branch found the current row no longer equals the after-image; needs human intervention.
     */
    DIRTY_WRITE,
    /** Exhausted automatic retries; needs human intervention. */
    MANUAL_INTERVENTION;

    private static final Map<AtStatus, Set<AtStatus>> TRANSITIONS =
            new HashMap<AtStatus, Set<AtStatus>>();

    static {
        TRANSITIONS.put(
                ACTIVE, EnumSet.of(COMMITTING, ROLLING_BACK, DIRTY_WRITE, MANUAL_INTERVENTION));
        // COMMITTING 意味着本地事务已提交，无法再回滚，只能往前推进到 COMMITTED；
        // 推进彻底失败时必须能进 MANUAL_INTERVENTION，否则该事务连同其全局锁永久泄漏。
        TRANSITIONS.put(COMMITTING, EnumSet.of(COMMITTED, MANUAL_INTERVENTION));
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

    /**
     * 只有 ACTIVE 的全局事务才接受新的参与者/新的 undo 写入。
     *
     * <p>已被超时回滚或已提交的事务如果还接受迟到请求，那些请求产生的 undo 会挂在已终结的 XID 下， 既不会被回滚也不会被告警——就是所谓的"悬挂数据"。
     */
    public boolean isJoinable() {
        return this == ACTIVE;
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
        return allowed == null
                ? Collections.<AtStatus>emptySet()
                : Collections.unmodifiableSet(allowed);
    }
}

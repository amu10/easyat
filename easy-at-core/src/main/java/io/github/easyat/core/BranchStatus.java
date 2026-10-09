package io.github.easyat.core;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** State of a single branch (one DataSource DML) inside a global transaction. */
public enum BranchStatus {
    REGISTERED,
    EXECUTED,
    COMMITTING,
    COMMITTED,
    ROLLING_BACK,
    ROLLED_BACK,
    ROLLBACK_FAILED,
    /** Exhausted delivery retries for this branch; needs human intervention. */
    MANUAL_INTERVENTION;
    private static final Map<BranchStatus, Set<BranchStatus>> TRANSITIONS =
            new HashMap<BranchStatus, Set<BranchStatus>>();

    static {
        // REGISTERED/EXECUTED 允许直达终态 ROLLED_BACK：空回滚与本地回滚都不经过 EXECUTED。
        TRANSITIONS.put(
                REGISTERED,
                EnumSet.of(
                        EXECUTED, ROLLING_BACK, ROLLED_BACK, ROLLBACK_FAILED, MANUAL_INTERVENTION));
        TRANSITIONS.put(
                EXECUTED,
                EnumSet.of(
                        COMMITTING,
                        ROLLING_BACK,
                        ROLLED_BACK,
                        ROLLBACK_FAILED,
                        MANUAL_INTERVENTION));
        TRANSITIONS.put(COMMITTING, EnumSet.of(COMMITTED, ROLLBACK_FAILED, MANUAL_INTERVENTION));
        TRANSITIONS.put(COMMITTED, EnumSet.noneOf(BranchStatus.class));
        TRANSITIONS.put(
                ROLLING_BACK, EnumSet.of(ROLLED_BACK, ROLLBACK_FAILED, MANUAL_INTERVENTION));
        TRANSITIONS.put(ROLLED_BACK, EnumSet.noneOf(BranchStatus.class));
        TRANSITIONS.put(ROLLBACK_FAILED, EnumSet.of(ROLLING_BACK, MANUAL_INTERVENTION));
        TRANSITIONS.put(MANUAL_INTERVENTION, EnumSet.of(ROLLING_BACK, ROLLBACK_FAILED));
    }

    public boolean canTransitionTo(BranchStatus next) {
        Set<BranchStatus> allowed = TRANSITIONS.get(this);
        return allowed != null && allowed.contains(next);
    }

    /** 终态：不再需要任何自动处理。 MANUAL_INTERVENTION 也算终态——它已经停止自动重试，只等人工处理。 */
    public boolean isTerminal() {
        return this == COMMITTED || this == ROLLED_BACK || this == MANUAL_INTERVENTION;
    }

    /** 该分支是否已经不可能再被本机业务执行 Hang-free 检查用：只要分支已被回滚（含占位空回滚）， 后续迟到的业务请求必须拒绝，否则会产生无人补偿的悬挂数据。 */
    public boolean isRolledBack() {
        return this == ROLLED_BACK;
    }
}

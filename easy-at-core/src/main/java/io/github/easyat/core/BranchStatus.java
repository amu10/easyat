package io.github.easyat.core;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** State of a single branch (one DataSource DML) inside a global transaction. */
public enum BranchStatus {
    REGISTERED, EXECUTED, COMMITTING, COMMITTED, ROLLING_BACK, ROLLED_BACK, ROLLBACK_FAILED;
    private static final Map<BranchStatus,Set<BranchStatus>> TRANSITIONS=new HashMap<BranchStatus,Set<BranchStatus>>();
    static{
        TRANSITIONS.put(REGISTERED,EnumSet.of(EXECUTED,ROLLING_BACK,ROLLBACK_FAILED));
        TRANSITIONS.put(EXECUTED,EnumSet.of(COMMITTING,ROLLING_BACK,ROLLBACK_FAILED));
        TRANSITIONS.put(COMMITTING,EnumSet.of(COMMITTED));
        TRANSITIONS.put(COMMITTED,EnumSet.noneOf(BranchStatus.class));
        TRANSITIONS.put(ROLLING_BACK,EnumSet.of(ROLLED_BACK,ROLLBACK_FAILED));
        TRANSITIONS.put(ROLLED_BACK,EnumSet.noneOf(BranchStatus.class));
        TRANSITIONS.put(ROLLBACK_FAILED,EnumSet.of(ROLLING_BACK));
    }
    public boolean canTransitionTo(BranchStatus next){Set<BranchStatus> allowed=TRANSITIONS.get(this);return allowed!=null&&allowed.contains(next);}
    public boolean isTerminal(){return this==COMMITTED||this==ROLLED_BACK;}
}

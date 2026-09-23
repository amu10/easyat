package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.util.List;
import java.util.concurrent.*;

/**
 * Drives reliable branch delivery. Scans branches that still need a commit/rollback callback
 * (due for retry), and re-invokes the {@link BranchCoordinator}. On failure it backs off with an
 * exponential schedule persisted to the branch store so delivery resumes after a restart.
 */
public final class BranchRetryScheduler implements AutoCloseable {
    private final BranchRepository branches; private final BranchCoordinator coordinator;
    private final long intervalMillis; private final int batchSize;
    private final ScheduledExecutorService executor;
    private volatile ScheduledFuture<?> task;
    public BranchRetryScheduler(BranchRepository branches,BranchCoordinator coordinator,long intervalMillis,int batchSize){
        this.branches=branches;this.coordinator=coordinator;this.intervalMillis=intervalMillis;this.batchSize=batchSize;
        this.executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"easy-at-branch-retry");t.setDaemon(true);return t;});
    }
    public synchronized void start(){if(task==null)task=executor.scheduleWithFixedDelay(this::retryPending,intervalMillis,intervalMillis,TimeUnit.MILLISECONDS);}
    public void triggerNow(){retryPending();}
    private void retryPending(){
        try{
            long now=System.currentTimeMillis();
            List<AtBranch> pending=branches.pendingActions(now,batchSize);
            for(AtBranch b:pending){
                if(b.getStatus().isTerminal())continue;
                BranchStatus result=coordinator.rollbackBranch(b.getBranchId()); // branch commit is driven by the initiating service; retries here are for rollback completion
                if(result==BranchStatus.ROLLBACK_FAILED){
                    int retries=b.getRetries()+1;
                    branches.updateRecovery(b.getBranchId(),retries,System.currentTimeMillis()+backoff(retries));
                }
            }
        }catch(RuntimeException ignored){/* storage may be temporarily unavailable */}
    }
    private long backoff(int retries){return Math.min(300000L,1000L<<Math.min(retries,8));}
    @Override public synchronized void close(){if(task!=null)task.cancel(false);executor.shutdownNow();}
}

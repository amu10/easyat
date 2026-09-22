package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.util.List;
import java.util.concurrent.*;

/** Local recovery worker. In a cluster, configure JDBC/Redis leases before enabling it on every node. */
public final class AtRecoveryScheduler implements AutoCloseable {
  private final AtRepository repository;
  private final AtTransactionManager manager;
  private final long intervalMillis;
  private final int batchSize;
  private final ScheduledExecutorService executor;
  private volatile ScheduledFuture<?> task;

  public AtRecoveryScheduler(AtRepository repository,AtTransactionManager manager,long intervalMillis,int batchSize){
    this.repository=repository;this.manager=manager;this.intervalMillis=intervalMillis;this.batchSize=batchSize;
    this.executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"easy-at-recovery");t.setDaemon(true);return t;});
  }
  public synchronized void start(){if(task==null)task=executor.scheduleWithFixedDelay(this::recoverSafely,intervalMillis,intervalMillis,TimeUnit.MILLISECONDS);}
  public void recoverNow(){recoverSafely();}
  private void recoverSafely(){
    try{List<AtTransaction> transactions=repository.recoverable(System.currentTimeMillis(),batchSize);for(AtTransaction tx:transactions){try{manager.recover(tx);}catch(RuntimeException ignored){/* persisted retry state is the source of truth */}}}
    catch(RuntimeException ignored){/* database/filesystem may be temporarily unavailable */}
  }
  @Override public synchronized void close(){if(task!=null)task.cancel(false);executor.shutdownNow();}
}

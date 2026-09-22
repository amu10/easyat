package io.github.easyat.core;
import java.sql.Connection;
import java.util.*;
public final class AtTransactionManager {
  private final AtRepository repository; private final UndoExecutor undoExecutor; private final GlobalLockManager lockManager; private final int maxRetries;
  public AtTransactionManager(AtRepository r,UndoExecutor u,int maxRetries){this(r,u,null,maxRetries);}
  public AtTransactionManager(AtRepository r,UndoExecutor u,GlobalLockManager locks,int maxRetries){repository=r;undoExecutor=u;lockManager=locks;this.maxRetries=maxRetries;}
  public AtTransaction begin(String name,long timeout){long now=System.currentTimeMillis();AtTransaction tx=new AtTransaction(UUID.randomUUID().toString(),name,now,now+timeout);repository.create(tx);AtContext.bind(tx.getXid());return tx;}
  /** Binds a transaction created by the calling service. The repository must be shared by both services. */
  public void join(String xid){required(xid);AtContext.bind(xid);}
  public void append(UndoRecord record){AtTransaction tx=required(record.getXid());tx.addUndo(record);repository.save(tx);}
  /** Persists undo through the business connection when the repository supports it. */
  public void append(Connection connection,UndoRecord record){if(repository instanceof ConnectionBoundAtRepository){((ConnectionBoundAtRepository)repository).append(connection,record);return;}append(record);}
  public void updateUndo(String xid,String undoId,RowImage after){AtTransaction tx=required(xid);for(UndoRecord r:tx.getUndoRecords())if(r.getId().equals(undoId)){r.setAfterImage(after);repository.save(tx);return;}throw new AtException("Undo record not found: "+undoId);}
  public void updateUndo(Connection connection,String xid,String undoId,RowImage after){if(repository instanceof ConnectionBoundAtRepository){((ConnectionBoundAtRepository)repository).updateUndo(connection,xid,undoId,after);return;}updateUndo(xid,undoId,after);}
  public void discardUndo(Connection connection,String xid,String undoId){if(repository instanceof ConnectionBoundAtRepository){((ConnectionBoundAtRepository)repository).removeUndo(connection,xid,undoId);return;}AtTransaction tx=required(xid);tx.removeUndo(undoId);repository.save(tx);}
  public void commit(String xid){
    AtTransaction tx=required(xid);
    if(!transition(tx,AtStatus.COMMITTING))throw new AtException("Conflict committing "+xid+" from "+tx.getStatus());
    if(!transition(tx,AtStatus.COMMITTED))throw new AtException("Conflict committing "+xid+" from "+tx.getStatus());
    release(xid);
  }
  public void rollback(String xid){rollback(xid,true);}
  public void rollback(String xid,boolean releaseLockOnConverge){
    AtTransaction tx=required(xid);
    if(tx.getStatus()==AtStatus.ROLLED_BACK)return;
    if(tx.getStatus()!=AtStatus.ROLLING_BACK&&!transition(tx,AtStatus.ROLLING_BACK))throw new AtException("Conflict rolling back "+xid+" from "+tx.getStatus());
    List<UndoRecord> records=new ArrayList<UndoRecord>(tx.getUndoRecords());Collections.reverse(records);
    try{
      for(UndoRecord r:records){if(!r.isRolledBack()){undoExecutor.rollback(r);r.markRolledBack();repository.save(tx);}}
      if(!transition(tx,AtStatus.ROLLED_BACK))throw new AtException("Conflict finalizing rollback "+xid);
      if(releaseLockOnConverge)release(xid);
    }catch(DirtyWriteException dirty){
      tx.setDirtyWriteTable(dirty.getMessage());
      if(!transition(tx,AtStatus.DIRTY_WRITE))throw new AtException("Conflict recording dirty write "+xid);
      throw dirty;
    }catch(Exception e){
      if(!transition(tx,AtStatus.ROLLBACK_FAILED))throw new AtException("Conflict recording rollback failure "+xid,e);
      tx.setNextRetryAt(System.currentTimeMillis()+backoff(tx.getRetries()));
      repository.updateRecovery(xid,tx.getRetries(),tx.getNextRetryAt());
      throw new AtException("AT rollback failed: "+xid,e);
    }
  }
  public void recover(AtTransaction tx){
    if(tx.getRetries()>=maxRetries){
      if(transition(tx,AtStatus.MANUAL_INTERVENTION))return;
      throw new AtException("Cannot move "+tx.getXid()+" to MANUAL_INTERVENTION");
    }
    tx.incrementRetries();
    repository.updateRecovery(tx.getXid(),tx.getRetries(),System.currentTimeMillis()+backoff(tx.getRetries()));
    rollback(tx.getXid(),true);
  }
  /** Admin-driven state change with audit handled by the caller. */
  public boolean forceTransition(String xid,AtStatus to){AtTransaction tx=required(xid);return transition(tx,to);}
  public boolean claimLease(String xid,String owner,long leaseUntil,long now){return repository.claimLease(xid,owner,leaseUntil,now);}
  public void releaseLease(String xid,String owner){repository.releaseLease(xid,owner);}
  private boolean transition(AtTransaction tx,AtStatus to){
    if(!tx.getStatus().canTransitionTo(to))throw new AtException("Illegal AT status transition: "+tx.getStatus()+" -> "+to);
    if(!repository.transition(tx.getXid(),tx.getStatus(),tx.getVersion(),to))return false;
    tx.applyTransition(to);return true;
  }
  private AtTransaction required(String xid){return repository.find(xid).orElseThrow(()->new AtException("Transaction not found: "+xid));}
  private void release(String xid){if(lockManager!=null)lockManager.releaseByXid(xid);}
  private long backoff(int retries){return Math.min(300000L,1000L<<Math.min(retries,8));}
}

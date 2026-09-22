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
  /** Updates after-image in the same transaction as the intercepted DML. */
  public void updateUndo(Connection connection,String xid,String undoId,RowImage after){if(repository instanceof ConnectionBoundAtRepository){((ConnectionBoundAtRepository)repository).updateUndo(connection,xid,undoId,after);return;}updateUndo(xid,undoId,after);}
  /** Removes an undo record when its protected JDBC statement failed. */
  public void discardUndo(Connection connection,String xid,String undoId){if(repository instanceof ConnectionBoundAtRepository){((ConnectionBoundAtRepository)repository).removeUndo(connection,xid,undoId);return;}AtTransaction tx=required(xid);tx.removeUndo(undoId);repository.save(tx);}
  public void commit(String xid){AtTransaction tx=required(xid);tx.setStatus(AtStatus.COMMITTING);repository.save(tx);tx.setStatus(AtStatus.COMMITTED);repository.save(tx);release(xid);}
  public void rollback(String xid){AtTransaction tx=required(xid);tx.setStatus(AtStatus.ROLLING_BACK);repository.save(tx);List<UndoRecord> records=new ArrayList<UndoRecord>(tx.getUndoRecords());Collections.reverse(records);try{for(UndoRecord r:records){if(!r.isRolledBack()){undoExecutor.rollback(r);r.markRolledBack();repository.save(tx);}}tx.setStatus(AtStatus.ROLLED_BACK);}catch(Exception e){tx.setStatus(AtStatus.ROLLBACK_FAILED);tx.setNextRetryAt(System.currentTimeMillis()+Math.min(300000L,1000L<<Math.min(tx.getRetries(),8)));repository.save(tx);throw new AtException("AT rollback failed: "+xid,e);}repository.save(tx);release(xid);}
  public void recover(AtTransaction tx){if(tx.getRetries()>=maxRetries){tx.setStatus(AtStatus.MANUAL_INTERVENTION);repository.save(tx);return;}tx.incrementRetries();repository.save(tx);rollback(tx.getXid());}
  private AtTransaction required(String xid){return repository.find(xid).orElseThrow(()->new AtException("Transaction not found: "+xid));}
  private void release(String xid){if(lockManager!=null)lockManager.releaseByXid(xid);}
}

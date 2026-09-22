package io.github.easyat.core;
import java.io.Serializable; import java.util.*;
/**
 * Persisted global transaction. The version column is the optimistic lock that makes
 * all status transitions safe under concurrent recovery instances (see {@link AtRepository#transition}).
 */
public final class AtTransaction implements Serializable {
  private static final long serialVersionUID=1L;
  private final String xid,name; private final long createdAt,deadline;
  private AtStatus status=AtStatus.ACTIVE; private int retries; private long nextRetryAt;
  private long version; private String owner; private long leaseUntil;
  private String dirtyWriteTable; private String dirtyWriteKey;
  private final List<UndoRecord> undoRecords=new ArrayList<UndoRecord>();
  public AtTransaction(String xid,String name,long now,long deadline){this.xid=xid;this.name=name;this.createdAt=now;this.deadline=deadline;}
  public String getXid(){return xid;} public String getName(){return name;} public long getCreatedAt(){return createdAt;} public long getDeadline(){return deadline;}
  public AtStatus getStatus(){return status;} public void setStatus(AtStatus s){AtStatus.validate(this.status,s);this.status=s;}
  public int getRetries(){return retries;} public void incrementRetries(){retries++;} public void setRetries(int r){retries=r;} public long getNextRetryAt(){return nextRetryAt;} public void setNextRetryAt(long v){nextRetryAt=v;}
  public long getVersion(){return version;} public void setVersion(long v){version=v;} public long versionAfter(){return version+1;}
  public String getOwner(){return owner;} public void setOwner(String o){owner=o;} public long getLeaseUntil(){return leaseUntil;} public void setLeaseUntil(long v){leaseUntil=v;}
  public String getDirtyWriteTable(){return dirtyWriteTable;} public void setDirtyWriteTable(String t){dirtyWriteTable=t;}
  public String getDirtyWriteKey(){return dirtyWriteKey;} public void setDirtyWriteKey(String k){dirtyWriteKey=k;}
  public List<UndoRecord> getUndoRecords(){return Collections.unmodifiableList(undoRecords);}
  public void addUndo(UndoRecord r){undoRecords.add(r);}
  public void removeUndo(String undoId){for(Iterator<UndoRecord> it=undoRecords.iterator();it.hasNext();)if(it.next().getId().equals(undoId)){it.remove();return;}}
  /** Restores persisted state without transition validation (used when loading from storage). */
  public void restore(AtStatus status,int retries,long nextRetryAt){this.status=status;this.retries=retries;this.nextRetryAt=nextRetryAt;}
  public void applyTransition(AtStatus next){this.status=next;this.version++;}
}

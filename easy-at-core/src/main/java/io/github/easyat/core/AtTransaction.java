package io.github.easyat.core;
import java.io.Serializable; import java.util.*;
public final class AtTransaction implements Serializable {
  private static final long serialVersionUID=1L; private final String xid,name; private final long createdAt,deadline; private AtStatus status=AtStatus.ACTIVE; private int retries; private long nextRetryAt; private final List<UndoRecord> undoRecords=new ArrayList<UndoRecord>();
  public AtTransaction(String xid,String name,long now,long deadline){this.xid=xid;this.name=name;this.createdAt=now;this.deadline=deadline;}
  public String getXid(){return xid;} public String getName(){return name;} public long getCreatedAt(){return createdAt;} public long getDeadline(){return deadline;} public AtStatus getStatus(){return status;} public void setStatus(AtStatus s){status=s;} public int getRetries(){return retries;} public void incrementRetries(){retries++;} public long getNextRetryAt(){return nextRetryAt;} public void setNextRetryAt(long v){nextRetryAt=v;} public List<UndoRecord> getUndoRecords(){return Collections.unmodifiableList(undoRecords);} public void addUndo(UndoRecord r){undoRecords.add(r);} public void removeUndo(String undoId){for(Iterator<UndoRecord> it=undoRecords.iterator();it.hasNext();)if(it.next().getId().equals(undoId)){it.remove();return;}}
  public void restore(AtStatus status,int retries,long nextRetryAt){this.status=status;this.retries=retries;this.nextRetryAt=nextRetryAt;}
}

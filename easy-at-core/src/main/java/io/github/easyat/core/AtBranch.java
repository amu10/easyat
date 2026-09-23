package io.github.easyat.core;

import java.io.Serializable;
import java.util.UUID;

/** A branch is one participating DataSource's AT work for a global transaction. */
public final class AtBranch implements Serializable {
    private static final long serialVersionUID=1L;
    private final String branchId,xid,resourceId,serviceName,callbackUrl;
    private final int sequence;
    private BranchStatus status=BranchStatus.REGISTERED;
    private int retries; private long createdAt,updatedAt,nextRetryAt;
    public AtBranch(String xid,String resourceId,String serviceName,String callbackUrl,int sequence){
        this.branchId=UUID.randomUUID().toString();this.xid=xid;this.resourceId=resourceId;this.serviceName=serviceName;this.callbackUrl=callbackUrl;this.sequence=sequence;
        long now=System.currentTimeMillis();this.createdAt=now;this.updatedAt=now;
    }
    public AtBranch(String branchId,String xid,String resourceId,String serviceName,String callbackUrl,int sequence,BranchStatus status,int retries,long createdAt,long updatedAt,long nextRetryAt){
        this.branchId=branchId;this.xid=xid;this.resourceId=resourceId;this.serviceName=serviceName;this.callbackUrl=callbackUrl;this.sequence=sequence;
        this.status=status;this.retries=retries;this.createdAt=createdAt;this.updatedAt=updatedAt;this.nextRetryAt=nextRetryAt;
    }
    public String getBranchId(){return branchId;} public String getXid(){return xid;} public String getResourceId(){return resourceId;}
    public String getServiceName(){return serviceName;} public String getCallbackUrl(){return callbackUrl;} public int getSequence(){return sequence;}
    public BranchStatus getStatus(){return status;} public void setStatus(BranchStatus s){this.status=s;} public void applyTransition(BranchStatus s){this.status=s;}
    public int getRetries(){return retries;} public void setRetries(int r){retries=r;} public long getCreatedAt(){return createdAt;} public long getUpdatedAt(){return updatedAt;} public void setUpdatedAt(long v){updatedAt=v;}
    public long getNextRetryAt(){return nextRetryAt;} public void setNextRetryAt(long v){nextRetryAt=v;}
}

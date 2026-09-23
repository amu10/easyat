package io.github.easyat.spring;

import io.github.easyat.core.*;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;
import java.util.List;

/**
 * Coordinates AT branches: registration, idempotent commit/rollback delivery, and reliable
 * retry. Local branches are resolved through the in-process {@link AtTransactionManager}; remote
 * branches are driven over HTTP to their registered callback URL with HMAC-signed headers.
 */
public final class BranchCoordinator {
    private final BranchRepository branches; private final AtTransactionManager manager; private final HmacSigner signer; private final String appName; private final EasyAtMetrics metrics; private final RestTemplate rest=new RestTemplate();
    public BranchCoordinator(BranchRepository branches,AtTransactionManager manager,HmacSigner signer,String appName,EasyAtMetrics metrics){
        this.branches=branches;this.manager=manager;this.signer=signer;this.appName=appName;this.metrics=metrics;
    }
    /** Registers a branch. Idempotent per (xid, resourceId). */
    public synchronized String register(String xid,String resourceId,String serviceName,String callbackUrl){
        for(AtBranch b:branches.byXid(xid))if(b.getResourceId().equals(resourceId))return b.getBranchId();
        AtBranch b=new AtBranch(xid,resourceId,serviceName,normalize(callbackUrl),branches.byXid(xid).size()+1);
        branches.register(b);return b.getBranchId();
    }
    public void commit(String xid){drive(xid,false);}
    public void rollback(String xid){drive(xid,true);}
    public BranchStatus commitBranch(String branchId){return act(branchId,false);}
    public BranchStatus rollbackBranch(String branchId){return act(branchId,true);}
    private BranchStatus act(String branchId,boolean rollback){
        AtBranch b=branches.find(branchId).orElseThrow(()->new AtException("Branch not found: "+branchId));
        if(b.getStatus().isTerminal())return b.getStatus();
        if(isLocal(b)){
            if(rollback){manager.rollback(b.getXid());}
            return mark(b,rollback?BranchStatus.ROLLED_BACK:BranchStatus.COMMITTED);
        }
        return deliverRemote(b,rollback);
    }
    private void drive(String xid,boolean rollback){
        List<AtBranch> list=branches.byXid(xid);boolean localHandled=false;
        for(AtBranch b:list){
            if(b.getStatus().isTerminal())continue;
            if(isLocal(b)){
                if(!localHandled){if(rollback)manager.rollback(xid);localHandled=true;}
                mark(b,rollback?BranchStatus.ROLLED_BACK:BranchStatus.COMMITTED);
            }else{
                deliverRemote(b,rollback);
            }
        }
    }
    private BranchStatus deliverRemote(AtBranch b,boolean rollback){
        try{
            String url=b.getCallbackUrl()+(rollback?"/rollback":"/commit");
            HttpHeaders headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);
            long deadline=System.currentTimeMillis()+30000L;
            headers.set(AtTransportHeaders.XID,b.getXid());
            headers.set(AtTransportHeaders.DEADLINE,Long.toString(deadline));
            headers.set(AtTransportHeaders.SOURCE,appName);
            if(signer.isConfigured())headers.set(AtTransportHeaders.SIGNATURE,signer.sign(b.getXid(),deadline,appName));
            ResponseEntity<String> res=rest.postForEntity(url,new HttpEntity<String>(headers),String.class);
            if(res.getStatusCode().is2xxSuccessful())return mark(b,rollback?BranchStatus.ROLLED_BACK:BranchStatus.COMMITTED);
            return mark(b,BranchStatus.ROLLBACK_FAILED);
        }catch(Exception e){
            return mark(b,BranchStatus.ROLLBACK_FAILED);
        }
    }
    private BranchStatus mark(AtBranch b,BranchStatus next){
        b.setStatus(next);branches.save(b);return next;
    }
    private boolean isLocal(AtBranch b){return b.getCallbackUrl()==null||b.getCallbackUrl().isEmpty();}
    private static String normalize(String url){return url==null||url.trim().isEmpty()?null:url.trim();}
}

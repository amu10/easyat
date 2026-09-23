package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.time.Instant;
import java.util.*;

/**
 * Servlet-agnostic management/ops API core: inspect transactions, force a retry or rollback, and
 * keep an in-memory audit trail of every human action. The Boot 2/Boot 3 starters expose this
 * through {@code @RestController} adapters and perform the token check.
 */
public final class ManagementService {
    private final AtRepository repository; private final AtTransactionManager manager; private final EasyAtMetrics metrics;
    private final boolean enabled; private final String token; private final UndoDataCodec codec;
    private final List<AuditEntry> audit=Collections.synchronizedList(new ArrayList<AuditEntry>());
    public ManagementService(AtRepository repository,AtTransactionManager manager,EasyAtMetrics metrics,boolean enabled,String token,UndoDataCodec codec){
        this.repository=repository;this.manager=manager;this.metrics=metrics;this.enabled=enabled;this.token=token;this.codec=codec;
    }
    public boolean isEnabled(){return enabled;}
    /** Returns true when the supplied admin token is acceptable. When disabled, never authorized. */
    public boolean authorized(String providedToken){
        if(!enabled)return false;
        if(token==null||token.isEmpty())return true; // enabled without a token: dev convenience
        return token.equals(providedToken);
    }
    public Map<String,Object> getTransaction(String xid){
        Optional<AtTransaction> tx=repository.find(xid);
        if(!tx.isPresent())return notFound(xid);
        return toMap(tx.get());
    }
    public List<Map<String,Object>> listByStatus(String status,int limit){
        AtStatus s=AtStatus.valueOf(status);
        List<Map<String,Object>> out=new ArrayList<Map<String,Object>>();
        for(AtTransaction tx:repository.findByStatus(s,limit))out.add(toMap(tx));
        return out;
    }
    /** Re-enters the recovery path for a stuck transaction (e.g. MANUAL_INTERVENTION). */
    public Map<String,Object> retry(String xid,String operator,String reason){
        AtTransaction tx=required(xid);
        if(tx.getStatus().isTerminal())return bad("Transaction already terminal: "+tx.getStatus());
        manager.forceTransition(xid,AtStatus.ROLLING_BACK);
        audit("retry",xid,operator,reason,"re-entered ROLLING_BACK");
        metrics.recordManualIntervention();
        return toMap(required(xid));
    }
    /** Force a rollback now. */
    public Map<String,Object> rollback(String xid,String operator,String reason){
        required(xid);
        manager.forceTransition(xid,AtStatus.ROLLING_BACK);
        try{manager.rollback(xid);}
        catch(AtException e){audit("rollback",xid,operator,reason,"failed: "+e.getMessage());throw e;}
        audit("rollback",xid,operator,reason,"ok");
        return toMap(required(xid));
    }
    public List<AuditEntry> audit(){return new ArrayList<AuditEntry>(audit);}
    private AtTransaction required(String xid){return repository.find(xid).orElseThrow(()->new AtException("Transaction not found: "+xid));}
    private Map<String,Object> toMap(AtTransaction tx){
        Map<String,Object> m=new LinkedHashMap<String,Object>();
        m.put("xid",tx.getXid());m.put("name",tx.getName());m.put("status",tx.getStatus().name());
        m.put("retries",tx.getRetries());m.put("owner",tx.getOwner());
        m.put("leaseUntil",tx.getLeaseUntil()==0?null:tx.getLeaseUntil());
        m.put("nextRetryAt",tx.getNextRetryAt()==0?null:tx.getNextRetryAt());
        if(tx.getDirtyWriteTable()!=null)m.put("dirtyWriteTable",tx.getDirtyWriteTable());
        if(tx.getDirtyWriteKey()!=null)m.put("dirtyWriteKey",tx.getDirtyWriteKey());
        if(codec!=null){
            List<Map<String,Object>> undo=new ArrayList<Map<String,Object>>();
            for(UndoRecord r:tx.getUndoRecords()){
                Map<String,Object> u=new LinkedHashMap<String,Object>();
                u.put("id",r.getId());u.put("resourceId",r.getResourceId());u.put("table",r.getTableName());
                UndoContext ctx=new UndoContext(r.getResourceId(),r.getTableName());
                if(r.getBeforeImage()!=null)u.put("before",codec.toDiagnosticString(r.getBeforeImage(),ctx));
                if(r.getAfterImage()!=null)u.put("after",codec.toDiagnosticString(r.getAfterImage(),ctx));
                undo.add(u);
            }
            m.put("undo",undo);
        }
        return m;
    }
    private Map<String,Object> notFound(String xid){Map<String,Object> m=new LinkedHashMap<String,Object>();m.put("error","not_found");m.put("xid",xid);return m;}
    private Map<String,Object> bad(String msg){Map<String,Object> m=new LinkedHashMap<String,Object>();m.put("error",msg);return m;}
    private void audit(String action,String xid,String operator,String reason,String result){audit.add(new AuditEntry(Instant.now(),action,xid,operator,reason,result));}
    public static final class AuditEntry{public final Instant at;public final String action,xid,operator,reason,result;
        AuditEntry(Instant at,String action,String xid,String operator,String reason,String result){this.at=at;this.action=action;this.xid=xid;this.operator=operator;this.reason=reason;this.result=result;}}
}

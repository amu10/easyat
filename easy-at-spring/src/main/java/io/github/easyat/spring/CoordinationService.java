package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.util.*;

/**
 * Servlet-agnostic core of the cross-service coordination endpoints. The Boot 2/Boot 3 starters
 * provide the thin {@code @RestController} adapters that extract headers into a {@code Map} and
 * delegate here, so the same logic runs on both servlet APIs.
 */
public final class CoordinationService {
    private final BranchCoordinator coordinator; private final EasyAtTransportSecurity security;
    public CoordinationService(BranchCoordinator coordinator,EasyAtTransportSecurity security){this.coordinator=coordinator;this.security=security;}
    public Map<String,Object> register(Map<String,String> headers,Map<String,String> body){
        if(!security.authorized(headers))return forbidden();
        String xid=body.get("xid"),resourceId=body.get("resourceId"),service=body.get("service"),callback=body.get("callbackUrl");
        if(xid==null||resourceId==null)return bad("xid and resourceId are required");
        String branchId=coordinator.register(xid,resourceId,service,callback);
        return response(branchId,BranchStatus.REGISTERED.name());
    }
    public Map<String,Object> commit(Map<String,String> headers,String branchId){
        if(!security.authorized(headers))return forbidden();
        return response(branchId,coordinator.commitBranch(branchId).name());
    }
    public Map<String,Object> rollback(Map<String,String> headers,String branchId){
        if(!security.authorized(headers))return forbidden();
        return response(branchId,coordinator.rollbackBranch(branchId).name());
    }
    private Map<String,Object> response(String branchId,String status){Map<String,Object> m=new LinkedHashMap<String,Object>();m.put("branchId",branchId);m.put("status",status);return m;}
    private Map<String,Object> bad(String msg){Map<String,Object> m=new LinkedHashMap<String,Object>();m.put("error",msg);return m;}
    private Map<String,Object> forbidden(){Map<String,Object> m=new LinkedHashMap<String,Object>();m.put("error","forbidden");return m;}
}

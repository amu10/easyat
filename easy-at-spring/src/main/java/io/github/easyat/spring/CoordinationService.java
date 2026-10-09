package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Servlet-agnostic core of the cross-service coordination endpoints. The Boot 2/Boot 3 starters
 * provide the thin {@code @RestController} adapters that extract headers into a {@code Map} and
 * delegate here, so the same logic runs on both servlet APIs.
 *
 * <p>Endpoints answer with real HTTP status codes. Returning {@code 200} with an error body would
 * be read as success by {@link BranchCoordinator#deliverRemote}, which marks the branch as already
 * rolled back — a silent data-consistency hole.
 */
public final class CoordinationService {
    private final BranchCoordinator coordinator;
    private final EasyAtTransportSecurity security;

    public CoordinationService(BranchCoordinator coordinator, EasyAtTransportSecurity security) {
        this.coordinator = coordinator;
        this.security = security;
    }

    public ResponseEntity<Map<String, Object>> register(
            Map<String, String> headers, Map<String, String> body) {
        if (!security.authorized(headers)) return forbidden();
        String xid = body.get("xid"),
                resourceId = body.get("resourceId"),
                service = body.get("service"),
                callback = body.get("callbackUrl");
        if (xid == null || resourceId == null) return bad("xid and resourceId are required");
        String branchId;
        try {
            branchId = coordinator.register(xid, resourceId, service, callback);
        } catch (AtException rejected) {
            // 该资源已被回滚（防悬挂命中）：用 409 明确拒绝，调用方据此让全局事务失败而不是继续。
            return ResponseEntity.status(HttpStatus.CONFLICT).body(error(rejected.getMessage()));
        }
        return ok(branchId, BranchStatus.REGISTERED.name());
    }

    public ResponseEntity<Map<String, Object>> commit(
            Map<String, String> headers, String branchId) {
        if (!security.authorized(headers)) return forbidden();
        return ok(branchId, coordinator.commitBranch(branchId).name());
    }

    /**
     * Rollback a branch. Carries {@code xid} (from header) and {@code resourceId} (from body) so
     * the receiving side can perform an empty rollback when the branch row does not exist yet.
     */
    public ResponseEntity<Map<String, Object>> rollback(
            Map<String, String> headers, String branchId, Map<String, String> body) {
        if (!security.authorized(headers)) return forbidden();
        String resourceId = body == null ? null : body.get("resourceId");
        return ok(
                branchId,
                coordinator
                        .rollbackBranch(
                                branchId, header(headers, AtTransportHeaders.XID), resourceId)
                        .name());
    }

    private ResponseEntity<Map<String, Object>> ok(String branchId, String status) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("branchId", branchId);
        m.put("status", status);
        return ResponseEntity.ok(m);
    }

    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(error(msg));
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("forbidden"));
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("error", msg);
        return m;
    }

    /** HTTP 头名大小写不敏感，按名字查找而不是直接 get。 */
    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet())
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }
}

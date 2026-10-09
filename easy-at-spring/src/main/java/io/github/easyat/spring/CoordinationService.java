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
    /** 分支协调器，执行真正的注册/提交/回滚逻辑。 */
    private final BranchCoordinator coordinator;

    /** 跨服务请求的鉴权组件（HMAC 签名、截止时间、来源校验）。 */
    private final EasyAtTransportSecurity security;

    public CoordinationService(BranchCoordinator coordinator, EasyAtTransportSecurity security) {
        this.coordinator = coordinator;
        this.security = security;
    }

    /**
     * 处理分支注册请求。先鉴权，再校验必填参数（xid、resourceId），最后委托协调器注册。
     * 防悬挂命中（资源已回滚）时返回 409。
     *
     * @param headers 请求头（含鉴权信息）
     * @param body 请求体（xid / resourceId / service / callbackUrl）
     * @return 含 branchId 与状态的响应
     */
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

    /**
     * 处理分支提交请求。先鉴权，再委托协调器提交指定分支。
     *
     * @param headers 请求头（含鉴权信息）
     * @param branchId 分支 id
     * @return 含分支状态（应为 COMMITTED）的响应
     */
    public ResponseEntity<Map<String, Object>> commit(
            Map<String, String> headers, String branchId) {
        if (!security.authorized(headers)) return forbidden();
        return ok(branchId, coordinator.commitBranch(branchId).name());
    }

    /**
     * Rollback a branch. Carries {@code xid} (from header) and {@code resourceId} (from body) so
     * the receiving side can perform an empty rollback when the branch row does not exist yet.
     */
    /**
     * 处理分支回滚请求。先鉴权，再带上 xid（来自头）与 resourceId（来自体）委托协调器回滚，
     * 以便分支缺失时仍能完成空回滚。
     *
     * @param headers 请求头（含鉴权信息与 XID）
     * @param branchId 分支 id
     * @param body 请求体（可含 resourceId）
     * @return 含分支状态（应为 ROLLED_BACK）的响应
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

    /** 构造成功响应：body 含 branchId 与当前分支状态。 */
    private ResponseEntity<Map<String, Object>> ok(String branchId, String status) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("branchId", branchId);
        m.put("status", status);
        return ResponseEntity.ok(m);
    }

    /** 构造 400 错误响应。 */
    private ResponseEntity<Map<String, Object>> bad(String msg) {
        return ResponseEntity.badRequest().body(error(msg));
    }

    /** 构造 401 未授权响应（鉴权失败）。 */
    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error("forbidden"));
    }

    /** 构造统一的错误体 {@code {error: msg}}。 */
    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("error", msg);
        return m;
    }

    /** HTTP 头名大小写不敏感，按名字查找而不是直接 get。 */
    /**
     * 在请求头中按名（大小写不敏感）查找值。
     *
     * @param headers 请求头映射
     * @param name 头名
     * @return 匹配的值，找不到时返回 null
     */
    private static String header(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> e : headers.entrySet())
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }
}

package io.github.easyat.boot2;

import io.github.easyat.core.*;
import io.github.easyat.spring.CoordinationService;
import java.util.*;
import javax.servlet.http.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 分支协调控制器，统一前缀 {@code /_easy-at/v1}。
 *
 * <p>对外暴露分支事务的「注册 / 提交 / 回滚」HTTP 端点，供上游事务管理器或同实例的
 * 二阶段驱动调用；入站请求先经 {@link AtXidFilter} 做 HMAC 签名鉴权，再进入本控制器。
 */
@RestController
@RequestMapping("/_easy-at/v1")
public final class EasyAtCoordinationController {
    /** 分支协调服务：承载真正的注册/提交/回滚业务逻辑。 */
    private final CoordinationService service;

    /** 注入分支协调服务。 */
    public EasyAtCoordinationController(CoordinationService service) {
        this.service = service;
    }

    /** 把请求头整体取出为 Map，供下游做签名验签与透传。 */
    private Map<String, String> headers(HttpServletRequest r) {
        Map<String, String> m = new HashMap<String, String>();
        Enumeration<String> e = r.getHeaderNames();
        while (e != null && e.hasMoreElements()) {
            String n = e.nextElement();
            m.put(n, r.getHeader(n));
        }
        return m;
    }

    /**
     * 注册一个分支事务。
     *
     * @param body 分支注册信息（如资源、锁键等），由 {@code CoordinationService} 解析
     * @param r    请求对象（用于取出请求头做签权/透传）
     * @return 注册结果（含分支 ID 等），由协调服务决定状态码
     */
    @PostMapping("/branches")
    public ResponseEntity<Map<String, Object>> register(
            @RequestBody Map<String, String> body, HttpServletRequest r) {
        return service.register(headers(r), body);
    }

    /**
     * 提交指定分支事务（二阶段提交）。
     *
     * @param branchId 分支事务 ID
     * @param r        请求对象（用于取出请求头做签权/透传）
     * @return 提交结果，由协调服务决定状态码
     */
    @PostMapping("/branches/{branchId}/commit")
    public ResponseEntity<Map<String, Object>> commit(
            @PathVariable String branchId, HttpServletRequest r) {
        return service.commit(headers(r), branchId);
    }

    /**
     * 回滚指定分支事务（二阶段回滚）。
     *
     * @param branchId 分支事务 ID
     * @param body     可选回滚参数（如需要回滚的 undo 批次信息），可缺省
     * @param r        请求对象（用于取出请求头做签权/透传）
     * @return 回滚结果，由协调服务决定状态码
     */
    @PostMapping("/branches/{branchId}/rollback")
    public ResponseEntity<Map<String, Object>> rollback(
            @PathVariable String branchId,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest r) {
        return service.rollback(headers(r), branchId, body);
    }
}

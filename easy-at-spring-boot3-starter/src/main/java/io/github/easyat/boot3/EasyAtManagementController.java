package io.github.easyat.boot3;

import io.github.easyat.spring.ManagementService;
import io.github.easyat.spring.ManagementUi;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/**
 * 管理/运维端点控制器，统一前缀 {@code /_easy-at/v1}。
 *
 * <p>提供全局事务查询、按状态列表、影子对账、人工重试/回滚，以及内置 HTML 运维界面；
 * 除 {@code /ui} 外所有端点均受 {@code X-EasyAt-Token} 令牌保护（见 {@link #auth}）。
 */
@RestController
@RequestMapping("/_easy-at/v1")
public final class EasyAtManagementController {
    /** 管理/运维服务：承载事务查询与人工重试/回滚等逻辑。 */
    private final ManagementService service;

    /** 注入管理/运维服务。 */
    public EasyAtManagementController(ManagementService service) {
        this.service = service;
    }

    /** 返回内置运维 HTML 页面（text/html），供人工在浏览器查看/操作。 */
    @GetMapping(value = "/ui", produces = "text/html")
    public String ui() {
        return ManagementUi.page();
    }

    /** 校验请求头 {@code X-EasyAt-Token} 是否与配置令牌匹配，不匹配则拒绝后续操作。 */
    private boolean auth(HttpServletRequest r) {
        return service.authorized(r.getHeader("X-EasyAt-Token"));
    }

    /**
     * 查询单个全局事务的详情。
     *
     * @param xid 全局事务 ID
     * @param r   请求对象（用于取令牌鉴权）
     * @return 事务详情；鉴权失败返回 {@link #denied()} 占位
     */
    @GetMapping("/transactions/{xid}")
    public Map<String, Object> get(@PathVariable String xid, HttpServletRequest r) {
        if (!auth(r)) return denied();
        return service.getTransaction(xid);
    }

    /**
     * 按状态分页列出全局事务。
     *
     * @param status 目标状态（如 COMMITTED / ROLLED_BACK 等）
     * @param limit  返回条数上限，默认 100
     * @param r      请求对象（用于取令牌鉴权）
     * @return 事务列表；鉴权失败返回空列表
     */
    @GetMapping("/transactions")
    public List<Map<String, Object>> list(
            @RequestParam String status,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest r) {
        if (!auth(r)) return Collections.emptyList();
        return service.listByStatus(status, limit);
    }

    /** 影子运行对账：GET /_easy-at/v1/reconciliation —— 建议灰度期间定时拉取并配置告警。 */
    @GetMapping("/reconciliation")
    public Map<String, Object> reconciliation(HttpServletRequest r) {
        if (!auth(r)) return denied();
        return service.reconciliation();
    }

    /**
     * 人工重试指定全局事务（重新驱动其分支提交/回滚补偿）。
     *
     * @param xid      全局事务 ID
     * @param operator 操作人（默认空串，仅作审计记录）
     * @param reason   重试原因（默认空串，仅作审计记录）
     * @param r        请求对象（用于取令牌鉴权）
     * @return 操作结果；鉴权失败返回 {@link #denied()} 占位
     */
    @PostMapping("/transactions/{xid}/retry")
    public Map<String, Object> retry(
            @PathVariable String xid,
            @RequestParam(defaultValue = "") String operator,
            @RequestParam(defaultValue = "") String reason,
            HttpServletRequest r) {
        if (!auth(r)) return denied();
        return service.retry(xid, operator, reason);
    }

    /**
     * 人工回滚指定全局事务（强制驱动其分支回滚，用于异常兜底）。
     *
     * @param xid      全局事务 ID
     * @param operator 操作人（默认空串，仅作审计记录）
     * @param reason   回滚原因（默认空串，仅作审计记录）
     * @param r        请求对象（用于取令牌鉴权）
     * @return 操作结果；鉴权失败返回 {@link #denied()} 占位
     */
    @PostMapping("/transactions/{xid}/rollback")
    public Map<String, Object> rollback(
            @PathVariable String xid,
            @RequestParam(defaultValue = "") String operator,
            @RequestParam(defaultValue = "") String reason,
            HttpServletRequest r) {
        if (!auth(r)) return denied();
        return service.rollback(xid, operator, reason);
    }

    /** 构造统一的「鉴权失败」响应体（{@code error=forbidden}）。 */
    private Map<String, Object> denied() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("error", "forbidden");
        return m;
    }
}

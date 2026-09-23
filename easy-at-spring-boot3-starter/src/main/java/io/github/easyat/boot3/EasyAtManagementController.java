package io.github.easyat.boot3;

import io.github.easyat.spring.ManagementService;
import io.github.easyat.spring.ManagementUi;
import jakarta.servlet.http.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/_easy-at/v1")
public final class EasyAtManagementController {
    private final ManagementService service;

    public EasyAtManagementController(ManagementService service) {
        this.service = service;
    }

    @GetMapping(value = "/ui", produces = "text/html")
    public String ui() {
        return ManagementUi.page();
    }

    private boolean auth(HttpServletRequest r) {
        return service.authorized(r.getHeader("X-EasyAt-Token"));
    }

    @GetMapping("/transactions/{xid}")
    public Map<String, Object> get(@PathVariable String xid, HttpServletRequest r) {
        if (!auth(r)) return denied();
        return service.getTransaction(xid);
    }

    @GetMapping("/transactions")
    public List<Map<String, Object>> list(
            @RequestParam String status,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest r) {
        if (!auth(r)) return Collections.emptyList();
        return service.listByStatus(status, limit);
    }

    @PostMapping("/transactions/{xid}/retry")
    public Map<String, Object> retry(
            @PathVariable String xid,
            @RequestParam(defaultValue = "") String operator,
            @RequestParam(defaultValue = "") String reason,
            HttpServletRequest r) {
        if (!auth(r)) return denied();
        return service.retry(xid, operator, reason);
    }

    @PostMapping("/transactions/{xid}/rollback")
    public Map<String, Object> rollback(
            @PathVariable String xid,
            @RequestParam(defaultValue = "") String operator,
            @RequestParam(defaultValue = "") String reason,
            HttpServletRequest r) {
        if (!auth(r)) return denied();
        return service.rollback(xid, operator, reason);
    }

    private Map<String, Object> denied() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("error", "forbidden");
        return m;
    }
}

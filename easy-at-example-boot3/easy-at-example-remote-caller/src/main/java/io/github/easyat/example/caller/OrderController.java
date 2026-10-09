package io.github.easyat.example.caller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 下单服务的 HTTP 入口：触发跨服务的下单事务（账户扣款 + 写订单 + 远程扣库存），并提供状态查询。
 *
 * <p>{@link #place} 只是把请求转交给 {@link OrderService}（真正的 AT 全局事务发起处）；
 * {@link #state} 用于观察账户、库存、全局事务与 undo 日志，直观验证整笔事务提交或回滚后
 * 各侧数据是否一致——尤其能看出同一个 XID 是否真的跨服务传播到了 callee 的库存库。
 */
@RestController
@RequestMapping("/order")
public class OrderController {
    private final OrderService orderService;
    private final JdbcTemplate jdbc;

    public OrderController(OrderService orderService, JdbcTemplate jdbc) {
        this.orderService = orderService;
        this.jdbc = jdbc;
    }

    /** 下单：成功则账户扣款 + 库存扣减都提交；fail=true 或库存不足则整笔回滚。 */
    @PostMapping("/place")
    public String place(
            @RequestParam("userId") long userId,
            @RequestParam("itemId") long itemId,
            @RequestParam("qty") int qty,
            @RequestParam(name = "fail", defaultValue = "false") boolean fail) {
        return orderService.placeOrder(userId, itemId, qty, fail);
    }

    /** 观察当前账户余额 / 库存 / 最近全局事务状态，验证提交或回滚结果。 */
    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("accounts", jdbc.queryForList("SELECT id,balance FROM account ORDER BY id"));
        m.put(
                "inventory",
                jdbc.queryForList("SELECT item_id,stock FROM inventory ORDER BY item_id"));
        m.put(
                "transactions",
                jdbc.queryForList(
                        "SELECT xid,name,status FROM easy_at_global ORDER BY created_at DESC LIMIT 10"));
        // undo 日志：若 XID 跨服务传播成功，同一个 xid 下会同时出现 caller(account/orders)
        // 与 callee(inventory) 写入的记录，两者 resource_id 不同
        m.put(
                "undoLogs",
                jdbc.queryForList(
                        "SELECT undo_id,xid,resource_id,table_name,pk_name,pk_value,status,created_at "
                                + "FROM easy_at_undo_log ORDER BY created_at DESC LIMIT 20"));
        return m;
    }
}

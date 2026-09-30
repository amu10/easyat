package io.github.easyat.example.caller;

import io.github.easyat.annotation.EasyAtTransactional;
import io.github.easyat.core.AtContext;
import io.github.easyat.example.caller.dto.DeductResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 下单服务（全局事务发起方）。
 *
 * <p>{@code @EasyAtTransactional} 会开启一笔全局事务并把 XID 绑定到当前线程；随后的本地 DML （扣账户、写订单）与 undo
 * 日志都在同一个本地事务里。对库存服务的 Feign 调用发生在事务开启之后， 所以 XID 会随请求头传播过去；库存服务加入同一笔事务后，它的库存扣减也挂在这个 XID 下。
 *
 * <p>任意一个分支抛异常（这里是库存服务抛错被 Feign 转成 FeignException 抛上来）， {@code @EasyAtTransactional} 的切面都会回滚：本地
 * undo + 同一 XID 下所有 undo（含库存服务产生的） 一起被补偿，账户余额和库存都回到事务开始前。
 */
@Service
public class OrderService {
    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final JdbcTemplate jdbc;
    private final InventoryFeignClient inventory;

    public OrderService(JdbcTemplate jdbc, InventoryFeignClient inventory) {
        this.jdbc = jdbc;
        this.inventory = inventory;
    }

    @EasyAtTransactional(name = "place-order", timeout = 30000)
    @Transactional
    public String placeOrder(long userId, long itemId, int qty, boolean fail) {
        int unitPrice = 10; // 演示用固定单价
        // XID 传播观测点 ①：切面刚开启全局事务，XID 已绑定到当前线程
        log.info("[AT-caller] 全局事务已开启，xid={}", AtContext.xid());

        Integer balance =
                jdbc.queryForObject(
                        "SELECT balance FROM account WHERE id=?", Integer.class, userId);
        if (balance == null) throw new IllegalArgumentException("account not found: " + userId);
        int amount = qty * unitPrice;
        if (balance < amount) throw new IllegalStateException("insufficient balance: " + balance);

        // 1) 扣减付款方余额（本服务本地库）
        // AT 模式只接受「SET 列 = ?」的受限 SQL：不支持 balance-? 这类表达式，
        // 所以先读余额、在 Java 里算出新值，再整体写入（与 easy-at-example-local 写法一致）。
        jdbc.update("UPDATE account SET balance=balance - ? WHERE id=?", amount, userId);

        // 2) 记录订单（INSERT 必须显式携带主键 id，且所有值都必须是 ?）
        long orderId = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO orders(id,user_id,item_id,qty,amount,status) VALUES(?,?,?,?,?,?)",
                orderId,
                userId,
                itemId,
                qty,
                amount,
                "CREATED");

        // 3) 远程调用库存服务（XID 随 Feign 请求头自动传播）
        //    观测点 ②：这里的 xid 会被 EasyAtFeignInterceptor 写成 X-EasyAt-Xid 请求头；
        //    开启 Feign FULL 日志后可在 caller 控制台直接看到发出的头。
        log.info("[AT-caller] 准备调用 inventory-service，将传出 xid={}", AtContext.xid());
        DeductResult r = inventory.deduct(itemId, qty, fail);
        log.info("[AT-caller] inventory-service 返回 success={}", r.isSuccess());
        if (!r.isSuccess()) {
            throw new IllegalStateException("inventory deduct failed: " + r.getMessage());
        }

        // 4) 订单置为已支付：字面量 'PAID' 同样不被允许，必须作为 ? 参数传入
        jdbc.update("UPDATE orders SET status=? WHERE id=?", "PAID", orderId);
        return "order " + orderId + " placed, amount=" + amount;
    }
}

package io.github.easyat.example.callee;

import io.github.easyat.core.AtContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存服务（全局事务参与方）。
 *
 * <p>注意：这里<b>没有</b>加 {@code @EasyAtTransactional}。XID 由入口的 {@code AtXidFilter}
 * 从 Feign 请求头里取出并 {@code manager.join(xid)} 到当前线程；本服务的本地事务（@Transactional）
 * 产生的 undo 日志会自动挂到这个被 join 进来的 XID 下。caller 回滚时会统一补偿这些 undo。
 *
 * <p>库存不足或 {@code fail=true} 时直接抛异常：本地 @Transactional 回滚本次 DML，异常经 Feign
 * 变成 FeignException 抛回 caller，进而触发整笔全局事务回滚。
 */
@Service
public class InventoryService {
    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final JdbcTemplate jdbc;

    public InventoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void deduct(long itemId, int qty, boolean fail) {
        // XID 传播观测点 ③：AtXidFilter 已从请求头 X-EasyAt-Xid 取出 xid 并 manager.join(xid)。
        // 打印出非 null 的 xid == XID 确实跨服务传过来了且 join 成功；
        // 若打印 null，说明请求头没到（Feign 拦截器没生效，或 caller 侧压根没开事务）。
        log.info(
                "[AT-callee] 收到扣库存请求，joined xid={} (inAT={})",
                AtContext.xid(),
                AtContext.active());

        Integer stock =
                jdbc.queryForObject("SELECT stock FROM inventory WHERE item_id=?", Integer.class, itemId);
        if (stock == null) throw new IllegalArgumentException("item not found: " + itemId);
        if (stock < qty)
            throw new IllegalStateException("insufficient stock: " + stock + " < " + qty);

        // AT 模式只接受「SET 列 = ?」的受限 SQL：不支持 stock-? 这类表达式，
        // 所以先读库存、算出新值再整体写入；item_id 是主键，满足「按主键单行」要求。
        jdbc.update("UPDATE inventory SET stock=? WHERE item_id=?", stock - qty, itemId);

        if (fail) throw new IllegalStateException("simulated inventory failure");
    }
}

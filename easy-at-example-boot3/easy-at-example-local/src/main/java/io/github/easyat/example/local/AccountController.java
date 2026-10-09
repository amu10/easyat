package io.github.easyat.example.local;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/**
 * 本地 AT 示例的 HTTP 入口：演示一笔只涉及本服务数据库的转账事务（账户 A 扣款、账户 B 加款）。
 *
 * <p>除了触发转账（{@link #transfer}），本控制器还提供几个只读查询接口，方便直观查看
 * 全局事务状态（{@code easy_at_global}）与已落库的 undo 日志（{@code easy_at_undo_log}），
 * 验证 AT 事务最终是提交还是回滚。
 */
@RestController
@RequestMapping("/demo")
public class AccountController {
    private final TransferService transfers;
    private final JdbcTemplate jdbc;

    public AccountController(TransferService transfers, JdbcTemplate jdbc) {
        this.transfers = transfers;
        this.jdbc = jdbc;
    }

    /** 查询账户表当前余额，转账前后各看一眼即可验证结果。 */
    @GetMapping("/accounts")
    public List<Map<String, Object>> accounts() {
        return jdbc.queryForList("SELECT id,balance FROM account ORDER BY id");
    }

    /** 查看示例产生的全局事务，便于确认事务最终提交或回滚到哪个状态。 */
    @GetMapping("/transactions")
    public List<Map<String, Object>> transactions() {
        return jdbc.queryForList(
                "SELECT xid,name,status,retry_count,version,created_at,updated_at "
                        + "FROM easy_at_global ORDER BY created_at DESC");
    }

    /** 查看已经提交的 undo log。BLOB 类型的 before/after image 未直接返回，避免 HTTP 响应不可读。 */
    @GetMapping("/undo-logs")
    public List<Map<String, Object>> undoLogs() {
        return jdbc.queryForList(
                "SELECT undo_id,xid,resource_id,table_name,pk_name,pk_value,"
                        + "rollback_sql,status,created_at,updated_at "
                        + "FROM easy_at_undo_log ORDER BY created_at DESC");
    }

    /**
     * 触发一次转账，并在完成后直接返回账户余额快照。
     *
     * <p>{@code fail=true} 时 {@link TransferService#transfer} 会在扣款后抛异常，
     * 从而演示整笔 AT 事务回滚、余额恢复原状的效果。
     *
     * @param from 付款方账户 id
     * @param to 收款方账户 id
     * @param amount 转账金额
     * @param fail 是否模拟扣款后失败（用于演示回滚）
     * @return 转账结束后的账户余额列表
     */
    @PostMapping("/transfer")
    public List<Map<String, Object>> transfer(
            @RequestParam("from") long from,
            @RequestParam("to") long to,
            @RequestParam("amount") int amount,
            @RequestParam(name = "fail", defaultValue = "false") boolean fail) {
        transfers.transfer(from, to, amount, fail);
        return accounts();
    }
}

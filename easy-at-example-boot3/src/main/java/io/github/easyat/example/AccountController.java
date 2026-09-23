package io.github.easyat.example;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/demo")
public class AccountController {
    private final TransferService transfers;
    private final JdbcTemplate jdbc;

    public AccountController(TransferService transfers, JdbcTemplate jdbc) {
        this.transfers = transfers;
        this.jdbc = jdbc;
    }

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

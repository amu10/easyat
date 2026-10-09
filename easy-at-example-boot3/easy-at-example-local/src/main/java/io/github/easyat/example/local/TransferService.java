package io.github.easyat.example.local;

import io.github.easyat.annotation.EasyAtTransactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地转账业务（AT 全局事务发起方）。
 *
 * <p>整个 {@link #transfer} 方法被 {@code @EasyAtTransactional} 包裹：进入方法时 AT 会开启一笔全局事务
 * 并把 XID 绑定到当前线程；方法内的两条 {@code UPDATE} 与它们产生的 undo 日志都在同一个本地事务里提交。
 * 一旦方法抛异常，{@code @EasyAtTransactional} 切面会回滚——本服务的 undo 被补偿、余额回到事务前状态。
 *
 * <p>注意 AT 对 SQL 形态的限制（详情见方法内注释）：只接受「{@code SET 列 = ?}」这类受限写法，
 * 不支持 {@code balance - ?} 这种表达式，所以需要先读、再算、再整体写回。
 */
@Service
public class TransferService {
    private final JdbcTemplate jdbc;

    public TransferService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 执行一次账户间转账，并在 {@code failAfterDebit=true} 时模拟扣款后失败以演示回滚。
     *
     * <p>方法被 {@code @EasyAtTransactional} + {@code @Transactional} 双重包裹：前者管全局事务，
     * 后者管本地数据库事务（undo 与业务 DML 同事务提交，这是 AT 正确性的前提）。
     *
     * @param fromId 付款方账户 id
     * @param toId 收款方账户 id
     * @param amount 转账金额（须为正）
     * @param failAfterDebit 是否在扣款成功后抛异常（演示整笔回滚）
     */
    @EasyAtTransactional(name = "account-transfer", timeout = 30000)
    @Transactional
    public void transfer(long fromId, long toId, int amount, boolean failAfterDebit) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        int fromBalance = balance(fromId);
        balance(toId); // validate target account exists
        if (fromBalance < amount) throw new IllegalArgumentException("insufficient balance");

        // 同列加减会保存 before image，undo 时直接写回旧值。
        jdbc.update("UPDATE account SET balance=balance-? WHERE id=?", amount, fromId);
        if (failAfterDebit) throw new IllegalStateException("simulated failure after debit");
        jdbc.update("UPDATE account SET balance=balance+? WHERE id=?", amount, toId);
    }

    /**
     * 查询指定账户的当前余额；账户不存在时抛异常（也顺带校验账户有效性）。
     *
     * @param id 账户 id
     * @return 当前余额
     * @throws IllegalArgumentException 当账户不存在时抛出
     */
    public int balance(long id) {
        Integer value =
                jdbc.queryForObject("SELECT balance FROM account WHERE id=?", Integer.class, id);
        if (value == null) throw new IllegalArgumentException("account not found: " + id);
        return value;
    }
}

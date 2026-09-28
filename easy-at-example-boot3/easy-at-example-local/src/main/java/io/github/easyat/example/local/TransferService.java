package io.github.easyat.example.local;

import io.github.easyat.annotation.EasyAtTransactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {
    private final JdbcTemplate jdbc;

    public TransferService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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

    public int balance(long id) {
        Integer value =
                jdbc.queryForObject("SELECT balance FROM account WHERE id=?", Integer.class, id);
        if (value == null) throw new IllegalArgumentException("account not found: " + id);
        return value;
    }
}

package io.github.easyat.example;

import io.github.easyat.annotation.EasyAtTransactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {
    private final JdbcTemplate jdbc;

    public TransferService(JdbcTemplate jdbc){this.jdbc=jdbc;}

    @EasyAtTransactional(name="account-transfer",timeout=30000)
    @Transactional
    public void transfer(long fromId,long toId,int amount,boolean failAfterDebit){
        if(amount<=0)throw new IllegalArgumentException("amount must be positive");
        int fromBalance=balance(fromId);
        int toBalance=balance(toId);
        if(fromBalance<amount)throw new IllegalArgumentException("insufficient balance");

        // easyAt 当前只接受 SET column=?、WHERE primary_key=? 的安全单行形式。
        jdbc.update("UPDATE account SET balance=? WHERE id=?",fromBalance-amount,fromId);
        if(failAfterDebit)throw new IllegalStateException("simulated failure after debit");
        jdbc.update("UPDATE account SET balance=? WHERE id=?",toBalance+amount,toId);
    }

    public int balance(long id){
        Integer value=jdbc.queryForObject("SELECT balance FROM account WHERE id=?",Integer.class,id);
        if(value==null)throw new IllegalArgumentException("account not found: "+id);
        return value;
    }
}

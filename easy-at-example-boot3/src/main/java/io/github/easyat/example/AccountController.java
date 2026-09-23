package io.github.easyat.example;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/demo")
public class AccountController {
    private final TransferService transfers;
    private final JdbcTemplate jdbc;

    public AccountController(TransferService transfers,JdbcTemplate jdbc){this.transfers=transfers;this.jdbc=jdbc;}

    @GetMapping("/accounts")
    public List<Map<String,Object>> accounts(){return jdbc.queryForList("SELECT id,balance FROM account ORDER BY id");}

    @PostMapping("/transfer")
    public List<Map<String,Object>> transfer(@RequestParam long from,@RequestParam long to,@RequestParam int amount,@RequestParam(defaultValue="false") boolean fail){
        transfers.transfer(from,to,amount,fail);
        return accounts();
    }
}

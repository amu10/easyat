package io.github.easyat.example.callee;

import io.github.easyat.example.callee.dto.DeductResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 库存服务的 HTTP 入口，供 caller 通过 Feign 调用。XID 由 AtXidFilter 在过滤器里 join。 */
@RestController
@RequestMapping("/inventory")
public class InventoryController {
    private final InventoryService service;

    public InventoryController(InventoryService service) {
        this.service = service;
    }

    @PostMapping("/deduct")
    public DeductResult deduct(
            @RequestParam("itemId") long itemId,
            @RequestParam("qty") int qty,
            @RequestParam("fail") boolean fail) {
        service.deduct(itemId, qty, fail);
        return new DeductResult(true, "deducted " + qty + " of item " + itemId);
    }
}

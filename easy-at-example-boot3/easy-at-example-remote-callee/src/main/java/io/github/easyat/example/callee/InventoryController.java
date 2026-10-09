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

    /**
     * 扣减库存的 HTTP 入口，供 caller 通过 Feign 调用。
     *
     * <p>实际扣减逻辑在 {@link InventoryService#deduct} 里；若扣减失败该方法会抛异常，
     * 异常经 Feign 传播回 caller，进而触发整笔全局事务回滚。只有成功时才返回结果对象。
     *
     * @param itemId 商品 id
     * @param qty 扣减数量
     * @param fail 是否模拟库存服务内部失败（演示跨服务回滚）
     * @return 扣减成功的结果载体
     */
    @PostMapping("/deduct")
    public DeductResult deduct(
            @RequestParam("itemId") long itemId,
            @RequestParam("qty") int qty,
            @RequestParam("fail") boolean fail) {
        service.deduct(itemId, qty, fail);
        return new DeductResult(true, "deducted " + qty + " of item " + itemId);
    }
}

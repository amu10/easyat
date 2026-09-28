package io.github.easyat.example.caller;

import io.github.easyat.example.caller.dto.DeductResult;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 通过 OpenFeign 调用库存服务（inventory-service）。
 *
 * <p>easyAt 的 {@code EasyAtFeignInterceptor} 是一个 Feign {@code RequestInterceptor}， 只要当前线程处在 AT
 * 全局事务中（AtContext 有 XID），它就会自动把 XID 等头注入到这次 HTTP 请求里。库存服务侧的 {@code AtXidFilter} 收到后调用 {@code
 * manager.join(xid)} 加入同一笔 全局事务，因此库存服务里的本地事务会挂到 caller 发起的同一个 XID 下。
 */
@FeignClient("inventory-service")
public interface InventoryFeignClient {

    @PostMapping("/inventory/deduct")
    DeductResult deduct(
            @RequestParam("itemId") long itemId,
            @RequestParam("qty") int qty,
            @RequestParam("fail") boolean fail);
}

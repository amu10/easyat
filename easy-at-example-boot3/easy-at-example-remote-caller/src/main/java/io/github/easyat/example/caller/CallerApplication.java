package io.github.easyat.example.caller;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * 远程示例的「发起方」（下单服务）启动类。
 *
 * <p>它开启全局事务（{@code @EasyAtTransactional} 在 {@link OrderService} 上），并通过 OpenFeign
 * 把 XID 随请求头传播给库存服务（callee）。{@code @EnableFeignClients} 启用 Feign，
 * 使 {@link InventoryFeignClient} 能自动注入并被 {@code EasyAtFeignInterceptor} 拦截、注入 XID 头。
 * 该服务通常作为业务入口接收下单请求（{@code /order/place}）。
 */
@SpringBootApplication
@EnableFeignClients(basePackages = "io.github.easyat.example.caller")
public class CallerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CallerApplication.class, args);
    }
}

package io.github.easyat.example.callee;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 远程示例的「被调用方」（库存服务）启动类。
 *
 * <p>它本身<b>不开启</b>全局事务，而是被动加入由 caller（下单服务）发起的全局事务：
 * caller 发来的 HTTP 请求经 {@code AtXidFilter} 取出 XID 并 {@code manager.join(xid)}，
 * 本服务产生的本地事务与 undo 因此挂到 caller 的 XID 下，随 caller 一起提交或回滚。
 * 默认通过服务发现以 {@code inventory-service} 为名注册，供 caller 的 Feign 客户端调用。
 */
@SpringBootApplication
public class CalleeApplication {
    public static void main(String[] args) {
        SpringApplication.run(CalleeApplication.class, args);
    }
}

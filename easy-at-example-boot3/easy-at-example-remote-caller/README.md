# easy-at-example-remote-caller（order-service）

跨服务全局事务的**发起方**。通过 OpenFeign 调用 `inventory-service`，与本例的 callee 一起加入同一笔 AT 全局事务。

- 端口：18081
- 服务名：`order-service`
- 依赖：starter + web + `spring-cloud-starter-openfeign` + `spring-cloud-starter-alibaba-nacos-discovery` + `spring-cloud-starter-loadbalancer`
- 业务表：`account`、`orders`；协调表：`easy_at_*`
- 关键类：`CallerApplication`（`@EnableFeignClients`）、`InventoryFeignClient`、`OrderService`（`@EasyAtTransactional`）、`OrderController`

前置：Nacos 可访问 `192.168.110.237:8848`。运行：

```shell
mvn spring-boot:run
```

调试：

```shell
# 成功（提交）
curl -X POST "http://localhost:18081/order?userId=1&itemId=1001&qty=2"
# 失败（回滚，库存服务抛异常）
curl -X POST "http://localhost:18081/order?userId=1&itemId=1001&qty=2&fail=true"
curl http://localhost:18081/order/state
```

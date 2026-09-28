# easy-at-example-remote-callee（inventory-service）

跨服务全局事务的**参与方**。从 Feign 请求头取出 XID 并 `join` 进 caller 发起的同一笔事务，本地 `@Transactional` 产生的 undo 自动挂到该 XID 下。

- 端口：18082
- 服务名：`inventory-service`
- 依赖：starter + web + `spring-cloud-starter-alibaba-nacos-discovery`（本模块不发起 Feign 调用，无需 openfeign）
- 业务表：`inventory`；协调表：`easy_at_*`
- 关键类：`CalleeApplication`、`InventoryService`（`@Transactional`，**不加** `@EasyAtTransactional`）、`InventoryController`

前置：Nacos 可访问 `192.168.110.237:8848`。运行（与 caller 同时启动）：

```shell
mvn spring-boot:run
```

被 caller 通过 `POST /inventory/deduct?itemId=1001&qty=2&fail=false` 调用；`fail=true` 或库存不足时抛异常触发全局回滚。

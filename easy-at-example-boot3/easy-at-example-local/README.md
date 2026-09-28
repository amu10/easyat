# easy-at-example-local

单服务本地 AT 事务示例。一个 Spring Boot 应用内完成「账户 A → 账户 B」转账，`@EasyAtTransactional` 在单进程内提交或回滚。

- 端口：18080
- 依赖：`easy-at-spring-boot3-starter`、`spring-boot-starter-web`、`mysql-connector-j`
- 业务表：`account`；协调表：`easy_at_*`
- 接口：`/demo/accounts`、`/demo/transfer`、`/demo/transactions`、`/demo/undo-logs`

运行：

```shell
mvn spring-boot:run
```

调试：

```shell
curl -X POST "http://localhost:18080/demo/transfer?from=1&to=2&amount=100"
curl -X POST "http://localhost:18080/demo/transfer?from=1&to=2&amount=100&fail=true"
```

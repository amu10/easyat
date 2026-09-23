# easyAt Spring Boot 3 示例

该示例使用 H2 和 File Repository，演示同一数据库中的转账提交与异常回滚。

## 启动

在项目根目录执行：

```shell
mvn -pl easy-at-example-boot3 -am spring-boot:run
```

若 Maven 将 `spring-boot:run` 错误应用到聚合模块，可先构建，再进入示例目录启动：

```shell
mvn install -DskipTests
cd easy-at-example-boot3
mvn spring-boot:run
```

## 验证

初始余额：

```shell
curl http://localhost:18080/demo/accounts
```

成功转账 100：

```shell
curl -X POST "http://localhost:18080/demo/transfer?from=1&to=2&amount=100"
```

模拟扣款后的异常：

```shell
curl -X POST "http://localhost:18080/demo/transfer?from=1&to=2&amount=100&fail=true"
```

最后再次查询余额。失败请求不会改变余额，因为 `@Transactional` 回滚本地事务，easyAt 同时收敛全局事务状态：

```shell
curl http://localhost:18080/demo/accounts
```

生产项目应将 `storage.type` 和 `lock.type` 改为 `jdbc` 或 `redis`，并启用 `easy-at.production=true` 和 HMAC 密钥；本示例的 H2/File 配置仅用于本地体验。

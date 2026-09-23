# easyAt Spring Boot 3 示例

该示例使用本机 MySQL 和 JDBC Repository/Lock，演示同一数据库中的转账提交与异常回滚。

## 准备数据库

示例默认连接 `localhost:3306/easyat01`，用户名和密码均为 `root`。

先创建数据库：

```sql
CREATE DATABASE IF NOT EXISTS easyat01
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;
```

应用启动时会自动执行 `src/main/resources/schema.sql`。该脚本会重建示例表，因此只应用于本地演示环境。

## 启动

先在项目根目录安装各模块，再进入示例目录启动：

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

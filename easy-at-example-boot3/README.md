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

应用启动时会自动执行 `src/main/resources/schema.sql`。该脚本使用幂等建表和 `INSERT IGNORE` 初始化账户，重启应用不会删除已经产生的事务和 Undo Log；该配置只用于本地演示环境。

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

成功请求完成后可以查看全局事务和 Undo Log：

```shell
curl http://localhost:18080/demo/transactions
curl http://localhost:18080/demo/undo-logs
```

也可以直接在 MySQL 中查询：

```sql
SELECT xid, name, status, created_at, updated_at
FROM easy_at_global
ORDER BY created_at DESC;

SELECT undo_id, xid, table_name, pk_name, pk_value, rollback_sql, status,
       created_at, updated_at
FROM easy_at_undo_log
ORDER BY created_at DESC;
```

一次成功转账包含两条 `UPDATE account ...`，因此应产生两条 Undo Log。`before_image` 和 `after_image` 是框架编码后的 BLOB，示例接口只返回便于阅读的元数据和回滚 SQL。

模拟扣款后的异常：

```shell
curl -X POST "http://localhost:18080/demo/transfer?from=1&to=2&amount=100&fail=true"
```

最后再次查询余额。失败请求不会改变余额，因为 `@Transactional` 回滚本地事务，easyAt 同时收敛全局事务状态：

```shell
curl http://localhost:18080/demo/accounts
```

失败请求中的业务 DML 与 Undo Log 使用同一条 JDBC Connection 和同一个本地事务，所以两者会一起回滚。也就是说，只执行 `fail=true` 的示例时，`easy_at_undo_log` 没有新增记录是预期行为；可以在 `easy_at_global` 中看到对应事务已进入 `ROLLED_BACK` 状态。

生产项目应使用 JDBC 或 Redis 作为共享存储和锁，并启用 `easy-at.production=true` 和 HMAC 密钥。本示例使用本机 MySQL，仅用于本地体验。

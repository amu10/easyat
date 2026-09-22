# easyAt

easyAt 是一个不需要独立协调服务器、嵌入 Spring Boot 应用运行的轻量级 AT 事务框架实验项目。

## 当前状态

`0.1.0-SNAPSHOT` 已实现全局事务状态机、undo log 模型、File/JDBC 事务存储、JDBC DataSource 自动代理、before/after image、带租约的 JDBC 全局行锁、脏写校验、JDBC undo 执行器、超时恢复调度、Spring AOP 事务入口，以及 Spring Boot 2/3 Starter。

## AT 执行链路

```text
@EasyAtTransactional
       │
       ▼
AtDataSource 拦截 JDBC PreparedStatement
       │
       ├─ 使用 JSqlParser 解析 INSERT / UPDATE / DELETE
       ├─ 查询 before image
       ├─ 获取 resource + table + primary-key 全局锁
       ├─ 持久化 undo log
       ├─ 执行原始 DML
       └─ 查询并持久化 after image

成功：标记 COMMITTED，释放全局锁
失败：逆序执行 undo SQL，释放全局锁
```

## SQL 支持范围

首版以正确性优先，只自动处理以下形式的单行 DML：

```sql
INSERT INTO account (id, balance) VALUES (?, ?)
UPDATE account SET balance=? WHERE id=?
DELETE FROM account WHERE id=?
```

表必须有单列主键（通过 JDBC 元数据识别，不依赖 `id` 命名），`UPDATE` 和 `DELETE` 的 `WHERE` 条件必须精确匹配主键。多表 DML、批量更新、子查询、函数表达式、存储过程、DDL、无主键表会在业务 SQL 执行前抛出 `UnsupportedAtSqlException`，避免生成不可靠的 undo log。

## 跨服务 AT 协调

跨服务 AT 必须使用**共享的事务 Repository 和全局锁实现**（JDBC 或 Redis）。`AtDataSource` 在首个 DML 前经 `DefaultBranchRegistrar` 注册分支（`resourceId`、服务名、回调地址、顺序）。协调端点由 Starter 自动暴露：

- `POST /_easy-at/v1/branches`：注册分支
- `POST /_easy-at/v1/branches/{branchId}/commit`
- `POST /_easy-at/v1/branches/{branchId}/rollback`

三者均幂等；`BranchRetryScheduler` 扫描待处理分支并指数退避重试。

传播客户端：

- `RestTemplate`：`AtRestTemplateInterceptor` + `EasyAtRestTemplateCustomizer`（Starter 自动注册）。
- OpenFeign：`EasyAtFeignInterceptor`（`RequestInterceptor`）。
- `WebClient`：因离线仓库暂缺 `spring-webflux`，`ExchangeFilterFunction` 传播待后续补充。

Header 安全：所有跨服务请求带 `X-EasyAt-Xid`/`X-EasyAt-Deadline`/`X-EasyAt-Source`/`X-EasyAt-Signature`，由 `HmacSigner` 签名并做恒定时间校验、超时与重放保护；生产模式缺少 HMAC 密钥时启动失败。File Repository 仅适用于单实例或共享磁盘验证，不适用于多主机生产集群。

## JDBC 集群模式

在每个参与服务使用相同的业务数据库（或同一个专用协调数据库）执行对应脚本，然后启用 JDBC 存储和锁：

```yaml
easy-at:
  storage:
    type: jdbc
  lock:
    type: jdbc
```

MySQL 脚本位于 `easy-at-jdbc/src/main/resources/db/mysql/easy-at.sql`，PostgreSQL 脚本位于 `easy-at-jdbc/src/main/resources/db/postgresql/easy-at.sql`。JDBC 锁通过 `(resource_id, table_name, pk_value)` 唯一键争用，默认租约为 30 秒；事务结束时按 XID 释放。Starter 同时启动每 5 秒一次、每批最多 100 笔的本地恢复扫描器，超时 `ACTIVE` 和待重试的 `ROLLBACK_FAILED` 会被回滚。

JDBC Repository 已启用 connection-bound undo writer：经 `AtDataSource` 执行的 DML 在关闭自动提交或由 Spring `@Transactional` 管理时，会在**同一条 JDBC Connection**中写入 before/after image，因此本地事务回滚会同时撤销业务 DML 与 undo log。自动提交模式仍无法提供“DML 与 undo log 同时提交”的崩溃原子性，生产业务应使用 Spring `@Transactional`。

## 使用事务注解

```java
@EasyAtTransactional(name = "create-order")
public void createOrder() {
    orderMapper.insert(...);
    accountMapper.decrease(...);
}
```

## 模块

- `easy-at-core`：事务状态机、上下文、undo log 与 SPI
- `easy-at-storage-file`：文件事务日志
- `easy-at-jdbc`：JDBC undo 执行基础设施
- `easy-at-storage-redis`：Redis 事务日志与全局锁
- `easy-at-spring`：Spring AOP 集成
- `easy-at-spring-boot2-starter`：Spring Boot 2.7 Starter
- `easy-at-spring-boot3-starter`：Spring Boot 3.x Starter

## 构建

```shell
mvn clean verify
```

## License

Apache License 2.0

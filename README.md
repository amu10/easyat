# easyAt

easyAt 是一个不需要独立协调服务器、嵌入 Spring Boot 应用运行的轻量级 AT 事务框架实验项目。

> 📖 想读懂源码？先看 **[CODE_GUIDE.md](./CODE_GUIDE.md)** —— 它按一次事务的生命周期，串联了各模块核心类的职责与关键方法。
> 🚀 想直接上手？看 **[USAGE.md](./USAGE.md)** —— 依赖、配置、单服务/跨服务完整示例、管理运维与排查。

## 当前状态

`0.1.0` 已实现全局事务状态机（CAS + 版本）、undo log 模型、File/JDBC/**Redis** 事务存储、JDBC DataSource 自动代理、before/after image、带租约的 JDBC/Redis 全局行锁、脏写校验、JDBC undo 执行器、多实例恢复租约、分支注册与跨服务协调端点、HMAC 签名传播（RestTemplate/Feign/WebClient）、JSON undo 编解码（版本化 + `UndoDataEncryptor`/`UndoDataMasker` SPI 自动装配）、管理 API 与 Micrometer 指标，以及 Spring Boot 2/3 Starter。

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
- `WebClient`：`WebClientPropagator` 通过反射注入 `ExchangeFilterFunction`（仅当 `spring-webflux` 在 classpath 时自动激活，不引入编译期依赖）。

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

### JDBC 历史清理

历史清理默认关闭。确认保留策略后可启用渐进式清理：

```yaml
easy-at:
  cleanup:
    enabled: true
    interval: 1m
    batch-size: 500
    committed-retention: 7d
    rolled-back-retention: 30d
    expired-lock-retention: 10m
```

每轮最多分别清理 `batch-size` 条 `COMMITTED` 和 `ROLLED_BACK` 事务，并按 `easy_at_undo_log` → `easy_at_branch` → `easy_at_global` 的顺序在短事务内删除。`ACTIVE`、`COMMITTING`、`ROLLING_BACK`、`ROLLBACK_FAILED`、`DIRTY_WRITE` 和 `MANUAL_INTERVENTION` 不会自动清理。过期锁会在租约到期并超过 `expired-lock-retention` 后分批删除。

已有 MySQL 数据库建议补充清理索引（新建库使用最新建表脚本时已包含）：

```sql
CREATE INDEX idx_easy_at_global_cleanup
  ON easy_at_global(status, updated_at, xid);
CREATE INDEX idx_easy_at_lock_expired
  ON easy_at_lock(lease_until);
```

## Redis 存储

共享 Redis 时启用：

```yaml
easy-at:
  storage:
    type: redis
  lock:
    type: redis
  redis:
    host: localhost
    port: 6379
```

`easy-at-storage-redis` 提供 `RedisAtRepository`/`RedisBranchRepository`/`RedisGlobalLockManager`，状态 CAS、token/租约锁、恢复队列均通过 Lua 脚本保证原子性。在 `easy-at` 配置下填入 `host`/`port`/密码/`database` 即可，Starter 自动构建 `JedisPool`。

事务存储与全局锁也可以独立选择。例如，事务和 undo log 持久化到 JDBC、全局锁使用 Redis：

```yaml
easy-at:
  storage:
    type: jdbc
  lock:
    type: redis
    wait-timeout: 1s
    lease: 30s
  redis:
    host: localhost
    port: 6379
```

`storage.type` 只决定 `AtRepository` 和 `BranchRepository`，`lock.type` 只决定 `GlobalLockManager`；任一项选择 Redis 时，Starter 都会创建并复用同一个 `JedisPool`。

## 运维：管理 API 与指标

管理端点（`easy-at.management.enabled=true` 时暴露，需 `easy-at.management.token` 鉴权）：

- `GET  /_easy-at/v1/transactions/{xid}`
- `GET  /_easy-at/v1/transactions?status=MANUAL_INTERVENTION`
- `POST /_easy-at/v1/transactions/{xid}/retry`
- `POST /_easy-at/v1/transactions/{xid}/rollback`
- `GET  /_easy-at/v1/ui`：内置只读管理控制台（按状态/XID 查询、人工重试/回滚）

返回的事务视图包含 `undo` 列表，敏感列经 `UndoDataMasker` 自动脱敏。Micrometer 暴露活跃事务、提交/回滚/回滚失败计数、锁冲突、恢复队列深度与恢复耗时，并通过 MDC 在日志中携带 `xid`/`resourceId`。

## 加解密与脱敏 SPI

`JacksonUndoDataCodec` 在 Starter 中自动装配为 `UndoDataCodec` Bean，并把容器里的 `UndoDataEncryptor` / `UndoDataMasker` 注入：

```java
@Bean UndoDataEncryptor myEncryptor(){ return new AesUndoDataEncryptor(keySpec); }
@Bean UndoDataMasker myMasker(){ return new ColumnMasker("ssn", "password"); }
```

加密在 undo 落库时按列进行（密文以 `enc` 标签写入 JSON），脱敏在管理 API 诊断输出时按列进行，敏感值不会以明文出现在日志或管理接口中。

## 使用事务注解

```java
@EasyAtTransactional(name = "create-order")
public void createOrder() {
    orderMapper.insert(...);
    accountMapper.decrease(...);
}
```

可运行的 Spring Boot 3 示例位于 [`easy-at-example-boot3`](./easy-at-example-boot3)，包含 H2 建表、成功转账和异常回滚接口。

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

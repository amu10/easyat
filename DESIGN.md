# easyAt 设计方案

> 版本：0.2 设计稿  
> 状态：阶段 A 已部分实施；本文将现有能力与待实现的生产能力明确区分。

## 1. 背景与目标

easyAt 是一个嵌入 Spring Boot 应用的 AT（Automatic Transaction）分布式事务框架。它的目标不是复刻 Seata 的完整平台，而是在不部署独立协调服务器的前提下，为常见的 Spring Boot 微服务提供可恢复、可观测、可扩展的 AT 能力。

设计原则：

- 协调器嵌入事务发起服务，不增加必须部署的 Server。
- 所有决定恢复结果的状态必须持久化；内存状态不作为事实来源。
- 不支持的 SQL 必须失败，不允许生成猜测性的 undo log。
- 单机开发可使用 File；多实例和跨服务生产部署必须使用 JDBC 或 Redis。
- 框架负责协调与恢复，业务仍需避免不可逆副作用。

## 2. 当前实现与目标范围

| 能力 | 当前 MVP | 生产目标 |
|---|---|---|
| 全局事务状态机 | 已实现 | 增加乐观版本、租约与审计 |
| `@EasyAtTransactional` | 已实现 | 支持传播行为与 Spring 事务排序 |
| Spring AOP | 已实现 | 与 `@Transactional` 协作 |
| DataSource 自动代理 | 已实现 | 支持配置、排除和多数据源 |
| before/after image | 已实现，回滚前校验 | 版本列与更细粒度冲突诊断 |
| undo SQL | 已实现，JDBC 表持久化并支持同连接写入 | 方言与大字段处理 |
| 全局行锁 | File/JDBC 租约锁 | Redis 租约锁、续约与恢复租约 |
| SQL 解析 | 受限正则 | JSqlParser + 方言适配 |
| XID Header | RestTemplate 类与服务端 Filter | 自动注册、Feign/WebClient/Dubbo/MQ |
| 事务存储 | File/JDBC | Redis、乐观版本与审计 |
| 恢复 | 定时扫描、重试与人工介入状态 | 多实例恢复租约、告警、管理 API |
| 跨服务协调 | 仅 XID 绑定 | 参与者注册、回滚端点、可靠投递 |

当前自动支持的 SQL 仅为带主键条件的单行 DML：

```sql
INSERT INTO account (id, balance) VALUES (?, ?)
UPDATE account SET balance=? WHERE id=?
DELETE FROM account WHERE id=?
```

多表 DML、批量、子查询、DDL、无主键表、存储过程及复杂表达式必须暂时拒绝。

## 3. 总体架构

```text
┌──────────────────── 发起服务 ────────────────────┐
│ @EasyAtTransactional                              │
│   ├─ TransactionManager                            │
│   ├─ AtDataSource                                  │
│   │   ├─ SQL Parser / Dialect                      │
│   │   ├─ Image Collector                           │
│   │   ├─ GlobalLockManager                         │
│   │   └─ UndoLogRepository                         │
│   ├─ XID Client Propagator                         │
│   └─ RecoveryScheduler                             │
└───────────────┬───────────────────────────────────┘
                │ X-EasyAt-Xid / deadline / signature
                ▼
┌──────────────────── 参与服务 ────────────────────┐
│ XID Server Filter → join(xid)                      │
│ AtDataSource → 本地 DML + undo + branch registration│
└───────────────────────────────────────────────────┘
                │
                ▼
       共享 Repository / Lock Store
       JDBC 表或 Redis（生产环境）
```

协调器不需要独立进程。任一拥有未完成事务的服务实例都可以通过租约取得恢复权并继续处理事务；实际生产建议由发起服务优先恢复，其他实例作为接管者。

## 4. 事务模型与状态机

### 4.1 全局事务

```text
ACTIVE
  ├─ commit → COMMITTING → COMMITTED
  ├─ error / timeout → ROLLING_BACK → ROLLED_BACK
  └─ 回滚失败 → ROLLBACK_FAILED → 重试 / MANUAL_INTERVENTION
```

全局状态迁移必须采用比较并设置（CAS）：

```text
UPDATE easy_at_global
SET status = :next, version = version + 1
WHERE xid = :xid AND status = :expected AND version = :version
```

这避免了多个恢复实例同时执行回滚。

### 4.2 分支事务

每个 DataSource 上的一次受代理 DML 都是一个 AT 分支。分支状态建议为：

```text
REGISTERED → EXECUTED → COMMITTED
                    └→ ROLLING_BACK → ROLLED_BACK / ROLLBACK_FAILED
```

`undo log` 先于原始 DML 持久化。DML 成功后写入 after image；全局回滚时按分支执行顺序的反序执行 undo。

## 5. 数据模型

### 5.1 JDBC Repository 表

```sql
CREATE TABLE easy_at_global (
  xid               VARCHAR(128) PRIMARY KEY,
  name              VARCHAR(256) NOT NULL,
  status            VARCHAR(32) NOT NULL,
  timeout_at        TIMESTAMP NOT NULL,
  owner             VARCHAR(128),
  lease_until       TIMESTAMP,
  retry_count       INT NOT NULL DEFAULT 0,
  next_retry_at     TIMESTAMP,
  version           BIGINT NOT NULL DEFAULT 0,
  created_at        TIMESTAMP NOT NULL,
  updated_at        TIMESTAMP NOT NULL
);

CREATE TABLE easy_at_branch (
  branch_id         VARCHAR(128) PRIMARY KEY,
  xid               VARCHAR(128) NOT NULL,
  resource_id       VARCHAR(128) NOT NULL,
  status            VARCHAR(32) NOT NULL,
  service_name      VARCHAR(128),
  created_at        TIMESTAMP NOT NULL,
  updated_at        TIMESTAMP NOT NULL,
  INDEX idx_branch_xid (xid)
);

CREATE TABLE easy_at_undo_log (
  undo_id           VARCHAR(128) PRIMARY KEY,
  branch_id         VARCHAR(128) NOT NULL,
  xid               VARCHAR(128) NOT NULL,
  table_name        VARCHAR(128) NOT NULL,
  pk_name           VARCHAR(128) NOT NULL,
  pk_value          VARCHAR(512) NOT NULL,
  rollback_sql      TEXT NOT NULL,
  rollback_params   BLOB NOT NULL,
  before_image      BLOB,
  after_image       BLOB,
  status            VARCHAR(32) NOT NULL,
  created_at        TIMESTAMP NOT NULL,
  updated_at        TIMESTAMP NOT NULL,
  INDEX idx_undo_xid (xid)
);

CREATE TABLE easy_at_lock (
  resource_id       VARCHAR(128) NOT NULL,
  table_name        VARCHAR(128) NOT NULL,
  pk_value          VARCHAR(512) NOT NULL,
  xid               VARCHAR(128) NOT NULL,
  lease_until       TIMESTAMP NOT NULL,
  created_at        TIMESTAMP NOT NULL,
  PRIMARY KEY(resource_id, table_name, pk_value)
);
```

`before_image`、`after_image` 和参数使用可版本化的 JSON 或 CBOR，而不是 Java 原生序列化。字段需要支持脱敏和加密扩展。

### 5.2 Redis Repository

```text
easy-at:global:{xid}                Hash
easy-at:branch:{branchId}           Hash
easy-at:undo:{branchId}:{undoId}    Hash / JSON
easy-at:recovery                    Sorted Set，score=nextRetryAt
easy-at:lock:{resource}:{table}:{pk} String，value=xid，带 TTL
```

状态更新、获取锁、延长锁租约和加入恢复队列必须通过 Lua 脚本实现原子性。

## 6. DataSource 自动代理

### 6.1 Spring 接入

`AtDataSourceBeanPostProcessor` 在每个 `DataSource` Bean 初始化后将其包装为 `AtDataSource`。资源 ID 默认是 Bean 名称，可以由配置覆盖。

```yaml
easy-at:
  resources:
    orderDataSource:
      resource-id: order-db
      enabled: true
    reportingDataSource:
      enabled: false
```

代理范围：

- `Connection.prepareStatement(sql)`
- `PreparedStatement#setXxx(index, value)` 参数记录
- `execute`、`executeUpdate`、`executeLargeUpdate`
- 后续增加 `addBatch` / `executeBatch`

代理必须绕过框架自身的 undo 执行；使用 `AtContext.undoing()` 防止回滚 SQL 产生新的 undo log。

### 6.2 与 Spring 本地事务协作

推荐顺序：

```text
EasyAt 事务切面（外层）
  └─ Spring @Transactional（内层）
      └─ 业务 DML + 本地 undo log
```

JDBC Repository 模式下，分支状态、undo log 和业务 DML 必须处于同一个本地数据库事务中。实现方式：通过 `TransactionSynchronizationManager` 获取事务绑定连接，使用同一连接插入 undo log；事务提交后再写 after image 或通过事务同步回调持久化。

若不存在 Spring 本地事务，框架应拒绝生产模式运行，或自动创建一个本地事务包裹“undo + DML”。

## 7. SQL 解析、before/after image 与回滚

### 7.1 解析器

MVP 的正则解析仅用于受限 SQL 验证。生产实现改用 JSqlParser，并由数据库方言层校验：

```java
interface AtSqlDialect {
    ParsedDml parse(String sql);
    String buildBeforeImageSql(ParsedDml dml);
    String buildAfterImageSql(ParsedDml dml);
    UndoPlan buildUndoPlan(RowImage before, ParsedDml dml);
}
```

首批方言：MySQL 8、PostgreSQL 14+。后续增加 MariaDB、Oracle、SQL Server。

### 7.2 UPDATE

```text
1. 解析表、SET 列、主键 WHERE 条件
2. SELECT 被更新行，得到 before image
3. 获取主键锁
4. 生成反向 UPDATE 并写入 undo log
5. 执行业务 UPDATE
6. SELECT 同一行，写入 after image
```

回滚前必须检查当前行是否仍等于 after image：

```text
当前行 == after image → 允许执行 undo
当前行 != after image → 标记 DIRTY_WRITE，拒绝覆盖，人工处理
```

### 7.3 DELETE

before image 为完整行；undo 是按列生成的 `INSERT`。回滚前确认目标主键不存在；若存在且内容不同，标记冲突。

### 7.4 INSERT

after image 为新插入行；undo 是按主键 `DELETE`。首版要求 INSERT 显式携带主键。后续通过 `RETURN_GENERATED_KEYS` 支持自增主键。

### 7.5 不支持 SQL 的处理

默认 `strict-sql=true`。无法安全解析或确定主键时直接抛 `UnsupportedAtSqlException`，并禁止业务 DML 执行。禁止降级为“没有 undo log 的普通写操作”。

## 8. 全局行锁

锁键：

```text
{resourceId}:{tableName}:{primaryKeyValue}
```

获取逻辑：

1. 同一 XID 重入成功。
2. 无锁时原子创建，并设置租约。
3. 他人持锁且租约有效时，按配置短暂等待后返回 `GlobalLockConflictException`。
4. 租约过期时，恢复任务先检查全局事务状态，再安全接管或清理。
5. 全局提交或成功回滚后释放该 XID 的所有锁。

File 锁只用于单机测试。生产锁实现必须是 JDBC 唯一键插入或 Redis `SET key value NX PX` + token 校验删除。

## 9. 跨服务 XID 传播与协调

### 9.1 Header

```text
X-EasyAt-Xid: <xid>
X-EasyAt-Deadline: <epoch-millis>
X-EasyAt-Source: <application-name>
X-EasyAt-Signature: <HMAC>
```

客户端：

- `RestTemplate`：`ClientHttpRequestInterceptor`
- OpenFeign：`RequestInterceptor`
- WebClient：`ExchangeFilterFunction`
- Dubbo/gRPC：Attachment / Metadata
- MQ：消息 Header

服务端 Filter/Interceptor 负责验证签名、检查 deadline、绑定或加入 XID，并在请求结束后清理 ThreadLocal。

### 9.2 分支注册

参与服务执行首个 DML 前，向共享 Repository 注册分支：

```text
POST /_easy-at/v1/branches
{ xid, branchId, resourceId, service, callbackUrl }
```

发起者在事务失败时调用参与者：

```text
POST /_easy-at/v1/branches/{branchId}/rollback
POST /_easy-at/v1/branches/{branchId}/commit
```

请求必须幂等。若网络失败，发起者把任务写入恢复队列重试；参与者也可以依据共享 Repository 主动发现待回滚的分支。

### 9.3 存储前提

真正跨服务 AT 必须共享 JDBC/Redis Repository 和锁服务。File 只能验证单实例流程，不能作为多服务生产协调存储。

## 10. 恢复、超时与人工处理

`RecoveryScheduler` 每隔 `scan-interval`：

1. 用租约领取 `ACTIVE` 且超时、`ROLLBACK_FAILED`、部分分支失败的事务。
2. 根据状态继续回滚或完成提交确认。
3. 使用指数退避更新 `next_retry_at`。
4. 达到 `max-retries` 后置为 `MANUAL_INTERVENTION`。
5. 释放租约，不释放尚未收敛事务的全局锁。

```yaml
easy-at:
  recovery:
    enabled: true
    interval: 10s
    batch-size: 100
    max-retries: 20
    lease: 30s
```

需提供管理 API：

```text
GET  /_easy-at/v1/transactions/{xid}
GET  /_easy-at/v1/transactions?status=MANUAL_INTERVENTION
POST /_easy-at/v1/transactions/{xid}/retry
POST /_easy-at/v1/transactions/{xid}/rollback
```

管理 API 必须配置认证与授权，不能暴露到公网。

## 11. 配置、可观测性与安全

```yaml
easy-at:
  application-name: order-service
  storage:
    type: jdbc # file | jdbc | redis
  lock:
    type: jdbc # file | jdbc | redis
    wait-timeout: 3s
    lease: 30s
  sql:
    strict: true
    dialect: mysql
  transport:
    hmac-secret: ${EASY_AT_HMAC_SECRET}
  recovery:
    enabled: true
```

指标：活跃事务、提交数、回滚数、回滚失败数、锁冲突数、恢复队列深度、恢复耗时。日志和 Trace 中必须带 `xid`、`branchId`、`resourceId`。

敏感 undo 字段支持字段级脱敏、加密和日志屏蔽。HTTP 协调端点必须使用应用间认证，禁止信任任意传入 Header。

## 12. 测试策略

| 层级 | 覆盖内容 |
|---|---|
| 单元测试 | 状态机、SQL 解析、undo plan、CAS、锁重入 |
| H2 测试 | 当前已覆盖代理 UPDATE、image、undo 和锁释放 |
| Testcontainers | MySQL、PostgreSQL、Redis |
| 并发测试 | 同主键冲突、锁租约续期、恢复抢占 |
| 故障注入 | 写 undo 前后崩溃、DML 后崩溃、网络超时、重复回滚 |
| 跨服务测试 | 发起者/参与者、Header、回调失败、幂等重试 |

## 13. 实施路线图

### 阶段 A：单服务生产基础

1. 已完成：JDBC Repository、JDBC Lock、MySQL/PostgreSQL 建表脚本。
2. 已完成 JDBC 基础版：connection-bound undo writer 将 undo 与代理 DataSource 的业务 DML 写入同一 Connection；自动提交模式不提供崩溃原子性。
3. 已完成基础版：JSqlParser 结构化校验、数据库元数据主键识别及 MySQL/PostgreSQL 标准单行 DML；待抽取更完整的方言 SPI。
4. 已完成基础版：after image 校验与脏写拒绝；待增加冲突状态和管理 API。
5. 已完成基础版：RecoveryScheduler 与重试；待增加多实例恢复租约、续约和管理 API。

### 阶段 B：跨服务 AT

1. Redis Repository/Lock 与 Lua 原子脚本。
2. Branch 注册、嵌入式协调端点、可靠重试。
3. RestTemplate 自动注册、OpenFeign、WebClient。
4. Header HMAC、deadline、服务名校验。

### 阶段 C：工程化

1. Actuator、Micrometer、审计日志。
2. Testcontainers 和故障注入测试。
3. SQL 白名单、数据脱敏、加密 SPI。
4. 管理 UI 或只读查询服务。

## 14. 验收标准

单服务生产基础完成的最低标准：

- MySQL/PostgreSQL 上支持标准单行 INSERT/UPDATE/DELETE。
- 业务写入、undo log、分支状态具备本地原子性。
- 回滚前可检测 after image 脏写。
- 实例崩溃重启后能通过恢复任务自动回滚。
- 并发修改同一主键时不会覆盖其他事务结果。

跨服务验收标准：

- 两个独立 Spring Boot 实例能传递 XID 并注册分支。
- 发起方异常后能最终回滚两端数据。
- 网络超时、重复回调和服务重启不会造成重复补偿或遗漏补偿。

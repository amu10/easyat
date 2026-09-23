# easyAt 代码导读

> 本文件是「读懂代码」的索引：按一次分布式事务的生命周期，串联各模块的核心类与关键方法。
> 配合 `DESIGN.md`（设计）与 `PRODUCTION_GAPS.md`（生产就绪度）一起看。

## 0. 先建立心智模型

easyAt 是 **AT（Automatic Transaction）模式** 的嵌入式分布式事务框架。一句话概括它的工作方式：

> 业务 SQL 执行**之前**，先记录「改之前的行」（before image）并生成一条反向补偿 SQL（undo log）；
> 事务失败时，**逆序执行**这些 undo SQL 把数据改回去。

因此全框架的核心，围绕下面三个「事实来源」展开：

| 概念 | 是什么 | 存哪 |
|---|---|---|
| 全局事务（Global Tx） | 一次跨库/跨服务操作的 ID 与状态机 | `easy_at_global` 表 / Redis / File |
| undo 记录（UndoRecord） | 每条 DML 的补偿 SQL + before/after image | `easy_at_undo_log` 表 / Redis / File |
| 全局行锁（Global Lock） | 按「资源+表+主键」锁一行，防止并发脏写 | `easy_at_lock` 表 / Redis / File |

**一条铁律**：所有状态变更必须走 CAS（compare-and-set），多实例并发恢复时只有一个实例能改成功，从而不重复补偿。

## 1. 模块地图

| 模块 | 职责 | 关键类 |
|---|---|---|
| `easy-at-core` | 状态机、上下文、undo 模型、SPI 接口 | `AtTransactionManager`、`AtStatus`、`AtContext`、`AtRepository`、`UndoDataCodec`、`HmacSigner` |
| `easy-at-jdbc` | SQL 解析、image 采集、undo 生成/执行、JDBC 存储、锁与历史清理 | `AtDataSource`、`SqlUndoLogGenerator`、`JdbcUndoExecutor`、`JdbcAtRepository`、`JdbcGlobalLockManager`、`JdbcAtCleaner` |
| `easy-at-storage-file` | 单机文件存储 | `FileAtRepository`、`FileGlobalLockManager` |
| `easy-at-storage-redis` | Redis 存储与锁（Lua 原子） | `RedisAtRepository`、`RedisGlobalLockManager` |
| `easy-at-spring` | Spring AOP、传播、恢复、清理、协调、管理 | `EasyAtAspect`、`AtRecoveryScheduler`、`AtCleanupScheduler`、`BranchCoordinator`、`ManagementService` |
| `easy-at-spring-boot2/3-starter` | 自动装配、管理端点、Filter | `EasyAtAutoConfiguration`、`EasyAtManagementController`、`AtXidFilter` |

## 2. 事务生命周期（主链路）

一次 `@EasyAtTransactional` 方法调用，按顺序经过以下环节。每个环节都标注了「看哪个类的哪个方法」。

### ① 开启事务 — `EasyAtAspect.around()`

```
@Around("@annotation(EasyAtTransactional)")
```

- 若线程已在 AT 事务中（`AtContext.active()`）则直接放行（支持嵌套/传播）。
- 否则 `AtTransactionManager.begin(name, timeout)`：生成 XID、持久化 `ACTIVE` 行、`AtContext.bind(xid)`。

### ② 拦截 DML — `AtDataSource` + `SqlUndoLogGenerator`

`AtDataSource` 用 JDK 动态代理包装 `Connection` / `PreparedStatement`。拦截 `executeUpdate/execute/executeLargeUpdate`，且满足 `AtContext.active() && !AtContext.undoing()` 时介入：

1. `SqlUndoLogGenerator.capture()`：JSqlParser 解析 SQL → 查 before image → 抢全局锁 → 写 undo log。
2. 执行原始 DML。
3. 成功走 `after()`（回填 after image），失败走 `abort()`（丢弃 undo）。

**SQL 支持范围**：只接受带主键精确条件的单行 INSERT/UPDATE/DELETE，值必须是 `?` 占位符。其余一律抛 `UnsupportedAtSqlException`（见 `SqlUndoLogGenerator.reject()` / `unsupported()`）。

三种 DML 的 undo 逻辑（`SqlUndoLogGenerator.update/delete/insert`）：

| DML | before image | undo SQL |
|---|---|---|
| UPDATE | 被更新列旧值 | 反向 UPDATE（列改回旧值）|
| DELETE | 整行 | 按列 INSERT 回原行 |
| INSERT | null | 按主键 DELETE |

### ③ 提交 / 回滚 — `AtTransactionManager`

- **提交**：`commit()` 走 `ACTIVE → COMMITTING → COMMITTED` 两步 CAS，成功后释放锁。
- **回滚**：`rollback()` 进入 `ROLLING_BACK`，**逆序**逐条执行 undo；脏写转 `DIRTY_WRITE`，其他异常转 `ROLLBACK_FAILED` 并按指数退避排下次重试。

### ④ 脏写校验 — `JdbcUndoExecutor.assertNoDirtyWrite()`

执行 undo 前，把当前行与 after image 逐列比对。不一致就抛 `DirtyWriteException`，**拒绝覆盖并发修改**，交人工处理。这是 AT 模式「不覆盖别人数据」的底线。

### ⑤ 全局锁 — `JdbcGlobalLockManager.acquire()`

锁键 `{resource}:{table}:{pk}`，用 `easy_at_lock` 表主键唯一约束当原子冲突点。同 XID 重入、他人持锁等待/超时、租约过期安全接管（先查全局状态是否已收敛）。后台单线程每 `lease/3` 续租。

### ⑥ 状态机 — `AtStatus`

状态与合法迁移见 `DESIGN.md §4.1`。迁移规则硬编码在 `AtStatus` 的 `TRANSITIONS` 表，`canTransitionTo()` 校验。所有迁移经 `AtTransactionManager.transition()` → `AtRepository.transition()`（CAS：`WHERE status=? AND version=?`，成功 `version=version+1`）。

## 3. 跨服务协调

- **传播**：`AtRestTemplateInterceptor`（RestTemplate）、`EasyAtFeignInterceptor`（OpenFeign）、`WebClientPropagator`（WebClient，纯反射，classpath 无 webflux 时静默跳过）。
- **签名**：`HmacSigner` 对 `{xid, deadline, source}` 做 HMAC-SHA256，`verify()` 恒定时间比较 + deadline 防重放。
- **服务端**：`AtXidFilter` 校验签名、绑定 XID，请求结束清理 ThreadLocal。
- **分支**：`DefaultBranchRegistrar` 注册分支 → `BranchCoordinator` 协调提交/回滚（本地分支走 `AtTransactionManager`，远程分支 HTTP 回调 + 幂等重试）。

## 4. 恢复与人工介入

- `AtRecoveryScheduler`：定时扫描 `recoverable()` 事务，先抢恢复租约（`claimLease`），抢到才 `manager.recover()`。
- `AtTransactionManager.recover()`：重试耗尽转 `MANUAL_INTERVENTION`，否则自增重试 + 退避 + 再回滚。
- `ManagementService` + `EasyAtManagementController`：管理 API（查询/重试/回滚）+ 内置只读 UI（`/_easy-at/v1/ui`），敏感列经 `UndoDataMasker` 脱敏。

## 5. 编解码与安全 SPI

`JacksonUndoDataCodec`（实现 `UndoDataCodec` 接口）：

- **版本化 JSON**：`{"v":1,"cols":{...}}`，替代 Java 原生序列化，可读且向前兼容。
- **列级加密**：`UndoDataEncryptor` SPI，密文以 `{"@t":"enc","@v":base64}` 标签写入。
- **脱敏**：`UndoDataMasker` SPI，在 `toDiagnosticString()` 渲染管理输出时按列脱敏。
- **回退**：未知类型退回 Java 序列化（`@t=java`），旧数据可读——**注意这是安全面，见 `PRODUCTION_GAPS.md`**。

## 6. 存储三选一

| 存储 | 适用 | 关键点 |
|---|---|---|
| File | 单机开发 | `FileAtRepository` |
| JDBC | 多实例/跨服务生产 | `JdbcAtRepository`（CAS/租约用 SQL 实现）|
| Redis | 多实例/跨服务生产 | `RedisAtRepository`（CAS/锁/恢复队列用 Lua 原子）|

> 跨服务 AT 必须用 **JDBC 或 Redis** 共享存储；File 只做单实例验证。

## 7. 快速定位表

| 想了解 | 看这里 |
|---|---|
| 事务状态怎么流转 | `core/AtStatus.java`（TRANSITIONS 表）|
| CAS 到底长啥样 | `jdbc/JdbcAtRepository.java#transition()` |
| undo 怎么生成 | `jdbc/SqlUndoLogGenerator.java`（update/delete/insert）|
| 脏写怎么拦 | `jdbc/JdbcUndoExecutor.java#assertNoDirtyWrite()` |
| 全局锁怎么抢 | `jdbc/JdbcGlobalLockManager.java#acquire()` |
| 多实例怎么不重复恢复 | `spring/AtRecoveryScheduler.java#recoverSafely()` + `JdbcAtRepository#claimLease()` |
| XID 怎么跨服务传 | `spring/AtRestTemplateInterceptor.java` 等三个 interceptor |
| undo 怎么加密/脱敏 | `jdbc/JacksonUndoDataCodec.java#encodeValue()/toDiagnosticString()` |
| 自动装配了哪些 Bean | `boot2/3/EasyAtAutoConfiguration.java` |
| 管理接口有哪些 | `boot2/3/EasyAtManagementController.java` |

## 8. 用一次转账把所有代码串起来

示例业务方法同时标注：

```java
@EasyAtTransactional(name = "account-transfer", timeout = 30000)
@Transactional
public void transfer(...) {
    jdbc.update("UPDATE account SET balance=? WHERE id=?", ...);
    jdbc.update("UPDATE account SET balance=? WHERE id=?", ...);
}
```

这里存在两层事务，职责不同：

| 层次 | 注解 | 解决的问题 |
|---|---|---|
| easyAt 全局事务 | `@EasyAtTransactional` | 记录 XID、Undo Log、全局锁、跨服务协调和故障恢复 |
| Spring 本地事务 | `@Transactional` | 保证当前数据库连接中的业务 DML 与 Undo Log 一起提交或一起回滚 |

完整调用顺序如下：

```text
Controller
  └─ EasyAtAspect.around()
       ├─ manager.begin()
       │    ├─ INSERT easy_at_global，状态 ACTIVE
       │    └─ AtContext.bind(xid)
       └─ Spring TransactionInterceptor
            ├─ 开启本地 JDBC 事务
            ├─ 执行第一条 UPDATE
            │    └─ AtDataSource/SqlUndoLogGenerator
            │         ├─ 解析 SQL 和参数
            │         ├─ 查主键元数据
            │         ├─ 获取 easy_at_lock
            │         ├─ SELECT 旧数据，生成 before image
            │         ├─ INSERT easy_at_undo_log
            │         ├─ 执行业务 UPDATE
            │         └─ SELECT 新数据，写 after image
            ├─ 执行第二条 UPDATE（同样流程）
            └─ 提交本地事务
       ├─ manager.commit()
       │    └─ ACTIVE → COMMITTING → COMMITTED
       └─ 释放 XID 持有的全局锁
```

为什么 `fail=true` 后看不到新增 Undo Log？异常发生时 Spring 会先回滚本地事务，业务 UPDATE 和同连接写入的 Undo Log 一起回滚。之后 easyAt 把全局事务收敛到 `ROLLED_BACK`。这不是漏写，而是本地事务原子性的结果。

## 9. `AtDataSource` 到底代理了什么

`AtDataSourceBeanPostProcessor` 会在 Spring Bean 初始化后检查每个 `DataSource`：

1. 在 `datasource-exclude` 中的跳过；
2. `resources.<beanName>.enabled=false` 的跳过；
3. 其余包装为 `AtDataSource`；
4. 默认 `resourceId` 就是 DataSource Bean 名称，也可以显式覆盖。

`AtDataSource` 不自己实现数据库协议，它使用 JDK 动态代理逐层包裹：

```text
DataSource → Connection → PreparedStatement
```

只有同时满足以下条件时才捕获 SQL：

- 当前线程存在 XID；
- 当前不是执行 Undo 的线程；
- SQL 是受支持的 INSERT、UPDATE 或 DELETE；
- 操作的不是 `easy_at_*` 内部表。

内部表必须绕过捕获，否则“写 Undo Log”本身又会产生 Undo Log，最终无限递归耗尽连接池。

PreparedStatement 的 `setInt`、`setLong`、`setObject` 等参数会按下标保存在代理中，执行时交给 `SqlUndoLogGenerator`。因此目前要求业务 SQL使用 `?` 参数，不能把值直接拼进 SQL。

## 10. `SqlUndoLogGenerator` 的三种算法

### UPDATE

业务 SQL：

```sql
UPDATE account SET balance=? WHERE id=?
```

捕获过程：

1. 校验 `WHERE` 只有一个主键等值条件；
2. 获取 `(resourceId, account, id)` 全局锁；
3. 查询更新前的 `balance`；
4. 生成反向 SQL：

```sql
UPDATE account SET balance=? WHERE id=?
```

反向 SQL 外形相同，但第一个参数保存的是旧余额。

### DELETE

删除前读取整行，Undo SQL 是：

```sql
INSERT INTO account(id,balance,...) VALUES (?,?,...)
```

### INSERT

要求 INSERT 显式携带主键，Undo SQL 是：

```sql
DELETE FROM account WHERE id=?
```

自增主键但 SQL 中没有主键值的 INSERT 当前不支持，因为框架在执行前无法可靠构造锁键和补偿记录。

## 11. 四张 JDBC 表分别负责什么

### `easy_at_global`

一行代表一个全局事务。

- `xid`：全局唯一事务号；
- `status`：事务状态；
- `timeout_at`：超时时间；
- `version`：CAS 乐观锁版本；
- `owner/lease_until`：恢复任务的多实例租约；
- `retry_count/next_retry_at`：失败重试信息。

### `easy_at_undo_log`

一行代表一条业务 DML 的补偿记录。

- `resource_id`：应该去哪个 DataSource 回滚；
- `table_name/pk_name/pk_value`：目标行；
- `rollback_sql/rollback_params`：补偿 SQL 与参数；
- `before_image/after_image`：修改前后快照；
- `status`：该 Undo 是否已经执行。

### `easy_at_lock`

主键是 `(resource_id, table_name, pk_value)`。唯一键冲突就是全局锁冲突；`lease_until` 防止进程崩溃后永久死锁。

### `easy_at_branch`

记录跨服务参与者，包括服务名、资源、回调地址、执行顺序、状态和重试信息。发起方根据这些记录通知各参与服务提交或回滚。

## 12. 状态机怎么读

以 `AtStatus` 为准，不要在业务代码中直接随意修改状态：

```text
ACTIVE
  ├─ COMMITTING → COMMITTED
  └─ ROLLING_BACK
       ├─ 回滚成功 → ROLLED_BACK
       ├─ 回滚异常 → ROLLBACK_FAILED → 再次 ROLLING_BACK
       └─ 数据被别人改过 → DIRTY_WRITE

重试耗尽 → MANUAL_INTERVENTION
```

状态更新使用类似下面的 SQL：

```sql
UPDATE easy_at_global
SET status=?, version=version+1
WHERE xid=? AND status=? AND version=?
```

更新行数为 0 表示状态或版本已经被其他实例改变，本实例必须停止，而不是覆盖对方结果。

## 13. 回滚为什么还要比较 after image

假设 easyAt 把余额从 100 改成 80，之后另一个正常事务又把 80 改成 70。如果 easyAt 直接执行 Undo，把余额写回 100，就会覆盖别人的修改。

因此 `JdbcUndoExecutor` 先读取当前行：

```text
当前数据 == after image → 可以执行 Undo
当前数据 != after image → DIRTY_WRITE，停止自动回滚
```

`DIRTY_WRITE` 是保护状态，不应自动清理，也不应无限重试，需要人工判断正确数据。

## 14. 自动装配如何选择实现

`EasyAtAutoConfiguration` 根据配置创建 SPI 实现：

```text
storage.type=file  → FileAtRepository + FileBranchRepository
storage.type=jdbc  → JdbcAtRepository + JdbcBranchRepository
storage.type=redis → RedisAtRepository + RedisBranchRepository

lock.type=file     → FileGlobalLockManager
lock.type=jdbc     → JdbcGlobalLockManager
lock.type=redis    → RedisGlobalLockManager
```

Redis 连接配置独立放置：

```yaml
easy-at:
  storage:
    type: jdbc
  lock:
    type: redis
  redis:
    host: localhost
    port: 6379
```

`storage.type` 与 `lock.type` 是两个维度：前者决定事务/分支数据放哪里，后者决定行锁放哪里。

## 15. 恢复和历史清理不是一回事

### 恢复任务

`AtRecoveryScheduler` 处理仍未正常收敛的事务，例如超时 `ACTIVE`、`ROLLING_BACK`、到期重试的 `ROLLBACK_FAILED`。它们仍然可能需要 Undo Log，不能删除。

### 清理任务

`AtCleanupScheduler` 只处理已经终结且超过保留期的 `COMMITTED` 与 `ROLLED_BACK`：

```text
easy_at_undo_log → easy_at_branch → easy_at_global
```

每次删除有限批次，避免大事务。`JdbcAtCleaner` 同时分批删除超过保留期的过期锁。清理默认关闭，配置见：

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

## 16. 多数据源目前支持到什么程度

代码会代理多个 DataSource，并且 `JdbcUndoExecutor` 能根据 `resourceId` 路由回滚 SQL。但当前 `JdbcAtRepository` 只绑定一个协调 DataSource，读取 Undo Log 时也只查询这个数据源。

因此当前边界是：

- 单数据源：完整主路径可用；
- 多数据源但一次事务只操作一个库：基本可用；
- 一次事务同时修改多个数据库：Undo Log 分散读取和全局逆序尚未完整实现，不应直接用于生产。

不要把“所有 DataSource 都被代理”误解为“跨多个本地数据库的事务已经完整实现”。

## 17. 常见现象如何排查

### `easy_at_global` 有数据，但 `easy_at_undo_log` 没数据

依次检查：

1. 是否真的执行了受支持的 DML；
2. 方法是否经过 Spring 代理，避免同类内部直接调用；
3. DataSource 是否被 `AtDataSourceBeanPostProcessor` 包装；
4. 是否只执行了失败请求——本地回滚会同时撤销 Undo Log；
5. SQL 是否操作主键，并全部使用 `?` 参数；
6. 是否查错数据库。

示例提供：

```text
GET /demo/transactions
GET /demo/undo-logs
```

### 报 `Composite primary keys are not supported`

框架当前只支持单列主键。若表实际是单主键，检查连接当前 catalog 是否正确，以及是否存在多个数据库中的同名表。主键元数据查询已经限定当前 catalog。

### 报 `Unsupported AT SQL`

检查是否包含 JOIN、OR、IN、子查询、批量 SQL、表达式更新、非主键 WHERE 或直接拼接常量。当前实现宁可拒绝，也不会猜测补偿 SQL。

### 启动时报多个 `DataSource` 无法选择

JDBC Repository、分支仓库和 JDBC 锁目前需要一个主 DataSource。为协调库设置 `@Primary`；同时注意第 16 节描述的多数据源限制。

## 18. 推荐源码阅读顺序

第一次阅读不要从自动配置开始逐文件看，建议按以下顺序：

1. `AtStatus`：先理解状态；
2. `AtTransaction`、`UndoRecord`、`RowImage`：理解数据模型；
3. `AtTransactionManager`：理解 begin/commit/rollback/recover；
4. `EasyAtAspect`：理解框架何时调用 Manager；
5. `AtDataSource`：理解 JDBC 动态代理入口；
6. `SqlUndoLogGenerator`：理解 Undo 的生成算法；
7. `JdbcAtRepository`：理解数据如何落表与 CAS；
8. `JdbcUndoExecutor`：理解回滚和脏写保护；
9. `JdbcGlobalLockManager`：理解并发控制；
10. `AtRecoveryScheduler`、`AtCleanupScheduler`：理解后台任务；
11. `EasyAtAutoConfiguration`：最后看各组件如何组装。

阅读时可以在示例中依次给这些方法打断点：

```text
EasyAtAspect.around
AtTransactionManager.begin
SqlUndoLogGenerator.capture
SqlUndoLogGenerator.update
JdbcAtRepository.append
SqlUndoLogGenerator.after
AtTransactionManager.commit / rollback
JdbcUndoExecutor.rollback
```

走完一次成功转账和一次失败转账，整个项目的主干就基本清楚了。

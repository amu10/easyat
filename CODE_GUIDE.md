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
| `easy-at-jdbc` | SQL 解析、image 采集、undo 生成/执行、JDBC 存储与锁 | `AtDataSource`、`SqlUndoLogGenerator`、`JdbcUndoExecutor`、`JdbcAtRepository`、`JdbcGlobalLockManager`、`JacksonUndoDataCodec` |
| `easy-at-storage-file` | 单机文件存储 | `FileAtRepository`、`FileGlobalLockManager` |
| `easy-at-storage-redis` | Redis 存储与锁（Lua 原子） | `RedisAtRepository`、`RedisGlobalLockManager` |
| `easy-at-spring` | Spring AOP、传播、恢复、协调、管理 | `EasyAtAspect`、`AtRecoveryScheduler`、`BranchCoordinator`、`AtRestTemplateInterceptor`/`EasyAtFeignInterceptor`/`WebClientPropagator`、`ManagementService` |
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

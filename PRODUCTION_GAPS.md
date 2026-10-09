# easyAt 生产能力缺口清单

> 基于 `DESIGN.md` 0.2 设计稿和当前代码整理。  
> 更新日期：2026-09-23  
> 当前结论：P0（单服务投产底线）、P1（可运维上线）、P2（跨服务生产）以及 P3 中的 Redis 存储、加解密/脱敏 SPI 自动装配、WebClient 传播与管理 UI 均已完成实现，项目 `mvn clean compile` 全模块通过、JDBC 模块 H2 集成用例（含 codec SPI 单测）全绿。剩余缺口仅为部分 DML 能力（生成主键/批处理仍按设计拒绝）以及真实数据库/并发/故障注入测试（需 Docker + 真实实例，沙箱离线环境暂无法执行）。

> **2026-10-09 更新（两轮）**
>
> 1. 修复 6 项会造成**静默数据不一致**的功能缺陷（空回滚、防悬挂、COMMITTING 黑洞、分支状态非 CAS、失败态被洗白、投递重试无上限），并为每条补了 H2 回归用例（见 [§12](#12-2026-10-09-已修复的生产阻断项)）。
> 2. 补齐此前最大的验证缺口：**7 个故障注入点**（`FaultInjectionTest`）、**多实例并发恢复与锁竞争**（`ConcurrentRecoveryTest`）、
>    以及**真实 MySQL / PostgreSQL / Redis 集成测试**（`easy-at-db-tests` 模块，`-Pdbtest`）。
>    真实库验证当场暴露并修复了 3 个 H2 永远发现不了的生产缺陷，其中两个是"该功能在目标数据库上根本不可用"级别
>    （详见 [§13](#13-2026-10-09-真实数据库验证暴露并修复的缺陷)）。
>
> 当前用例规模（**2026-10-09 20:20 实测复核**）：默认构建 **82 个**（`easy-at-jdbc` 72 + `easy-at-spring` 8 + boot2/boot3 各 1，`BUILD SUCCESS`）+ 真实库 **12 个**（`-Pdbtest`：MySQL 5.7 6 例、PostgreSQL 16 容器 3 例、Redis 7 本地 3 例，`BUILD SUCCESS`）。
>
> **代码侧已无未修复的功能性缺陷**；剩下的全部是交付（发布）+ 验证（压测/灰度/soak）+ 运维配套，见 §16 与 §18。

## 0. 实现状态总览

| 缺口 | 状态 | 关键实现 |
| --- | --- | --- |
| 2.1 全局状态 CAS + 版本 | ✅ 已实现 | `AtTransaction.version`；`AtRepository.transition(xid,expected,expectedVersion,next)` CAS（`JdbcAtRepository`/`FileAtRepository`/`RedisAtRepository` Lua）；`AtStatus.canTransitionTo` 校验 |
| 2.2 多实例恢复租约 | ✅ 已实现 | `owner`/`lease_until` 持久化；`claimLease`/`releaseLease` 原子领取释放；`AtRecoveryScheduler` 租约驱动恢复与安全接管 |
| 2.3 本地事务强制保障 | ✅ 已实现 | `SpringTransactionBridge` 经 `TransactionSynchronizationManager` 绑定；`requireLocalTransaction` 生产模式拒绝无本地事务 DML；切面 `@Order(100)` |
| 2.4 全局锁续租与安全接管 | ✅ 已实现 | `JdbcGlobalLockManager`/`RedisGlobalLockManager` 后台续租；Redis 仅在原事务已收敛时安全接管过期锁；`GlobalLockConflictException` |
| 2.5 JSON undo 格式 | ✅ 已实现 | `JacksonUndoDataCodec`（版本号 + 类型标签，支持大字段/二进制/时间类型）；`UndoDataEncryptor`/`UndoDataMasker` SPI（已在 Starter 自动装配进 `UndoDataCodec` Bean 并注入 JDBC/Redis 仓库与管理 API 诊断）；旧 Java 序列化回退读 |
| 3.1 分支模型与注册 | ✅ 已实现 | `AtBranch` + `BranchRepository`（Jdbc/File/Redis）；首个 DML 前经 `DefaultBranchRegistrar` 注册 |
| 3.2 协调端点 | ✅ 已实现 | `CoordinationService` + `EasyAtCoordinationController`（`POST /_easy-at/v1/branches`、`.../commit`、`.../rollback`），幂等 |
| 3.3 可靠投递与重试 | ✅ 已实现 | `BranchRetryScheduler` 扫描 `pendingActions` + 指数退避；`coordinator.rollbackBranch` |
| 3.4 传播客户端 | ✅ 已实现 | `RestTemplate`（`AtRestTemplateInterceptor` + `RestTemplateCustomizer`）、`Feign`（`EasyAtFeignInterceptor`）、`WebClient`（`WebClientPropagator` 反射式 `ExchangeFilterFunction`，仅在 spring-webflux 位于 classpath 时自动激活，避免离线编译依赖）|
| 3.5 Header 安全 | ✅ 已实现 | `HmacSigner` 对 `X-EasyAt-Xid/Deadline/Source/Signature` 签名 + 恒定时间校验 + 超时/重放保护；缺密钥时生产启动失败 |
| 4.1 方言 SPI | ✅ 已实现 | `AtSqlDialect` + `MysqlAtSqlDialect`/`PostgresAtSqlDialect`/`GenericAtSqlDialect`，自动/显式选择 |
| 5 配置绑定 | ✅ 已实现 | `EasyAtProperties` 全量绑定：application-name、资源排除、`sql.strict`/`dialect`、锁、recovery、transport、management |
| 6 管理 API 与审计 | ✅ 已实现 | `ManagementService` + `EasyAtManagementController`（`GET/POST transactions`、`retry`、`rollback`）、token 鉴权、审计、`DIRTY_WRITE` 诊断 |
| 7.1/7.2 指标与 MDC | ✅ 已实现 | `EasyAtMetrics`（Micrometer）暴露活跃事务/提交/回滚/锁冲突/恢复队列/人工介入；`EasyAtMdcFilter` 注入 `xid`/`resourceId` |
| 8 Redis 存储 | ✅ 已实现 | `RedisAtRepository`/`RedisBranchRepository`/`RedisGlobalLockManager`（Lua CAS、token/租约锁、恢复队列） |
| P3 其余 / 9 测试 | ✅ 已收口 | 生成主键/`executeBatch`/多表等按设计拒绝（非缺陷）；故障注入、多实例并发、真实 MySQL/PostgreSQL/Redis 测试已补齐（见 §13）；jedis 3.8→6.0 兼容、`easy_at_branch` 唯一约束已补（见 §14）；**分支注册与业务本地事务同连接**已实现（见 §4.3 / §15） |

## 1. 当前已经具备的能力

- `@EasyAtTransactional` 全局事务入口和基础状态机。
- Spring AOP 与 DataSource 自动代理。
- 使用 JSqlParser 校验受支持的单行 `INSERT`、`UPDATE`、`DELETE`。
- 通过 JDBC 元数据识别单列主键。
- before image、after image 和回滚前脏写检查。
- File/JDBC 事务存储和全局行锁。
- JDBC 模式下使用业务 Connection 写入 undo log。
- JDBC undo 执行器和反序回滚。
- 基础超时扫描与回滚重试。
- RestTemplate XID Header 和 Boot 2/3 服务端 Filter。
- MySQL、PostgreSQL 建表脚本。

## 2. 投产阻断项

以下功能缺失会直接影响数据一致性或系统安全，完成前不应在关键生产业务中使用。

### 2.1 全局状态缺少 CAS 和版本控制

当前状态保存不是基于期望状态和版本号的比较更新。多个线程或实例可能同时推进同一事务，旧状态也可能覆盖新状态。

需要实现：

- `AtTransaction` 增加并维护 `version`。
- JDBC 表增加或实际使用 `version` 字段。
- 状态迁移使用 `WHERE xid=? AND status=? AND version=?`。
- CAS 失败时重新读取状态，禁止直接覆盖。
- 明确定义并校验所有合法状态迁移。

验收条件：多个实例并发提交或回滚同一 XID 时，只有一个实例能成功推进状态。

### 2.2 多实例恢复租约缺失

`RecoveryScheduler` 当前只扫描可恢复事务，没有领取租约。多个应用实例可能重复回滚同一事务。

需要实现：

- `owner`、`lease_until` 持久化字段。
- 原子领取、续约和释放恢复任务。
- 实例崩溃后允许其他实例在租约过期后接管。
- 单次领取数量和租约时长配置。
- 恢复操作与状态 CAS 协同。

验收条件：多实例同时扫描时，同一事务只能由一个有效租约持有者处理；持有者崩溃后可安全接管。

### 2.3 本地事务没有强制保障

关闭自动提交或运行在 Spring 本地事务中时，业务 DML 与 undo log 可以使用同一 Connection；但自动提交模式仍可能出现只提交其中一项的情况。

需要实现：

- 生产模式检测 Spring 事务绑定的 Connection。
- 没有本地事务时拒绝执行受代理 DML，或自动创建本地事务。
- 通过事务同步回调处理提交后的 after image/分支状态。
- 定义 EasyAt 切面与 Spring `@Transactional` 的固定顺序。

验收条件：在写 undo 前、写 undo 后、业务 DML 后分别注入崩溃，均不会留下无法恢复的不一致状态。

### 2.4 全局锁租约不完整

现有 JDBC 锁支持基本租期和重入，但没有周期续租，也没有在接管过期锁前检查原事务状态。

需要实现：

- 锁等待超时和明确的 `GlobalLockConflictException`。
- 活跃事务持锁期间自动续约。
- 接管过期锁前检查原 XID 的最终状态。
- 仅在事务已收敛时清理旧锁。
- 回滚失败或人工介入状态下继续保留锁。

验收条件：长事务不会因为租期到期而被其他事务错误接管，已结束事务的遗留锁可以安全清理。

### 2.5 持久化格式仍使用 Java 原生序列化

JDBC Repository 当前使用 Java 序列化保存主键、参数和 RowImage，不满足设计中的可版本化和安全要求。

需要实现：

- 使用带格式版本号的 JSON 或 CBOR。
- 支持 JDBC 常用值类型、时间类型、二进制值和大字段。
- 增加向后兼容读取策略。
- 提供字段脱敏和加密 SPI。
- 禁止对不可信内容使用 Java 反序列化。

验收条件：框架升级后旧 undo log 仍可恢复，敏感字段不会以明文写入日志或存储。

## 3. 跨服务 AT 缺口

目前跨服务能力仅完成 XID 传递和 ThreadLocal 绑定，不是完整的跨服务事务协调。

### 3.1 分支模型与注册

需要实现：

- `easy_at_branch` 表及 Branch 实体、状态机、Repository。
- 参与服务首个 DML 前注册分支。
- 保存 `resourceId`、服务名、回调地址和创建顺序。
- 全局事务与所有分支状态的关联查询。

### 3.2 协调端点

需要实现并保证幂等：

- `POST /_easy-at/v1/branches`
- `POST /_easy-at/v1/branches/{branchId}/commit`
- `POST /_easy-at/v1/branches/{branchId}/rollback`
- 重复提交、重复回滚和乱序请求处理。

### 3.3 可靠投递与重试  **✅ 已实现**

需要实现：

- 回调任务持久化。
- 网络失败后的指数退避。
- 发起服务重启后继续投递。
- 参与服务依据共享 Repository 主动发现待处理分支。
- 部分成功时保持事务未收敛并保留相关锁。

### 3.4 更多传播客户端  **✅ 已实现**

需要补充：

- RestTemplate 自动注册。
- OpenFeign `RequestInterceptor`。
- WebClient `ExchangeFilterFunction`。
- 后续的 Dubbo、gRPC 和 MQ Header 传播。

### 3.5 Header 安全

当前服务端会信任传入的 XID，存在伪造风险。

需要实现：

- `X-EasyAt-Deadline`。
- `X-EasyAt-Source`。
- `X-EasyAt-Signature` HMAC 签名与恒定时间校验。
- 超时、来源服务和重放检查。
- 密钥轮换与缺少密钥时的生产启动失败策略。

验收条件：两个独立服务可以注册和完成分支；异常、重复回调、网络超时及服务重启不会造成遗漏补偿或重复补偿。

## 4. SQL 和数据库能力缺口

### 4.1 方言 SPI 尚未完整抽取

当前已使用 JSqlParser 做结构化校验，但 SQL 生成逻辑仍集中在 JDBC 模块。

需要实现：

- `AtSqlDialect` 接口。
- MySQL 8 方言。
- PostgreSQL 14+ 方言。
- 标识符引用、schema/catalog 和保留字处理。
- 方言自动检测与显式配置。

### 4.2 未支持的 DML 能力

以下能力当前应继续严格拒绝，只有在可以可靠生成镜像与 undo 后才能开放：

- 批量执行和 `executeBatch`。
- 自增主键和 `RETURN_GENERATED_KEYS`。
- 复合主键。
- 多行 DML。
- 表达式更新、子查询和多表 DML。
- 大字段和流式参数。

### 4.3 分支状态原子性（**已实现**）

需要确保分支注册、undo 写入和业务 DML 位于同一个本地事务中，并在本地提交后可靠推进分支状态。

> **2026-10-09 收口**：分支注册已接入业务本地连接，三者同生共死。详见 §15。

改动要点：

- `BranchRepository#registerIn(AtBranch, Object localConnection)`（参数用 `Object` 以保持 core 存储无关），
  `JdbcBranchRepository` 识别到 `java.sql.Connection` 时在该连接上 INSERT。
- `BranchRegistrar#register(xid, resourceId, Object localConnection)` 新增三参数默认方法，
  两参数版本委托给它并传 `null`——既有实现二进制兼容。
- `SqlUndoLogGenerator#capture` 把业务连接 `c`（`target.getConnection()` 取到的物理连接）传给 registrar。
- `DefaultBranchRegistrar` 优先走 `registerIn`，Redis/File 等无本地事务概念的存储自动回退到独立连接。
- 两个坑已在实现里处理：① 同事务多条 DML 会重复走到注册，故**先在业务连接上查重**（能看见本事务未提交的
  行）；② 仍撞唯一键冲突时**回滚到 savepoint**——PostgreSQL 上一句报错会把整个业务事务标记为 aborted。

## 5. 配置与 Spring 集成缺口

当前 Starter 中多项值为硬编码，设计稿里的配置尚未完整绑定。

需要实现：

- `easy-at.application-name`。
- `easy-at.resources.<bean>.enabled` 和 `resource-id`。
- DataSource 包装排除列表。
- `easy-at.sql.strict` 和 `dialect`。
- 锁等待、租约和续约配置。
- Recovery 的启用、间隔、批量、租约和最大重试次数。
- Transport HMAC 配置。
- 管理端点启用和安全配置。
- 配置项校验及生产环境安全默认值。

还需要避免代理框架自身使用的协调 DataSource，防止内部 SQL 被重复代理或出现 Bean 初始化循环。

## 6. 管理与人工处理能力缺口

需要实现带认证和授权的管理 API：

- `GET /_easy-at/v1/transactions/{xid}`
- `GET /_easy-at/v1/transactions?status=MANUAL_INTERVENTION`
- `POST /_easy-at/v1/transactions/{xid}/retry`
- `POST /_easy-at/v1/transactions/{xid}/rollback`
- `GET  /_easy-at/v1/ui`：内置只读管理控制台（复用上述 API，undo 敏感列经 `UndoDataMasker` 脱敏）

还需要：

- 明确的 `DIRTY_WRITE` 冲突状态和诊断信息。
- 操作审计记录，包括操作者、时间、原因和结果。
- 管理 API 默认关闭或仅绑定管理网络。
- 防止人工操作与自动恢复并发执行。

## 7. 可观测性和安全缺口

### 7.1 指标

需要通过 Micrometer 暴露：

- 活跃事务数。
- 提交、回滚和回滚失败计数。
- 锁冲突计数。
- 恢复队列深度和恢复耗时。
- 人工介入事务数量。

### 7.2 日志与 Trace  **✅ 已实现**

需要实现：

- 日志 MDC 自动携带 `xid`、`branchId`、`resourceId`。
- Trace/Baggage 集成。
- undo 参数和敏感业务字段屏蔽。
- 状态迁移、锁接管和人工操作审计日志。

### 7.3 安全

需要实现：

- 协调端点的服务间认证与授权。
- Header 防伪造、防重放。
- undo 数据加密和密钥管理扩展。
- SQL 和表的允许列表。
- 管理接口公网暴露保护。

## 8. Redis 存储缺口

设计中的 Redis Repository 和 Redis Lock 尚未实现。

需要实现：

- Global、Branch、Undo 和恢复队列的数据结构。
- 基于 Lua 的状态 CAS。
- 基于 token 的锁获取、续约和释放。
- 原子加入恢复队列。
- Redis 故障、主从切换和脚本重试策略。

Redis 不是单服务投产的绝对前置条件；如果使用 JDBC，必须先完成 JDBC CAS、恢复租约和锁安全机制。

## 9. 测试缺口

> **2026-10-09 状态**：前 6 项已补齐（见 §13）。仍待补：

- ~~MySQL Testcontainers。~~ ✅ `-Pdbtest`：`MysqlRealDatabaseIT`
- ~~PostgreSQL Testcontainers。~~ ✅ `-Pdbtest`：`PostgresRealDatabaseIT`
- ~~Redis Testcontainers。~~ ✅ `-Pdbtest`：`RedisRealStorageIT`
- ~~多实例并发恢复测试。~~ ✅ `ConcurrentRecoveryTest`
- ~~同一主键锁竞争和租约续期测试。~~ ✅ `ConcurrentRecoveryTest`
- ~~写 undo 前后、DML 前后和提交前后的故障注入。~~ ✅ `FaultInjectionTest`（7 个注入点）
- 网络超时、回调重复、服务重启测试。
- 脏写、人工重试和幂等性测试。
- 大字段、时间、二进制和特殊 JDBC 类型测试。
- Starter 自动配置和多 DataSource 测试。
- 性能、连接池压力和长事务测试。
- 多实例**真实集群**下的故障注入（目前并发测试在单 JVM 多线程 + 独立 owner 层面，未做跨进程崩溃演练）。

## 10. 建议实施顺序

### P0：单服务投产底线

1. JDBC 全局状态 CAS 和合法状态迁移。
2. 多实例恢复租约、领取、续约和接管。
3. 强制本地事务，保证业务 DML 与 undo 原子性。
4. 全局锁续租及安全过期接管。
5. JSON/CBOR undo 格式和兼容策略。
6. MySQL/PostgreSQL Testcontainers 与故障注入。

### P1：可运维和受控上线

1. 完整配置绑定和校验。
2. 管理 API、认证授权和审计。
3. `DIRTY_WRITE` 等冲突状态及诊断。
4. Micrometer、MDC 和告警。
5. 方言 SPI 与大字段处理。

### P2：跨服务生产能力

1. Branch Repository 和状态机。
2. 注册、提交、回滚协调端点。
3. 可靠回调队列和幂等重试。
4. HMAC、deadline 和来源校验。
5. Feign、WebClient 等传播组件。

### P3：扩展能力

1. Redis Repository/Lock。
2. 生成主键、批处理及更多数据库方言。
3. 数据脱敏、加密 SPI 和 SQL 白名单。
4. 管理 UI 或独立只读查询服务。

## 11. 当前可用范围

当前版本适用于：

- 框架原理验证。
- 开发和测试环境。
- 单实例、非关键数据、具备人工修复手段的受控场景。

当前版本不适用于：

- 金融、订单、库存等关键一致性业务（缺少真实流量的 soak 验证，见 §16）。
- 无法接受人工数据修复的生产系统。

> **2026-10-09 更新**：P0/P1/P2 与 §13/§14/§15 的修复合入并发布后，
> 「单实例」「多实例自动恢复」「完整跨服务提交/回滚」三项已从"不适用"移除，
> 可按 `RUNBOOK.md` 的 5 阶段灰度评估上线。

## 12. 2026-10-09 已修复的生产阻断项

以下六项此前都会导致**静默的数据不一致**（既不报错、也不告警、还不收敛），现已修复并各配一条回归用例
（`easy-at-spring/src/test/java/io/github/easyat/spring/BranchConsistencyTest.java`）。

| # | 缺陷 | 修复前后果 | 修复位置 |
|---|---|---|---|
| 1 | **空回滚缺失**：协调者对尚未注册的分支发 rollback 时 `orElseThrow` | 分支回调按退避无限重试，永不收敛、永不进人工介入 | `BranchCoordinator#emptyRollback` 落 `ROLLED_BACK` 占位记录，回滚幂等成功 |
| 2 | **防悬挂缺失**：注册分支不检查是否已被回滚；`join()` 不校验全局状态 | rollback 先到 / 超时回滚后迟到的请求照样执行 DML 并本地提交，undo 挂在终态 XID 下 → **永久悬挂数据** | `DefaultBranchRegistrar#register`、`BranchCoordinator#register` 拒绝已回滚资源；`AtTransactionManager#join` 只接受 ACTIVE；`append` 写 undo 前二次校验 |
| 3 | **COMMITTING 黑洞**：`ACTIVE→COMMITTING→COMMITTED` 第二步失败后事务不在恢复集合 | 事务永久残留、**全局锁泄漏**，且无法转入人工介入（状态机不允许） | `AtStatus` 放行 `COMMITTING→MANUAL_INTERVENTION`；新增 `AtTransactionManager#finishCommit`；三个 `AtRepository#recoverable` 纳入 COMMITTING；`AtRecoveryScheduler` 按状态分派 |
| 4 | **分支状态非 CAS**：`mark()` 无条件 `save()` 覆盖，状态机与 `transition` 是死代码 | 并发恢复/投递互相覆盖，丢失"谁推进过"的事实 | `BranchCoordinator#advance` 全部改用 `BranchRepository#transition` CAS，并把 `BranchStatus` 迁移表补齐（新增 `MANUAL_INTERVENTION`） |
| 5 | **失败态被洗白**：`ROLLBACK_FAILED` 非终态，可被乱序 commit 置为 `COMMITTED` | 回滚失败被静默固化成"已提交"，不再重试 | `BranchCoordinator#actExisting` 阻止非 `COMMITTED` 合法前驱被提升为 `COMMITTED` |
| 6 | **投递重试无上限**：`BranchRetryScheduler` 无 `maxRetries`，`finally` 还会把 `MANUAL_INTERVENTION` 拉回 `ROLLING_BACK` | 长期失败的分支无限重试；人工介入标记被抹掉 | 重试耗尽转 `MANUAL_INTERVENTION` 并计入 Micrometer 指标；恢复 `finally` 跳过人工态；`drive` 改为逆序回滚 + 单分支异常隔离 |

配套调整（顺带修正的语义问题）：

- **协调端点返回真实 HTTP 状态码**：`CoordinationService` 鉴权失败返回 401、参数错误 400、命中防悬挂返回 409。
  此前错误用 HTTP 200 + `{"error":...}` 返回，会被 `deliverRemote` 判为"回滚成功"。
- **`BranchRepository#findByXidResource`**（default 方法）：按 `(xid, resourceId)` 唯一定位分支，是空回滚与防悬挂的共同前提。
- ~~**`easy_at_branch` 无 `(xid, resource_id)` 唯一约束**~~ —— **已补齐（见 §14.2）**：建表脚本加
  `UNIQUE KEY`、提供去重迁移脚本、三处存储（Jdbc/Redis/File）的 `register` 改为幂等。

### 修复后仍未做（真实数据库验证缺失）

这六项修复**只在 H2 上验证过**。上线前仍需完成 [§9](#9-测试缺口) 中剩余项。**没有这些，"正确"仍只是代码层面的推断。**

## 13. 2026-10-09 真实数据库验证暴露并修复的缺陷

补齐验证（故障注入 + 并发 + 真实库）后，当场发现 **4 个缺陷**。其中前两个的严重程度是
"该功能在目标数据库上根本不可用"，而它们在 H2 上**永远不会被发现**——这正是补齐验证的意义。

### 13.1 PostgreSQL 上回滚 100% 失败（P0，已修复）

- **现象**：`PSQLException: ERROR: operator does not exist: bigint = character varying`，
  发生在 `JdbcUndoExecutor#assertNoDirtyWrite`。
- **根因**：`easy_at_undo_log.pk_value` 是 `VARCHAR` 列，`JdbcAtRepository` 读回时统一用 `getString`，
  所以**无论业务主键原本是什么类型，取回来一律是 String**。原先统一用 `setObject` 绑定，
  PostgreSQL 驱动把该值当 `varchar` 发送，于是 `WHERE id=?` 变成 `bigint = character varying`。
- **为何 H2/MySQL 掩盖**：两者都做隐式类型转换，不报错。
- **修复**：`JdbcUndoExecutor` 新增 `bind()`（按实际 Java 类型选择 setter）与 `coerce()`
  （读列元数据把 String 还原为列本身的类型，结果按 `表.列` 缓存）。
- **回归**：`PostgresRealDatabaseIT#fullAtRoundTripWithRollback`、
  `#undoRestoresEveryColumnIncludingStrings`。

### 13.2 Redis 全局锁 Lua 类型错误（P0，已修复）

- **现象**：`JedisDataException: ERR user_script: attempt to compare number with string`，
  `eval` 直接抛异常——调用方既拿不到锁，也拿不到"冲突"语义。
- **根因**：`ACQUIRE_LUA` 中 `lease` 经 `tonumber()` 是数字，而 `now` 直接取自 `ARGV[3]` 是字符串
  （Redis 的 ARGV 一律是字符串），Lua 不允许数字与字符串比较。
- **为何之前没发现**：Redis 路径此前只有 1 个自动配置冒烟用例，Lua 逻辑从未在真实 Redis 上执行过。
- **修复**：`now=tonumber(ARGV[3])`，并对 `lease` 增加 `nil` 保护。
- **回归**：`RedisRealStorageIT#globalLockIsMutuallyExclusive`。

### 13.3 jedis 版本二进制不兼容（P1，**已修复**）

- **现象**：`NoSuchMethodError: java.lang.Long redis.clients.jedis.Jedis.hset(String, Map)`。
- **根因**：写命令（`hset`/`sadd`/`del`/`exists`）在 jedis 3.x 返回包装类型 `Long`/`Boolean`，
  从 **4.x 起改成基本类型** `long`/`boolean`。JVM 方法描述符含返回类型，于是"按 3.8 编译、跑在 4+ 上"
  必然抛 `NoSuchMethodError`——反向也一样。
- **难点**：使用者说了算，我们控制不了运行期版本。**Spring Boot 2.7 的 BOM 管 jedis 3.8.0，
  Spring Boot 3.5 的 BOM 管 jedis 6.0.0**，`dependencyManagement` 会覆盖本模块声明的版本。
- **修复（不是升版本号就完事）**：把 Redis 模块改成**只调用跨 3.8→6.0 签名稳定的 API**——
  - 写：全部走 `eval(String, List<String>, List<String>)`，HSET/SADD/DEL/SETNX 都写进 Lua；
  - 读：只用 `hget` / `hgetAll` / `smembers` / `get`（返回类型从未变过）。
  - 编译版本升到 **4.4.6**；因此无论使用者被 BOM 换成 3.8 还是 6.0，字节码都能解析。
- **验证**：`easy-at-db-tests` 新增 `-Pjedis3 / -Pjedis5 / -Pjedis6` 三个 profile，
  在**本地真实 Redis** 上分别跑 `RedisRealStorageIT`：
  **jedis 3.8.0 / 4.4.6 / 5.2.0 / 6.0.0 四档各 3/3 全绿**（见 §14）。

### 13.4 并发回滚重复补偿 → 假性 DIRTY_WRITE（P1，已修复）

- **现象**：多实例并发恢复同一事务时，最终状态是 `DIRTY_WRITE` 而非 `ROLLED_BACK`；
  数据其实已正确回滚，却被标记为"需人工介入"。
- **根因**：`AtTransactionManager#rollback` 中
  `if (tx.getStatus() != ROLLING_BACK && !transition(tx, ROLLING_BACK))` ——
  当状态**已经是 `ROLLING_BACK`** 时短路为 false，**直接跳过 CAS 进入补偿循环**；
  两个执行者并发跑同一条 undo，后到的脏写校验看到"当前行 ≠ after image"（其实是被同伴改的）。
- **真实触发场景**：长回滚期间租约过期、另一实例接管。
- **修复**：新增 `enterRollback` / `exitRollback` 两道互斥——
  单 JVM 内用 `rollbackInFlight` 集合拦住并发线程；跨实例用恢复租约
  （同 owner 可重入，保证 `AtRecoveryScheduler` 抢租约后仍能驱动回滚；他人持有且未过期则拒绝；
  租约过期允许接管，保证持有者崩溃后仍有人推进）。装配上把 `owner` 传给 `AtTransactionManager`，
  与调度器保持一致。
- **回归**：`ConcurrentRecoveryTest#concurrentRecoveryAcrossInstancesCompensatesOnce`
  （已做反向验证：临时移除互斥后该用例立即变红，证明它确有捕获能力）。

## 14. 2026-10-09 投产三项遗留问题的收口

### 14.1 jedis 版本兼容（原 §13.3，已修）

见 §13.3。核心是"用签名稳定的 API"而不是"挑一个版本编译"——因为运行期版本由使用者的 BOM 决定。

### 14.2 `easy_at_branch` 的 (xid, resource_id) 唯一约束（已补）

| 层 | 改动 |
|---|---|
| 建表脚本 | MySQL `UNIQUE KEY uk_easy_at_branch_xid_resource`；PostgreSQL `CREATE UNIQUE INDEX`（(128+128)×4=1024 字节，低于 InnoDB 3072 上限） |
| 迁移脚本 | `db/{mysql,postgresql}/migration/v0.1.2__branch_unique.sql`：先按 `(xid, resource_id)` 去重（保留 `branch_id` 最小的一条），再加约束 |
| `JdbcBranchRepository` | `register` 先查 `findByXidResource`，命中即幂等返回；INSERT 冲突时按 SQLState `23xxx` / 错误码 1062·2601·2627 兜住并发窗口。并 override `findByXidResource` 走索引 |
| `RedisBranchRepository` | 新增 `branch:uniq:<xid>:<resource_id>` 索引键，注册走 `SETNX` 抢占；抢占失败即幂等返回，不覆盖、不重复建索引 |
| `FileBranchRepository` | `register` 在写锁内先扫一遍同 `(xid, resource_id)`，已存在则幂等返回 |
| 回归 | `ReconciliationTest#detectsDuplicateBranchRegistrationAsIdempotent`（H2，含 UNIQUE 约束） |

### 14.3 影子运行对账与人工修复入口（已实现）

- `ReconciliationReport` / `ReconciliationService`（`easy-at-core`）：只依赖
  `AtRepository` + `BranchRepository` + `GlobalLockManager`，三种存储通用。
- 检测项：超时未收敛的 ACTIVE、无有效租约的 ROLLING_BACK、COMMITTING 黑洞、ROLLBACK_FAILED、
  DIRTY_WRITE、**MANUAL_INTERVENTION 计数**、**锁泄漏**、跨服务的分支悬挂。
  级别：`CRITICAL`（人工介入/脏写/锁泄漏/悬挂）> `WARN`（残留）> `OK`。
- 锁全量快照：`GlobalLockManager#heldLocks()`（默认空），`JdbcGlobalLockManager` 已实现；
  Redis 侧因 SCAN 难以跨 jedis 版本稳定调用，改用运维手册里的 `redis-cli --scan` 片段。
- 三个入口：
  1. 管理端点 `GET /_easy-at/v1/reconciliation`（boot2/boot3 均已接线，`RUNBOOK.md`）。
  2. 独立 CLI `io.github.easyat.jdbc.ReconciliationCli <jdbc-url> [user] [password]`（退出码 0/2/1，可直接挂 cron）。
  3. 纯 SQL：`db/{mysql,postgresql}/reconciliation.sql`（7 段查询 + 汇总计数，DBA 直接跑）。
- 人工修复入口：管理端点 `GET /transactions/{xid}`、`POST /transactions/{xid}/retry`、
  `POST /transactions/{xid}/rollback`、`GET /audit`；UI `/_easy-at/v1/ui`。
  逐场景修复流程（MANUAL_INTERVENTION / DIRTY_WRITE / 锁泄漏）与灰度阶段表见 `RUNBOOK.md`。
- 回归：`ReconciliationTest` 6 个用例——干净环境必须报 OK，五种异常各自必须被抓到
  （防止"对账永远返回健康"这种假绿）。

## 15. 2026-10-09 分支状态原子性（§4.3，已实现）

分支注册此前走框架自己的独立连接，与业务本地事务不同源。后果是**业务回滚后分支记录仍然留下**——
协调器以为该资源参与了全局事务、会去回调它提交/回滚，但业务侧其实什么都没做；这个分支既不收敛也不告警。

| 层 | 改动 |
|---|---|
| `BranchRepository` | 新增 `default boolean registerIn(AtBranch, Object localConnection)`。参数刻意用 `Object` 而非 `java.sql.Connection`，让 core 保持存储无关 |
| `JdbcBranchRepository` | 识别到 `Connection` 时在该连接上 INSERT；**不关闭**该连接（归业务方管）。先在本连接查重，撞唯一键冲突时回滚到 savepoint |
| `BranchRegistrar` | 新增 `default void register(xid, resourceId, Object localConnection)`，两参数版委托给它传 `null`——既有实现二进制兼容 |
| `DefaultBranchRegistrar` | 优先 `registerIn`；Redis/File 等无本地事务概念的存储自动回退 |
| `SqlUndoLogGenerator#capture` | 把业务连接 `c`（`target.getConnection()` 取到的**物理**连接）传给 registrar；物理连接不经过代理，因此不会递归触发分支注册 |

两个必须处理的坑：

1. 同一本地事务通常有多条 DML，每条都会走到注册。所以在**业务连接上查重**——它能看见本事务尚未提交的
   行，从而根本不会重复 INSERT。
2. 仍撞唯一键冲突（别的实例并发注册）时**回滚到 savepoint**。PostgreSQL 上一句报错会把整个业务事务
   标记为 aborted，后续语句全部失败，业务直接挂掉。MySQL/H2 无此问题，但统一走 savepoint 更安全。

回归：`BranchRegistrationAtomicityTest` 4 例（H2，分支表带 `(xid, resource_id)` UNIQUE 约束）

- 业务提交 → 分支行可见
- 业务回滚 → 分支行消失
- 同事务多条 DML → 只注册一条分支，且不打断事务
- **反向验证**：故意退回独立连接注册，业务回滚后分支行确实残留——证明前三条测的是 `registerIn` 的效果

## 16. 代码侧已无阻断项，剩下的都是交付与验证流程

到本节为止，文档里**没有未修复的功能性缺陷**了。剩下三件事都不是改代码能解决的：

| # | 事项 | 为什么必须做 |
|---|---|---|
| 1 | 提交改动并发布新版本 | 已发布的 0.1.1 是修复前代码；正式版在 Central 不可覆盖，必须升版本重发 |
| 2 | 用真实业务 SQL 过一遍 AT 能力边界 | 子查询/JOIN/任意条件/多行 INSERT 已支持（§17），但**仍拒绝**多目标表 DML、给主键赋值、无主键表、`INSERT ... SELECT`、依赖自增主键却不写主键列。别到线上才发现这些被拒 |
| 3 | 按 `RUNBOOK.md` 灰度并影子对账 ≥7 天 | 核心一致性逻辑改完即发、零 soak time。盯 `easy_at_global` 残留、锁泄漏、`MANUAL_INTERVENTION` 计数 |

另：`~/.m2/settings.xml` 里明文存了 Central Token 与 GPG 口令，建议轮换。

> **2026-10-09 20:20 复核**：第 1 项仍未完成（`main` ahead 2，Central 无 0.1.2）。
> 该三人组已不足以覆盖全部风险，完整卡点清单见 [§18](#18-2026-10-09-交付评估代码就绪剩余卡点清单)。

## 17. 2026-10-09 通用快照路径：子查询 / JOIN / 多行 INSERT（原"刻意拒绝"，现已支持）

此前除"按主键、单行、值为 `?`"之外的 DML 一律抛 `UnsupportedAtSqlException`。这对真实业务 SQL 是硬伤——
批量更新、带子查询的删除、`UPDATE ... JOIN` 都是日常写法。现在换了个思路：

**不再要求框架"理解" WHERE，而是把原语句的 `FROM / WHERE / ORDER BY / LIMIT` 拼成一条 SELECT，
先读出所有会被影响的行，再逐行建 undo、逐行抢全局锁。**

```
UPDATE account SET balance=? WHERE id IN (SELECT aid FROM frozen WHERE status=?)
 ->
SELECT account.* FROM account WHERE id IN (SELECT aid FROM frozen WHERE status = ?)
```

| 层 | 改动 |
|---|---|
| `GenericSnapshotPlanner` | 由 UPDATE/DELETE AST 构造快照 SQL；识别单目标表（JOIN 落在 `startJoins`/`joins`，PG 的 `FROM` 落在 `fromItem`） |
| `GenericAtExecutor` | 快照 → 逐行加锁 → UPDATE 整行还原 / DELETE 整行插回 → 写入 undo |
| `SqlUndoLogGenerator#capture` | 严格路径抛 `UnsupportedAtSqlException` 后回退通用路径；两条路都失败时合并报错原因 |
| `RecognizerSupport#parameterCount` | 用 `ExpressionDeParser` 计数——`ExpressionVisitorAdapter` **不下钻子查询**，会把 `WHERE id IN (SELECT ... WHERE x=?)` 的 `?` 漏掉，导致参数绑定错位 |
| `InsertRecognizer` / `InsertAtExecutor` | 多行 `VALUES (...),(...)`；字面量与 `?` 混用时按文本顺序推进参数下标 |
| 别名 | `RecognizerSupport#tableName` 对别名由 `AtException` 改成"不支持"，让语句有机会落到通用路径 |

实现的三个坑：

1. **参数偏移**：UPDATE 的 SET 参数排在 WHERE 之前，快照 SELECT 没有 SET，必须跳过。
2. **逗号连接要补逗号**：`UPDATE a, b SET ...` 的第二个表渲染出来不带 JOIN 关键字，直接拼会得到 `FROM a b`。
3. **多目标表 / 给主键赋值仍拒绝**：前者无法按单个表的行建 undo，后者改完就找不到原行了。

回归：

- H2：`GenericAtSqlTest` 11 例（子查询 UPDATE/DELETE、任意谓词、`SET` 子查询、多行 INSERT、
  字面量混用、行数上限、多目标表拒绝、主键赋值拒绝、JOIN/PG-FROM 的快照 SQL 构造、子查询参数计数）
- 真实库：`JoinAndSubqueryAtIT` 3 例（MySQL 5.7 的 `UPDATE ... JOIN`、MySQL 子查询 DELETE、PG 的 `UPDATE ... FROM`）
- 既有断言同步更新：`AtDataSourceTest` 两条"应拒绝"改为"应支持并回滚"、`InsertRecognizerTest` 字面量改为支持

## 18. 2026-10-09 交付评估：代码就绪，剩余卡点清单

> 结论：**功能正确性已收口**（`mvn -o clean verify` 82 绿 + `-Pdbtest` 12 绿，见 §0），
> 但"能不能上生产"此刻卡在**交付与验证流程**，而不是代码。以下按严重程度排序。

### 18.1 硬卡点（不满足则不能上线）

| # | 卡点 | 证据 | 怎么消 |
|---|---|---|---|
| 1 | **0.1.2 没有发布到 Maven Central** | `git log`：`main` ahead `origin/main` 2 个提交（`01d0d24` 通用 SQL 路径、`afad91b` 文档）；Central 上只有修复前的 0.1.1 | `git push` + `mvn deploy -Prelease`，Portal 上 Publish。**在 0.1.2 可用之前，用户拿到的仍是缺陷版本** |
| 2 | **零 soak / 零压测数据** | 全仓库无 JMH/Gatling/基准脚本 | 至少给出：单条写 undo 的 RT 增量、热点行锁等待、通用快照路径多一次 SELECT 的开销。核心链路放量前必须有 |
| 3 | **Redis 存储既无历史清理也无 TTL，且 undo 不与业务本地事务原子提交** | `AtCleanupScheduler` 构造参数强绑 `JdbcAtCleaner`（starter 上另有 `@ConditionalOnBean(JdbcAtCleaner.class)`），Redis 模式下该 Bean 根本不存在；`AtRepository` 接口无 `delete`/`purge`；全仓搜索 `expire(` / `setex` / `ttl` 零命中；`RedisAtRepository` **只** `implements AtRepository`，未实现 `ConnectionBoundAtRepository` | **生产不要用 Redis 存 undo**，详见 §18.4；改 JDBC 存储则不受影响 |
| 4 | **多实例"跨进程"崩溃演练未做** | `ConcurrentRecoveryTest` 是单 JVM 多线程 + 独立 owner，没有 `kill -9` / 断网 / 容器驱逐 | 真实集群下 kill 实例验证租约接管与补偿唯一性 |
| 5 | **业务 SQL 未过 AT 边界** | 拒绝清单见 `SQL_COMPATIBILITY.md`：多目标表 DML、`SET pk=?`、无/复合主键表、`INSERT...SELECT`、`ON DUPLICATE KEY`、依赖自增主键却不写主键列 | 拿生产真实 SQL 全量过一遍，看有没有踩线（`UnsupportedAtSqlException` 会被直接拒绝执行） |

### 18.2 运维配套缺口（上线前建议补齐）

| # | 缺口 | 现状 |
|---|---|---|
| 6 | 无 CI | `.github/` 目录不存在——每次 PR 不会自动跑 82 个用例 + `-Pdbtest` |
| 7 | 无告警面 | Micrometer 指标齐（`EasyAtMetrics`），但没有 dashboard json / alert rules yaml，落地要人工翻译一遍指标名 |
| 8 | 传播客户端覆盖不足 | 只有 `AtRestTemplateInterceptor` / `EasyAtFeignInterceptor` / `WebClientPropagator`；**Dubbo、gRPC、MQ（RocketMQ/Kafka）均无**——链路里只要有一段跨 MQ，XID 就断 |
| 9 | 管理端鉴权粒度 | 单一静态 token（`easy-at.management.token`），无 RBAC、无 IP 白名单；Operator/审计靠调用方自报 `operator` 参数 |
| 10 | 凭证未轮换 | `~/.m2/settings.xml` 明文存 Central Token 与 GPG 口令 |

### 18.3 功能边界（不是缺陷，是能力范围）

- 分库分表（ShardingSphere 等代理数据源）、读写分离多数据源、DDL、`TRUNCATE`、`MERGE`、存储过程均未支持也未验证。
- 一行一锁：热点行的并发写会被全局锁串行化，`GlobalLockConflictException` 随热点上升——这是 AT 的固有代价，不是本实现的 bug。

### 18.4 Redis 存储的完整风险评估（2026-10-09）

> **2026-10-09 21:00 收口**：问题二（无回收）与问题三（内存增长）已实现修复：新增 `RedisCleanup`
> （zset 索引 + 批量删除）与兜底 TTL；并新增 `storage.type=hybrid` 混合存储，把 undo 移回业务库
> 从而彻底解决问题一。详见 [§19](#19-2026-10-09-混合存储hybrid与-redis-回收)。

用户问："存储改成 Redis、undo 也存 Redis，会不会出现存储过多不回收？"

**会。而且"不回收"只是三个问题里最轻的一个。**（以下为修复前的事实记录）

#### 问题一（最严重）：undo 不再与业务 DML 原子提交

`AtTransactionManager#append(Connection, UndoRecord)` 的分支：

```java
if (repository instanceof ConnectionBoundAtRepository) {
    ((ConnectionBoundAtRepository) repository).append(connection, record);  // 走业务连接
    return;
}
tx.addUndo(record);
repository.save(tx);   // ← Redis 落到这里：立刻独立写 Redis，与业务本地事务无关
```

`ConnectionBoundAtRepository` 全仓只有 `JdbcAtRepository` 实现；`RedisAtRepository implements AtRepository`，
所以 Redis 模式下 **undo 写在业务本地事务之外**。这与 §2.3「本地事务强制保障」直接冲突：

- 业务本地事务回滚，undo 却已落 Redis → 恢复调度会对一条从未真正改动的行执行回滚 SQL；
- Redis 写失败而业务提交成功 → 该行**永久无法回滚**；
- `UPDATE` 的 after image 通过 `updateUndo` 二次写入，同样是独立写。

这是选型级问题，不是加个清理任务能解决的。

#### 问题二：键只增不减

每个全局事务落下这些键（`prefix` 默认 `easy-at`）：

| 键 | 内容 | 会回收吗 |
|---|---|---|
| `easy-at:global:<xid>` | 事务元数据 hash | **永不删除**，终态也留着 |
| `easy-at:global:status:<status>` | xid 成员集合 | 迁移时 SREM 旧 + SADD 新，但终态集合里的成员**永不移除** |
| `easy-at:undo:<xid>` | undo id 索引集合 | `save()` 时 DEL 后重建，但**索引本身长期残留** |
| `easy-at:undo:<xid>:<uuid>` | 单条 undo hash，含 before/after image(base64) | **完全没有删除路径** |
| `easy-at:branch:<id>` / `branch:uniq:<xid>:<res>` | 分支记录 | **永不删除** |
| `easy-at:lock:<r>:<t>:<pk>` / `lock:byXid:<xid>` | 全局锁 | ✅ `releaseByXid` 的 `RELEASE_LUA` 会 DEL |

注意 `RedisAtRepository#save()` 里的 `CLEAR_UNDO_LUA = "return redis.call('DEL', KEYS[1]);"`，
KEYS[1] 是**索引集合** `easy-at:undo:<xid>`，不是那些 `easy-at:undo:<xid>:<uuid>` 实体——
实体 hash 一旦写过就再也无人引用，连 `recoverable()` 都扫不到它们。

#### 问题三：内存量级

单条 undo 要存整行的 before image **和** after image，JSON 编码后再 base64（×1.33）。
20 列左右的业务行，单条 undo 约 **3–8 KB**。粗算：

> 日均 100 万全局事务 × 每事务 3 条 DML = 300 万条 undo × ~5 KB ≈ **15 GB/天**

Redis 是内存库，几天就会撞 `maxmemory`。而一旦配了 `allkeys-lru` 之类的淘汰策略，
**被淘汰的可能正是还没收敛的 undo** —— 那不是报错，是静默地无法回滚。

另外 `save(tx)` 是**全量重写**该事务所有 undo（每条 DML 触发一次），N 条 DML ≈ O(N²) 次 HSET + 网络往返。

#### 结论（已按此实现）

- **生产推荐 `hybrid`**：undo 在业务库（保证与业务 DML 原子提交），全局状态/分支/锁在 Redis。
- 纯 `redis` 存储定位为验证/演示用途；若一定要上，必须配好 `easy-at.cleanup.enabled=true`
  与合理的 `redis.ttl`，并把 `maxmemory-policy` 设为 `noeviction`。
- 实现见 §19；回归：`HybridUndoStorageTest` 5 例（H2，含反向验证）+
  `RedisCleanupIT` 3 例（真实 Redis）。

## 19. 2026-10-09 混合存储（hybrid）与 Redis 回收

用户要求支持两件事：① undo 走业务库、其余走 Redis 的**混合方案**；② **单独 Redis 存储**也要能长期运行。
两项都已落地，`mvn -o clean verify` 全绿。

### 19.1 混合存储：把 undo 存储从 AtRepository 里拆出来

核心是新增一层 SPI，让 undo 的落点可以独立选择：

| 组件 | 作用 |
|---|---|
| `core.UndoRepository` | `load(xid)` / `replaceAll(xid, records)` / `deleteByXid(xid)` |
| `core.ConnectionBoundUndoRepository` | 额外提供 `append(Connection, …)` / `updateUndo` / `removeUndo`——**走业务连接**是关键 |
| `jdbc.JdbcUndoRepository` | 只依赖 `easy_at_undo_log` 一张表，混合模式专用（业务库不需要 `easy_at_global`） |
| `core.CleanupScheduler` | 与存储无关的清理调度；被删的 xid 交给 `Consumer` 做级联（正好用来删业务库 undo） |
| `AtTransactionManager` | 新增 7 参构造器（旧签名全部委托过来，二进制兼容）；`persist(tx)` 统一出口 |

几条必须说清的取舍：

- **为什么不能给 `RedisAtRepository` 也实现 `ConnectionBoundUndoRepository`**：Redis 连接不是 JDBC
  业务连接，它无法参与业务本地事务的提交/回滚——做不了"原子"，所以干脆不给它这个能力，由 `storage.type`
  显式区分，避免使用者误以为两者等价。
- **`persist(tx)` 在混合模式下不等于一个事务**：undo 写业务库、重试簿记写 Redis。簿记字段（retry_count /
  next_retry_at）本来就允许最终一致；真正要求与业务原子的是 undo 本身，它仍在业务连接里。
- **`required(xid)` 要拼装**：全局状态从 Redis 读、undo 从业务库读，两侧合起来才是完整事务快照。
  只在**显式注入** `UndoRepository` 时才合并，避免单存储模式重复加载。

### 19.2 Redis 单独存储：补上回收

| 改动 | 说明 |
|---|---|
| `RedisCleanup` | 新增 `easy-at:cleanup:index`（zset，member=xid、score=updated_at）按时间捞候选；一次 Lua 删掉 global hash、undo 索引**与它引用的所有 undo 实体**、分支实体与 `branch:uniq:`、状态集合成员、残留锁，最后 `ZREM` |
| 未到期候选 | 打上当前时间戳的 score 排到队尾：避免占满批次导致清理停滞，长期静止后又自然老化回队首重新参与扫描 |
| 兜底 TTL | `RedisAtRepository` 在每次 `create` / `transition` / `save` / `updateRecovery` 时刷新 `EXPIRE`（`easy-at.redis.ttl`，默认 30 天）。活跃事务永不到期，只有清理器也挂了的孤儿键才最终回收 |
| 孤儿键修复 | `CLEAR_UNDO_LUA` 原先只 DEL **索引集合**，实体 hash 从此再无人引用。现在会先 `SMEMBERS` 再逐个 DEL 实体 |
| `CleanupScheduler` 级联 | 混合模式下清理 Redis 后拿 `removedXids` 调 `UndoRepository#deleteByXid`，两边各清一半才是一次完整回收 |

### 19.3 配置与运维

```yaml
easy-at:
  storage:
    type: hybrid      # jdbc / redis / hybrid / file
  lock:
    type: redis
  cleanup:
    enabled: true
    committed-retention: 7d
    rolled-back-retention: 30d
  redis:
    ttl: 30d          # 兜底 TTL
```

- 业务库只需 `db/{mysql,postgresql}/undo-only.sql`（只要 undo 表）。
- 纯 Redis 模式下 `easy-at.cleanup.enabled=true` **必须开**——否则等于回到键只增不减的旧状态。
- 运维侧（`RUNBOOK.md` §5）增加两件事：① 看 `easy_at_undo_log` 与 Redis `easy-at:cleanup:index` 的
  规模；② 纯 Redis 模式用 `redis-cli --scan --pattern 'easy-at:*'` 抽查是否有长期未回收的孤儿键。

### 19.4 已知限制

- 混合模式目前按**单个业务 DataSource** 装配 `JdbcUndoRepository`；多数据源场景需要为每个资源各配一个
  undo 存储，尚未实现（会先走主数据源）。
- `storage.type=redis` 的原子性问题（§18.4 问题一）**没有也不会被修复**——那是存储选型的固有属性，
  只能靠 `hybrid` 绕开。
- Redis 清理只在**终态**事务上生效；`MANUAL_INTERVENTION` / `DIRTY_WRITE` 会一直保留并持续出现在
  清理扫描里，这正是想要的行为（等人工处理）。

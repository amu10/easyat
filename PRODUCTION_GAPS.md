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
> 当前用例规模：**默认构建 59 个**（H2）+ **真实库 9 个**（`-Pdbtest`）。

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
| 2 | 用真实业务 SQL 过一遍 AT 能力边界 | AT 刻意只接受"按主键、单行、值为 `?`"的 DML，批量/JOIN/子查询直接抛 `UnsupportedAtSqlException`。别到线上才发现大片 SQL 被拒 |
| 3 | 按 `RUNBOOK.md` 灰度并影子对账 ≥7 天 | 核心一致性逻辑改完即发、零 soak time。盯 `easy_at_global` 残留、锁泄漏、`MANUAL_INTERVENTION` 计数 |

另：`~/.m2/settings.xml` 里明文存了 Central Token 与 GPG 口令，建议轮换。

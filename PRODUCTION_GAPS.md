# easyAt 生产能力缺口清单

> 基于 `DESIGN.md` 0.2 设计稿和当前代码整理。  
> 更新日期：2026-09-23  
> 当前结论：P0（单服务投产底线）、P1（可运维上线）、P2（跨服务生产）以及 P3 中的 Redis 存储、加解密/脱敏 SPI 自动装配、WebClient 传播与管理 UI 均已完成实现，项目 `mvn clean compile` 全模块通过、JDBC 模块 H2 集成用例（含 codec SPI 单测）全绿。剩余缺口仅为部分 DML 能力（生成主键/批处理仍按设计拒绝）以及真实数据库/并发/故障注入测试（需 Docker + 真实实例，沙箱离线环境暂无法执行）。

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
| P3 其余 / 9 测试 | ⚠️ 部分 | 生成主键/`executeBatch`/多表等仍按设计拒绝；MySQL/PostgreSQL/Redis Testcontainers 与并发/故障注入测试待补充（管理 UI 已实现，见 6；`UndoDataEncryptor`/`UndoDataMasker` SPI 已自动装配，见 2.5 / 3.4）|

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

### 4.3 分支状态原子性

需要确保分支注册、undo 写入和业务 DML 位于同一个本地事务中，并在本地提交后可靠推进分支状态。

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

当前自动测试主要是 H2 单元/集成测试，还需要：

- MySQL Testcontainers。
- PostgreSQL Testcontainers。
- Redis Testcontainers。
- 多实例并发恢复测试。
- 同一主键锁竞争和租约续期测试。
- 写 undo 前后、DML 前后和提交前后的故障注入。
- 网络超时、回调重复、服务重启测试。
- 脏写、人工重试和幂等性测试。
- 大字段、时间、二进制和特殊 JDBC 类型测试。
- Starter 自动配置和多 DataSource 测试。
- 性能、连接池压力和长事务测试。

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

- 金融、订单、库存等关键一致性业务。
- 多实例自动恢复场景。
- 需要完整跨服务提交/回滚的分布式事务。
- 无法接受人工数据修复的生产系统。

完成 P0 并通过真实数据库、并发和故障注入测试后，才建议评估单服务小流量灰度；完成 P1/P2 后，才应评估跨服务生产部署。

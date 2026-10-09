# easyAt 项目长期约定

## 构建/验证（关键，务必照做）

- 离线构建：`mvn -o`（本地 m2 无网络，别去掉 -o）。mvn 在 `C:\ProgramData\chocolatey\...\mvn.cmd`。
- **Windows Defender 会锁 `.class` 致编译间歇失败**（报 "cannot access ...X.class"）：
  编译前先 `Add-MpPreference -ExclusionPath 'D:\projectspace\research\easyAt'`，并给 `clean compile` 加 3~5 次重试循环。
- **PowerShell 工具 stdout 不被捕获**：结果用 `Out-File -FilePath x.txt -Encoding ascii` 写文件再 Read。
  **必须 `-Encoding ascii`**，默认 UTF-16 会被 Read 判成二进制、`Get-Content` 读成空。
- 前台 PowerShell 有 ~120s 上限：`clean compile`（~45s）与 `-pl easy-at-jdbc -am test` 分开跑，别合一条。
- Bash 工具损坏（dirname/ls 缺失、safe-bin shim 坏）：用 PowerShell 执行命令。
- **离线 `-pl X` 会用到 m2 里的旧 easy-at-core jar**（不含新改的方法）→ 必须加 `-am` 一起构建依赖模块。
- 验证基线：**默认 12 模块 59 用例全绿**（49 jdbc + 8 spring + boot2/boot3 各 1）；
  真实库另需 `mvn -Pdbtest test`（13 模块 9 用例）。
- **surefire 默认只匹配 `*Test.java`**：`*IT.java` 静默不跑还显示 BUILD SUCCESS，必须显式配 `<includes>`。
- **Testcontainers 实际可用**（WSL2 后端）：`docker` CLI 走 npipe 不通，但 3306/6379 由
  `com.docker.backend`/`wslrelay` 中继。判断端口占用一律用 PowerShell `Get-NetTCPConnection`——
  Git Bash 的 `/dev/tcp` 探测在 Windows 上不可靠。缺的 jar 可从内网 Nexus 192.168.110.237:8181 拉取
  （去掉 `-o` 即可联网，`-Pdbtest` 首次需联网下载 testcontainers）。

## 架构/依赖约束

- `spring-webflux` 不在本地 m2：任何 WebClient/响应式集成必须**反射实现**（见 `WebClientPropagator`），
  禁止加编译期依赖，否则离线编译直接失败。
- 加解密/脱敏 SPI：`JacksonUndoDataCodec(encryptor, masker)`；`UndoDataCodec` 有默认 `toDiagnosticString`。
  Starter 把 `UndoDataEncryptor`/`UndoDataMasker` 自动装配进 `UndoDataCodec` Bean，注入 JDBC/Redis 仓库与 `ManagementService`。
- 管理端点前缀 `/_easy-at/v1`，UI 在 `/_easy-at/v1/ui`（token 走 `X-EasyAt-Token` header）。
- 传输头统一走 `AtTransportHeaders`（XID/DEADLINE/SOURCE/SIGNATURE），不要手写字符串。

## AT 模式 SQL 支持（写业务/示例代码必读，2026-10-09 改）

两条路径：
1. **严格主键路径**：`WHERE 主键=?` / `主键 IN (?,...)`，靠参数定位行，零额外查询。
2. **通用快照路径**（`GenericSnapshotPlanner` + `GenericAtExecutor`）：其余语句把原语句的
   `FROM/WHERE/ORDER BY/LIMIT` 拼成 `SELECT <alias>.* FROM ... WHERE <原 where>` 先读出受影响行，
   再逐行建 undo + 逐行加锁。子查询/EXISTS/JOIN/任意谓词/跨列 SET/别名/多行 INSERT 都走这条。
   严格路径抛 `UnsupportedAtSqlException` 才回退；两条都失败时合并报错原因。

仍拒绝（正确性边界，不是保守）：多目标表 DML、`SET pk=?`、无主键/复合主键表、`INSERT ... SELECT`、
依赖自增主键却不写主键列、DDL/TRUNCATE/MERGE/存储过程。

关键实现约束：
- 计数参数**必须用 `net.sf.jsqlparser.util.deparser.ExpressionDeParser`**（配 `SelectDeParser`）；
  `ExpressionVisitorAdapter` 不下钻 SubSelect，会把子查询里的 `?` 漏掉导致绑定错位。
- `UPDATE a, b SET ...` 的第二个表在 `startJoins` 且渲染不带 JOIN 关键字，拼 FROM 要补逗号。
- `Insert.getValues()` 遇 `INSERT...SELECT` 抛 CCE，先判 `getSelect() instanceof Values`。
- `Table.getAlias()` 返回 `Alias` 对象，不是 String。
- 要触发回退，识别器必须抛 `UnsupportedAtSqlException`；抛 `AtException` 会直接打断业务
  （`RecognizerSupport#tableName` 的别名分支、`assertPrimaryKey` 都已改）。
- UPDATE：`SET 列 = ?`——**不接受** `SET balance=balance-?`（表达式）与 `SET status='PAID'`（字面量）；
  `WHERE 主键 = ?`，且 WHERE 的 `?` 必须是最后一个参数（生成器按 `columns.size()+1` 取 key）。
- DELETE：`WHERE 主键 = ?`。INSERT：必须显式写出主键列且值全为 `?`（undo 是按主键 DELETE）。
- 表必须有**单列主键**；不支持 JOIN/子查询/批量/多表/表别名。
- 故业务写法固定为「先 SELECT 旧值 → Java 里算新值 → `UPDATE ... SET col=? WHERE pk=?` 整体写入」；
  因有全局行锁（`easy_at_lock`）该写法并发安全。参见 `easy-at-example-local/TransferService`。

## 示例工程集成坑（Spring Cloud Alibaba）

- Nacos 服务发现地址必须写 `spring.cloud.nacos.discovery.server-addr`；写 `spring.cloud.nacos.server-addr`
  会让 `NacosDiscoveryProperties.serverAddr` 为 null → 注册报 `Client not connected, current status:STARTING`。
- 示例 pom 必须导入 `spring-boot-dependencies` BOM（放最后）以锁定 jackson 2.17.2；
  nacos-client 2.4.x 需要 jackson-core ≥ 2.15，否则 `NoClassDefFoundError: StreamConstraintsException`。
- 各示例端口：local 18080 / caller 18181 / callee 18182（README 曾误写 18081/18082，已修正）。

## 本机真实实例（用户提供的账密，2026-10-09）

- MySQL `127.0.0.1:3306` root/root，**5.7.44**（`test_sport` 是 ggsport 真实业务库，禁止把测试指过去；
  已建专用空库 `easy_at_it`）。
- Redis `127.0.0.1:6379` 口令 `Redis,./`（未认证报 NOAUTH）。
- 3306/6379 均由 `wslrelay` + `com.docker.backend` 中继；Testcontainers 可用（本机无 PG，PG 用例走容器）。
- 跑真实库 IT：`mvn -o -Pdbtest -pl easy-at-db-tests -am test -Deasyat.it.mysql.url=... -Deasyat.it.redis.database=5`
  （**必须**用独立 schema + 非 0 redis db，用例会 DROP TABLE 与 FLUSHDB）。详见 `easy-at-db-tests/README.md`。

## 交付/运维约定（2026-10-09 起）

- **投产手册在 `RUNBOOK.md`**：灰度 5 阶段、告警阈值、三类人工修复流程、回滚预案。改对账或管理端点时同步它。
- 影子对账三入口：端点 `GET /_easy-at/v1/reconciliation`、CLI `io.github.easyat.jdbc.ReconciliationCli`、
  SQL `easy-at-jdbc/src/main/resources/db/{mysql,postgresql}/reconciliation.sql`。
- **Redis 模块禁止使用 jedis 的写命令**（`hset/sadd/del/exists/set`）：它们在 4.x 起返回值从
  `Long` 改成 `long`，会造成运行期 `NoSuchMethodError`。写一律走 `eval(String, List, List)` + Lua，
  读只用 `hget/hgetAll/smembers/get`。改完用 `-Pjedis3/-Pjedis5/-Pjedis6` 跑 `RedisRealStorageIT` 回归。
- 分支唯一性约束 `(xid, resource_id)`：建表脚本已含，存量库跑
  `db/{mysql,postgresql}/migration/v0.1.2__branch_unique.sql`（先去重再加约束）。

## 分支注册必须走业务连接（2026-10-09）

- `SqlUndoLogGenerator#capture(Connection c, ...)` 里的 `c` 是 **物理连接**（`target.getConnection()`），
  不经代理 → 在它上面直接跑框架 SQL 不会递归触发分支注册。
- 分支注册经 `BranchRegistrar#register(xid, resourceId, Object conn)` → `BranchRepository#registerIn`
  落到业务连接，与业务 DML / undo log 同事务；业务回滚则分支行一并消失。
- Redis/File 存储没有本地事务概念，`registerIn` 默认返回 false → 自动回退独立连接注册。
- **PG 陷阱**：业务事务内任何一条语句报错都会把整个事务标记 aborted。所以分支 INSERT 前先在**业务
  连接上查重**，撞唯一键冲突时 `rollback(savepoint)`，不要让异常冒到业务事务里。

## 状态（2026-09-23）

- P0/P1/P2/Redis/加解密脱敏 SPI/WebClient/管理 UI 均已实现；`mvn clean compile` 通过、jdbc 10 用例全绿。
- 剩余仅：生成主键/executeBatch/多表 DML（按设计拒绝）、真实数据库/并发/故障注入测试（需 Docker，沙箱离线不可做）。

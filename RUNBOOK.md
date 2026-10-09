# easyAt 投产运维手册

面向"决定要上生产"的人。三件事：怎么灰度、每天对账看什么、出问题怎么人工修。

---

## 0. 上生产前的硬前提

| # | 前提 | 检查方式 |
|---|---|---|
| 1 | 用的是**包含本次修复**的版本（> 0.1.1） | `mvn dependency:tree \| grep easy-at`；0.1.1 及更早**不要上** |
| 2 | 已跑过 `db/<mysql\|postgresql>/migration/v0.1.2__branch_unique.sql` | 见下 §1 |
| 3 | 管理端点已开启且配了 token | `easy-at.management.enabled=true` + `easy-at.management.token` |
| 4 | 有对账手段（端点或 CLI 或 SQL 三选一） | 见 §2 |
| 5 | 有人值班、有改库权限 | 出问题时要在 30 分钟内手工改数据 |

---

## 1. 建表与迁移

新建库：直接跑 `easy-at-jdbc/src/main/resources/db/<mysql|postgresql>/easy-at.sql`（已含 `UNIQUE(xid, resource_id)`）。

已在跑的库（`easy_at_branch` 缺唯一约束）：

```shell
# MySQL —— 脚本会先去重再加约束，可重复执行
mysql -h <host> -u <user> -p <db> < easy-at-jdbc/src/main/resources/db/mysql/migration/v0.1.2__branch_unique.sql

# PostgreSQL
psql -h <host> -U <user> -d <db> -f easy-at-jdbc/src/main/resources/db/postgresql/migration/v0.1.2__branch_unique.sql
```

> ⚠️ 去重步骤会**删除**同一 `(xid, resource_id)` 下多余的分支行（保留 `branch_id` 最小的一条）。
> 不确定影响面时，先跑脚本里被注释掉的那条 `SELECT ... HAVING COUNT(*)>1` 看看有多少。

---

## 2. 影子运行对账（≥7 天）

三个入口，任选其一，建议**端点做日常告警、SQL 做人工核实**：

### 2.1 管理端点（推荐，接监控）

```shell
curl -H "X-EasyAt-Token: <token>" http://<host>:<port>/_easy-at/v1/reconciliation
```

返回：

```json
{
  "level": "OK|WARN|CRITICAL",
  "healthy": true,
  "problemCount": 0,
  "counts": {
    "scannedTransactions": 1234, "scannedLocks": 3,
    "activeTimedOut": 0, "rollingBackStuck": 0, "committingStuck": 0,
    "rollbackFailed": 0, "dirtyWrite": 0,
    "manualIntervention": 0, "leakedLocks": 0, "hangingBranches": 0
  },
  "samples": { "manualIntervention": ["xid-..."], "leakedLocks": ["db/account/1 <- xid ..."] }
}
```

### 2.2 独立 CLI（不用启应用，可直接连影子库）

```shell
java -cp "easy-at-jdbc-<ver>.jar:mysql-connector-j-8.4.0.jar" \
     io.github.easyat.jdbc.ReconciliationCli \
     jdbc:mysql://127.0.0.1:3306/<db> <user> <password>

# 退出码：0=健康  2=有问题需要处理  1=连不上库
```

### 2.3 纯 SQL（DBA 友好）

```shell
mysql -h <host> -u <user> -p <db> < easy-at-jdbc/src/main/resources/db/mysql/reconciliation.sql
psql  -h <host> -U <user> -d <db> -f easy-at-jdbc/src/main/resources/db/postgresql/reconciliation.sql
```

### 2.4 告警阈值

| 指标 | 阈值 | 级别 | 含义 |
|---|---|---|---|
| `manualIntervention` | > 0 | **CRITICAL** | 框架承认搞不定，必须人工修数据 |
| `dirtyWrite` | > 0 | **CRITICAL** | 回滚时发现行已被别人改过 |
| `leakedLocks` | > 0 | **CRITICAL** | 同行的其他事务会被永久拒绝 |
| `hangingBranches` | > 0 | **CRITICAL** | 跨服务：分支已终态、全局仍在途 |
| `committingStuck` | > 0 | WARN | 本地已提交但全局未收敛 |
| `rollingBackStuck` | > 0 | WARN | 回滚卡住且没有有效租约 |
| `activeTimedOut` | > 0 持续 10 分钟 | WARN | 超时事务没被恢复调度接管 |
| `rollbackFailed` | > 0 | WARN | 补偿反复失败（会自动重试，但要看） |

**7 天内上述各项持续为 0，才允许进入下一步放量。**

Redis 存储补充说明：`heldLocks()` 目前只有 JDBC 实现，Redis 侧的锁泄漏用下面这段查：

```shell
redis-cli -a '<password>' --scan --pattern 'easy-at:lock:*' | while read k; do
  echo "$k -> $(redis-cli -a '<password>' hget "$k" xid)"
done
```

---

## 3. 灰度步骤

| 阶段 | 动作 | 通过标准 |
|---|---|---|
| **阶段 0 影子** | 接生产库只读副本，不开全局事务（或只对内部账号开启） | 无异常日志，SQL 兼容性无 `UnsupportedAtSqlException` |
| **阶段 1 单实例** | 一个实例 + 非核心接口 + 1% 流量 | 7 天对账全 0；无 `GlobalLockConflictException` 突增 |
| **阶段 2 小流量** | 非核心接口 10% → 50% | 同上；P99 无退化；`easy_at_global` 行数稳定不增长 |
| **阶段 3 扩实例** | 2~3 实例 | 并发用例已覆盖；观察是否出现双补偿（对账里的 dirtyWrite） |
| **阶段 4 核心链路** | 资金/订单/库存 | 前置项已齐（唯一约束 §14.2、分支注册原子性 §15、通用 SQL 路径 §17）；但仍要求先完成阶段 0~3 的完整观察期，且必须有 ≥7 天压测/soak 数据（见 `PRODUCTION_GAPS.md` §18.1） |

每一次放量前：备份 `easy_at_global` / `easy_at_undo_log` / `easy_at_lock` 三张表。

---

## 4. 人工修复入口（全程保留，不要关）

管理端点前缀 `/_easy-at/v1`，token 走 `X-EasyAt-Token` 头；UI 在 `/_easy-at/v1/ui`。

```shell
# 1) 看某个事务的 undo before/after image（决定最终数据以哪个为准）
curl -H "X-EasyAt-Token: <t>" http://host/_easy-at/v1/transactions/<xid>

# 2) 重新驱动（把未终态事务拉回 ROLLING_BACK，让恢复调度再跑一次）
curl -X POST -H "X-EasyAt-Token: <t>" \
     "http://host/_easy-at/v1/transactions/<xid>/retry?operator=zhangsan&reason=stuck"

# 3) 立即回滚
curl -X POST -H "X-EasyAt-Token: <t>" \
     "http://host/_easy-at/v1/transactions/<xid>/rollback?operator=zhangsan&reason=dirty-write"

# 4) 按状态列表
curl -H "X-EasyAt-Token: <t>" "http://host/_easy-at/v1/transactions?status=MANUAL_INTERVENTION&limit=100"

# 5) 审计记录（谁在什么时候动了什么）
curl -H "X-EasyAt-Token: <t>" http://host/_easy-at/v1/audit
```

### 4.1 MANUAL_INTERVENTION 的修复流程

1. `GET /transactions/<xid>` 看 undo 的 `before` / `after` image。
2. 到业务库 `SELECT` 该行当前值，判断它等于 after（已提交）还是 before（已回滚）。
3. 与业务方确认最终值，直接改数据。
4. 用 `POST /transactions/<xid>/retry` 把事务拉回 `ROLLING_BACK` 让它收敛到终态；
   若框架反复失败，改完数据后把 `easy_at_global.status` 直接置为 `ROLLED_BACK` 并清锁（见 4.3）。

### 4.2 DIRTY_WRITE 的修复流程

含义：回滚时该行已经不等于 after image —— 说明有人绕过了全局锁改了它。**不能无脑重放 undo**，
必须先比对 before/after/当前值三份数据再决定。其余同 4.1 第 3~4 步。

### 4.3 锁泄漏的修复

确认该 xid 的全局事务已终态后：

```sql
DELETE FROM easy_at_lock WHERE xid = '<xid>';
```

Redis 存储：`redis-cli --scan --pattern 'easy-at:lock:byXid:<xid>'` 对应的 set 与 key 删除。

---

## 5. 上线后每天必看

- [ ] `/_easy-at/v1/reconciliation` 各项为 0
- [ ] `easy_at_global` 非终态行数（ACTIVE/COMMITTING/ROLLING_BACK）不单调增长
- [ ] `easy_at_lock` 行数随业务量起伏，不单调增长（增长 = 泄漏）
- [ ] 日志里没有 `UnsupportedAtSqlException`（说明有业务 SQL 不在支持范围内，会被直接拒绝）
- [ ] 没有 `GlobalLockConflictException` 突增（说明热点行冲突加剧）

### 5.1 混合存储（hybrid）额外检查

| 对象 | 怎么看 | 异常信号 |
|---|---|---|
| 业务库 `easy_at_undo_log` | `SELECT COUNT(*) FROM easy_at_undo_log WHERE created_at < NOW() - INTERVAL 7 DAY` | 行数不降 ⇒ Redis 侧清理没跑或级联没生效 |
| Redis `easy-at:cleanup:index` | `redis-cli zcard easy-at:cleanup:index` | 只增不减 ⇒ `cleanup.enabled` 没开，或终态事务过不了保留期 |
| Redis 孤儿键 | `redis-cli --scan --pattern 'easy-at:undo:*' \| wc -l` | 远大于在途事务数 ⇒ 清理被跳过，查 `easy-at-cleanup` 线程日志 |

### 5.2 纯 Redis 存储（`storage.type: redis`）

- `maxmemory-policy` 必须是 `noeviction`；否则内存满时 Redis 会**随机淘汰未收敛事务的 undo**，
  表现为：事务卡在非终态、回滚时报找不到 undo。
- 每天 `redis-cli info memory` 看 `used_memory` 趋势；配合 §5.1 的 zset 计数确认回收在跑。
- 该模式下 undo **不与业务本地事务原子提交**（`PRODUCTION_GAPS.md` §18.4 问题一），
  业务回滚后 undo 仍会留下；如需强一致请切到 `hybrid`。

---

## 6. 回滚预案

出问题时按这个顺序停：

1. 关开关：`easy-at.management.enabled=false` 之外的总开关是把 `@EasyAtTransactional` 摘掉
   （或 `easy-at.exclude-resources` 排除该数据源）→ 新流量不再进全局事务。
2. 存量事务：用 §4 的端点逐个收敛；来不及就按 §4.1 手工改数据。
3. 确认 `easy_at_global` 无在途事务后，再决定是否清理三张表。

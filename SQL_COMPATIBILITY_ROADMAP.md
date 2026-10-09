# easyAt SQL 兼容性演进计划

> 目标：参考 Seata AT 的“SQL 识别 + 行镜像 + Undo 执行器”分层，在保持保守失败策略的前提下，逐步扩大 SQL 支持范围。

## 1. 基本原则

1. Undo 基于 before image 直接恢复旧值，不通过正向表达式推导反向表达式。
2. 只有能准确确定表、主键、受影响行和 JDBC 参数顺序的 SQL 才允许执行。
3. 业务数据、Undo Log 必须使用同一条本地连接和本地事务。
4. 回滚前必须使用 after image 做脏写检查。
5. 不支持的 SQL 在业务 DML 执行前失败，不能静默降级。
6. 每个阶段独立交付，必须包含单元测试、MySQL 示例和兼容矩阵更新。

## 2. 阶段总览

| 阶段 | 内容 | 状态 | 主要产物 |
|---|---|---|---|
| 0 | 单行主键 DML、同列 `+?/-?` | 已完成 | 当前 `SqlUndoLogGenerator` 与回滚测试 |
| 1 | AST 表达式分析和参数定位 | 已完成 | `UpdateExpressionAnalyzer`、字面量及算术表达式支持 |
| 2 | SQL Recognizer / Executor 分层 | 已完成 | UPDATE/DELETE/INSERT 独立识别器与执行器 |
| 3 | 常用函数与方言能力矩阵 | 已完成 | 函数白名单、MySQL/PostgreSQL 方言测试 |
| 4 | 多行 UPDATE/DELETE | 已完成（主键 IN） | 多行镜像、多锁键、影响行数上限 |
| 5 | JDBC Batch | 已完成 | 参数批次快照、批量 Undo 项 |
| 6 | 多数据源 Undo 路由 | 待实施 | 协调库与业务库分离、跨资源逆序回滚 |
| 7 | 通用快照路径（子查询 / JOIN / 任意条件） | 已完成 | `GenericSnapshotPlanner` + `GenericAtExecutor`、多行 INSERT、参数下钻计数 |

## 3. 阶段 1：表达式分析和参数定位

### 范围

- 从 UPDATE AST 统计每个 SET 表达式使用的 JDBC 参数数量。
- WHERE 主键参数位置不再假设为“更新列数 + 1”。
- 支持：
  - `column=?`
  - `column=column+?`、`column=column-?`
  - `column=column*?`、`column=column/?`、`column=column%?`
  - 多层同列算术，例如 `balance=balance*?+?`
  - 字符串、数字、NULL、日期时间字面量
- 继续拒绝跨列计算、子查询和未识别表达式。

### 验收条件

- SET 中零个、一个、多个参数时都能找到正确的 WHERE 参数。
- before/after image 正确。
- 回滚恢复原值。
- 跨列表达式在执行业务 SQL 前失败。

## 4. 阶段 2：Recognizer / Executor 分层

把当前大类拆分为：

```text
AtSqlRecognizer
  ├─ UpdateRecognizer
  ├─ DeleteRecognizer
  └─ InsertRecognizer

AtStatementExecutor
  ├─ UpdateAtExecutor
  ├─ DeleteAtExecutor
  └─ InsertAtExecutor
```

Recognizer 只负责解析和生成执行计划；Executor 负责镜像、锁、Undo 和业务执行前后钩子。现有对外行为保持不变。

### 验收条件

- `SqlUndoLogGenerator` 不再包含三种 DML 的全部细节。
- 每种 DML 有独立测试类。
- MySQL/PostgreSQL 方言逻辑不进入通用执行器。

## 5. 阶段 3：函数与数据库方言

先采用白名单，不开放任意函数：

- 时间：`CURRENT_TIMESTAMP`、`NOW()`；
- 空值：`COALESCE(column, ?)`；
- 数值：由各方言明确支持的安全标量函数。

函数不需要反向计算，但必须能统计参数并确认不包含子查询或其他表引用。

### 验收条件

- 建立公开 SQL 兼容矩阵。
- 每个方言分别运行集成测试。
- 未进入白名单的函数继续快速失败。

## 6. 阶段 4：多行 UPDATE/DELETE

当前安全范围限定为显式主键 `IN (?,...)`。执行前按参数得到全部主键，排序后获取多个全局锁并保存逐行 before image；执行后逐行查询 after image。

必须增加：

- `max-affected-rows` 安全上限；
- 主键排序，降低多事务锁顺序死锁；
- 多行 `TableRecords` 数据模型；
- 回滚逐行或分批执行；
- 主键 IN 参数重放；
- 执行结果行数与镜像行数校验。

默认支持最多 100 行；用户可通过 `easy-at.sql.max-affected-rows` 调整安全上限。任意范围条件留待后续独立设计。

## 7. 阶段 5：JDBC Batch

代理 `addBatch/clearBatch/executeBatch`，每组参数形成独立执行计划和 Undo 项。任何一组捕获失败时，整批在执行前失败。

同一个 Batch 内暂不允许重复修改同一个资源、表和主键，避免最终 after image 无法对应中间状态。

### 验收条件

- 参数快照不会被下一次 `setXxx` 覆盖。
- 返回行数与 Undo 项数量可校验。
- 本地事务回滚会同时撤销整批 Undo Log。

## 8. 阶段 6：多数据源

区分协调数据源和业务资源数据源：

- 协调库：`easy_at_global`、`easy_at_branch`；
- 每个业务库：本地 `easy_at_undo_log`；
- 锁：JDBC 或 Redis；
- 回滚：按 XID 和 resourceId 从各业务库加载 Undo，再按全局序号逆序执行。

这一阶段需要为 Undo 增加全局序号，不能依赖不同数据库的本地时间排序。

## 8.1 阶段 7：通用快照路径

此前不支持的语句一律抛 `UnsupportedAtSqlException`。阶段 7 换了个思路：**不再要求"能理解 WHERE"，
而是把原语句的 FROM / WHERE / ORDER BY / LIMIT 拼成一条 SELECT，先读出所有会被影响的行**。

```
UPDATE account SET balance=? WHERE id IN (SELECT aid FROM frozen WHERE status=?)
 ->
SELECT account.* FROM account WHERE id IN (SELECT aid FROM frozen WHERE status = ?)
```

读到主键后逐行抢全局锁、逐行建 undo：

- UPDATE 的 undo = 把整行（除主键）改回 before image；
- DELETE 的 undo = 按 before image 插回整行。

### 关键实现点

1. **参数偏移**：UPDATE 的 `SET` 参数排在 `WHERE` 之前，而快照 SELECT 里没有 `SET`，必须跳过它们。
   计数用 `ExpressionDeParser` 而不是 `ExpressionVisitorAdapter`——后者不会下钻子查询，
   会把 `WHERE id IN (SELECT ... WHERE x=?)` 里的 `?` 漏掉，导致参数绑定错位。
2. **逗号连接要补逗号**：`UPDATE a, b SET ...` 的第二个表落在 `startJoins` 里且渲染出来不带
   `JOIN` 关键字，直接拼接会得到 `FROM a b` 这种语法错误。
3. **多目标表仍然拒绝**：`UPDATE a,b SET a.x=?, b.y=?` 无法按单个表的行建 undo。
4. **不给主键赋值**：`SET pk=?` 会让行在 undo 时找不到，明确拒绝。
5. **行数上限照旧**：超出 `max-affected-rows` 就整条拒绝，不截断。

### 验收条件

- 子查询 / EXISTS / JOIN / 任意条件谓词的 UPDATE、DELETE 都能回滚且数据还原。
- 多行 INSERT（`VALUES (...),(...)`，可混用字面量）回滚后行被删掉。
- H2 上 11 个用例（含 planner 层的 SQL 构造断言）；真实 MySQL / PostgreSQL 上 3 个 IT。

## 9. 不在当前路线内的 SQL

- DDL；
- 存储过程；
- MERGE；
- 多目标表 UPDATE/DELETE（单目标表带 JOIN 已支持）；
- 无稳定主键的表、复合主键表；
- `INSERT ... SELECT` 与依赖自增主键的 INSERT（执行前拿不到主键）。

这些能力只有在能证明镜像、锁和回滚正确性后才能单独立项。

## 10. 每阶段交付流程

1. 更新本计划中的状态。
2. 完成代码和自动化测试。
3. 更新 README、CODE_GUIDE 和示例。
4. 执行 `mvn clean verify`。
5. 给出新增支持、仍不支持及升级注意事项。

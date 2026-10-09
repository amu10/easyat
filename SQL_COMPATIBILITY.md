# easyAt SQL 兼容矩阵

AT 有两条生成 undo 的路径：

1. **严格主键路径**：`WHERE 主键 = ?` / 有上限的 `主键 IN (?,...)`。靠参数反推受影响行，零额外查询。
2. **通用快照路径**（阶段 7）：其它语句一律先把原语句的 `FROM / WHERE / ORDER BY / LIMIT` 拼成一条
   `SELECT <表>.* ...`，读出所有会被影响的行，再逐行建 undo 并逐行抢全局锁。

第二条路径不需要框架"理解"谓词——数据库自己会算出受影响的行，因此子查询、JOIN、任意条件都能支持。

## UPDATE 赋值

| 形式 | 支持 | 走哪条路径 |
|---|---:|---|
| `column=?`、标量字面量、`NULL` | ✅ | 严格 |
| 同列 `+ - * / %` 组合 | ✅ | 严格 |
| `COALESCE(column, ?)`、`ABS(column)` 及函数白名单内 | ✅ | 严格 |
| `CURRENT_TIMESTAMP`、`NOW()` | ✅ | 通用（`SET` 值不需要被理解） |
| 跨列计算 `balance=credit-?` | ✅ | 通用 |
| `SET x=(SELECT ...)` 标量子查询 | ✅ | 通用 |

Undo 从不反向计算表达式——它直接把 before image 写回去，所以 `SET` 里是什么都不影响正确性。

## WHERE 与行数

| 形式 | 支持 | 说明 |
|---|---:|---|
| `WHERE pk=?` | ✅ | 严格路径 |
| `WHERE pk IN (?,...)` | ✅ | 严格路径，受行数上限约束 |
| 任意条件（范围、OR、`LIKE`、函数） | ✅ | 通用快照 |
| `WHERE id IN (SELECT ...)` | ✅ | 通用快照 |
| `WHERE EXISTS (SELECT ...)` | ✅ | 通用快照 |
| `ORDER BY ... LIMIT ...` | ✅ | 通用快照，快照 SQL 原样带上，保证选中同一批行 |
| 无 `WHERE` 的全表 UPDATE/DELETE | ✅ | 通用快照，但极易撞行数上限 |

行数上限（默认 100，可被 `max-affected-rows` 覆盖）对两条路径都生效：

```yaml
easy-at:
  sql:
    max-affected-rows: 20
```

超限不是"截断"，而是**整条语句拒绝执行**——宁可报错，也不能留下没有 undo 的写。

## JOIN 与多表

| 形式 | 支持 | 说明 |
|---|---:|---|
| `UPDATE a JOIN b ON ... SET a.x=? WHERE ...` | ✅ | MySQL 写法，快照 `SELECT a.* FROM a JOIN b ...` |
| `UPDATE a, b SET a.x=? WHERE a.id=b.aid` | ✅ | 逗号连接 |
| `UPDATE a SET ... FROM b WHERE ...` | ✅ | PostgreSQL 写法 |
| `DELETE a FROM a JOIN b ON ... WHERE ...` | ✅ | MySQL 多表 DELETE |
| `UPDATE a,b SET a.x=?, b.y=?` | ❌ | **多目标表**：无法按单个表的行建 undo |
| `DELETE a,b FROM ...` | ❌ | 同上 |

## INSERT

| 形式 | 支持 | 说明 |
|---|---:|---|
| `INSERT INTO t(cols) VALUES (?,...)` | ✅ | undo 是按主键 DELETE |
| `INSERT INTO t(cols) VALUES (?,...),(?,...)` | ✅ | 多行，每行一条 undo |
| 字面量与 `?` 混用 | ✅ | 字面量不消耗占位符下标 |
| `INSERT ... SELECT` | ❌ | 被插入的行执行前不存在，拿不到主键就无法补偿 |
| `INSERT ... ON DUPLICATE KEY UPDATE` / `ON CONFLICT` | ❌ | 语义不是纯插入 |
| 依赖自增主键、INSERT 里不写主键列 | ❌ | 同上：执行前拿不到主键 |

## JDBC Batch

- 支持 `addBatch()`、`clearBatch()`、`executeBatch()` 和 `executeLargeBatch()`。
- 每次 `addBatch()` 都复制当前参数，不会被后续 `setXxx` 覆盖。
- 批次返回数量必须与 Undo 计划数量一致。
- 同一个 Batch 暂不允许重复修改同一行。

## 仍然拒绝（与安全相关，不是保守）

- 无主键表、复合主键表——没有主键就无法定位行。
- 多目标表 DML。
- 给主键列赋值（`SET pk=?`）——改完就找不到原来的行了。
- DDL、`TRUNCATE`、`MERGE`、存储过程调用。

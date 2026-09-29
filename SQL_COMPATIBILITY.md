# easyAt SQL 兼容矩阵

## UPDATE 赋值

| 形式 | Generic | MySQL | PostgreSQL |
|---|---:|---:|---:|
| `column=?` | ✅ | ✅ | ✅ |
| 标量字面量、`NULL` | ✅ | ✅ | ✅ |
| 同列 `+ - * / %` 组合 | ✅ | ✅ | ✅ |
| `COALESCE(column, ?)` | ✅ | ✅ | ✅ |
| `ABS(column)` | ✅ | ✅ | ✅ |
| `CURRENT_TIMESTAMP` | ❌ | ✅ | ✅ |
| `NOW()` | ❌ | ✅ | ✅ |
| 跨列计算 | ❌ | ❌ | ❌ |
| 未列入白名单的函数 | ❌ | ❌ | ❌ |

函数白名单只用于判断能否安全定位 JDBC 参数和列引用。Undo 不反向计算函数，而是直接写回 before image。

## WHERE 与行数

| 形式 | 默认配置 | `max-affected-rows > 1` |
|---|---:|---:|
| `WHERE pk=?` | ✅ | ✅ |
| `WHERE pk IN (?,...)` | ❌ | ✅，且参数数量不能超过上限 |
| 非主键、范围、OR、子查询 | ❌ | ❌ |

配置示例：

```yaml
easy-at:
  sql:
    max-affected-rows: 20
```

## JDBC Batch

- 支持 `addBatch()`、`clearBatch()`、`executeBatch()` 和 `executeLargeBatch()`。
- 每次 `addBatch()` 都复制当前参数，不会被后续 `setXxx` 覆盖。
- 批次返回数量必须与 Undo 计划数量一致。
- 同一个 Batch 暂不允许重复修改同一行。

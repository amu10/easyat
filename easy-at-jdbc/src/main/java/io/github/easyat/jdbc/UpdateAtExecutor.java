package io.github.easyat.jdbc;

import io.github.easyat.core.AtException;
import io.github.easyat.core.RowImage;
import io.github.easyat.core.UndoRecord;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * UPDATE（严格主键路径）的执行器（对应 UpdateRecognizer.Plan）。
 *
 * <p>UPDATE 的 undo 是「把整行（除主键）改回 before image」。先按主键参数收集待更新主键集合 （按值排序避免死锁），逐行抢全局锁、快照 before image、拼
 * UPDATE 反向 SQL 并落库。 中途失败整体 abort。
 */
final class UpdateAtExecutor implements AtStatementExecutor<UpdateRecognizer.Plan> {
    /**
     * 收集 UPDATE 主键参数并执行。
     *
     * <p>WHERE 里的主键参数下标 = SET 子句占位符个数 + 自身位置（SET 在前、WHERE 在后）， 因此用 {@code assignmentParameterCount
     * + i + 1} 定位。收集后排序保证加锁顺序一致。
     *
     * @param context undo 生成上下文
     * @param connection 业务连接
     * @param xid 全局事务 id
     * @param plan 已识别的 UPDATE 计划
     * @param parameters 业务语句的 JDBC 参数
     * @return 单行时返回单个 Capture，多行时返回合并后的 Capture
     * @throws SQLException 数据库访问异常
     */
    @Override
    public SqlUndoLogGenerator.Capture execute(
            SqlUndoLogGenerator context,
            Connection connection,
            String xid,
            UpdateRecognizer.Plan plan,
            Map<Integer, Object> parameters)
            throws SQLException {
        SqlUndoLogGenerator.assertPrimaryKey(connection, plan.table, plan.primaryKeyColumn);
        List<Object> keys = new ArrayList<Object>(plan.predicateParameterCount);
        for (int i = 0; i < plan.predicateParameterCount; i++)
            keys.add(
                    SqlUndoLogGenerator.require(parameters, plan.assignmentParameterCount + i + 1));
        // 按字符串序排序主键，保证多实例加锁顺序一致，避免相互等待造成死锁。
        keys.sort(Comparator.comparing(String::valueOf));
        List<SqlUndoLogGenerator.Capture> captures =
                new ArrayList<SqlUndoLogGenerator.Capture>(keys.size());
        try {
            for (Object key : keys) captures.add(executeOne(context, connection, xid, plan, key));
        } catch (SQLException | RuntimeException failure) {
            context.abort(connection, new SqlUndoLogGenerator.Capture(captures));
            throw failure;
        }
        return captures.size() == 1 ? captures.get(0) : new SqlUndoLogGenerator.Capture(captures);
    }

    /**
     * 执行单条主键更新：抢锁 → 快照 before image → 拼 UPDATE 反向 SQL（把各列改回 before）→ 落库。
     *
     * @param context undo 生成上下文
     * @param connection 业务连接
     * @param xid 全局事务 id
     * @param plan 已识别的 UPDATE 计划
     * @param key 待更新行的主键值
     * @return 单行 Capture
     * @throws SQLException 数据库访问异常
     */
    private SqlUndoLogGenerator.Capture executeOne(
            SqlUndoLogGenerator context,
            Connection connection,
            String xid,
            UpdateRecognizer.Plan plan,
            Object key)
            throws SQLException {
        context.lock(plan.rawTable, key, xid);
        RowImage before =
                context.select(connection, plan.rawTable, plan.columns, plan.primaryKeyColumn, key);
        if (before == null)
            throw new AtException("UPDATE target does not exist: " + plan.rawTable + "." + key);

        StringBuilder undo = new StringBuilder("UPDATE ").append(plan.tableRef).append(" SET ");
        List<String> columns = plan.columns;
        Object[] values = new Object[columns.size() + 1];
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) undo.append(',');
            undo.append(context.dialect().quoteIdentifier(columns.get(i))).append("=?");
            values[i] = SqlUndoLogGenerator.column(before, columns.get(i));
        }
        undo.append(" WHERE ")
                .append(context.dialect().quoteIdentifier(plan.primaryKeyColumn))
                .append("=?");
        values[values.length - 1] = key;
        UndoRecord record =
                context.record(
                        xid,
                        plan.tableRef,
                        plan.primaryKeyColumn,
                        key,
                        undo.toString(),
                        values,
                        before);
        context.append(connection, record);
        return new SqlUndoLogGenerator.Capture(record, plan.rawTable, plan.primaryKeyColumn, key);
    }
}

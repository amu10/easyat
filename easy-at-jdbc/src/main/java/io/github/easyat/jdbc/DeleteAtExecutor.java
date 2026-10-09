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
 * DELETE 的执行器（对应 DeleteRecognizer.Plan）。
 *
 * <p>DELETE 的 undo 是「把整行按 before image 插回」。这里先把 WHERE 里主键 {@code ?} 参数收集成
 * 待删主键集合（按值排序以保证多实例加锁顺序一致、避免死锁），逐行抢全局锁、快照 before image、
 * 拼出 INSERT 反向 SQL 并随业务 DML 同连接落库。一旦某行失败，整体 abort（释放已占锁、删除已写 undo）。
 */
final class DeleteAtExecutor implements AtStatementExecutor<DeleteRecognizer.Plan> {
    /**
     * 收集 DELETE 主键参数并执行。
     *
     * <p>把 WHERE 中的占位符参数（主键 IN 时多个）按字符串序排序后逐行处理，保证多实例加锁顺序一致。
     *
     * @param context     undo 生成上下文
     * @param connection  业务连接
     * @param xid         全局事务 id
     * @param plan        已识别的 DELETE 计划
     * @param parameters  业务语句的 JDBC 参数
     * @return 单行时返回单个 Capture，多行时返回合并后的 Capture
     * @throws SQLException 数据库访问异常
     */
    @Override
    public SqlUndoLogGenerator.Capture execute(
            SqlUndoLogGenerator context,
            Connection connection,
            String xid,
            DeleteRecognizer.Plan plan,
            Map<Integer, Object> parameters)
            throws SQLException {
        SqlUndoLogGenerator.assertPrimaryKey(connection, plan.table, plan.primaryKeyColumn);
        List<Object> keys = new ArrayList<Object>(plan.predicateParameterCount);
        for (int i = 0; i < plan.predicateParameterCount; i++)
            keys.add(SqlUndoLogGenerator.require(parameters, i + 1));
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
     * 执行单条主键删除：抢锁 → 快照 before image → 拼 INSERT 反向 SQL → 落库。
     *
     * @param context     undo 生成上下文
     * @param connection  业务连接
     * @param xid         全局事务 id
     * @param plan        已识别的 DELETE 计划
     * @param key         待删行的主键值
     * @return 单行的 Capture（含 undo 记录与锁信息）
     * @throws SQLException 数据库访问异常
     */
    private SqlUndoLogGenerator.Capture executeOne(
            SqlUndoLogGenerator context,
            Connection connection,
            String xid,
            DeleteRecognizer.Plan plan,
            Object key)
            throws SQLException {
        context.lock(plan.rawTable, key, xid);
        RowImage before = context.selectAll(connection, plan.rawTable, plan.primaryKeyColumn, key);
        if (before == null)
            throw new AtException("DELETE target does not exist: " + plan.rawTable + "." + key);

        List<String> columns = new ArrayList<String>(before.getColumns().keySet());
        StringBuilder undo = new StringBuilder("INSERT INTO ").append(plan.tableRef).append(" (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) undo.append(',');
            undo.append(context.dialect().quoteIdentifier(columns.get(i)));
        }
        undo.append(") VALUES (");
        Object[] values = new Object[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) undo.append(',');
            undo.append('?');
            values[i] = before.getColumns().get(columns.get(i));
        }
        undo.append(')');
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

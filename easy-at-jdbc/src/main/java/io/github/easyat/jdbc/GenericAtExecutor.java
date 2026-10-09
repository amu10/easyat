package io.github.easyat.jdbc;

import io.github.easyat.core.AtException;
import io.github.easyat.core.RowImage;
import io.github.easyat.core.UndoRecord;
import io.github.easyat.core.UnsupportedAtSqlException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 通用快照路径的执行器（对应 {@link GenericSnapshotPlanner}）。
 *
 * <p>流程：快照受影响行 → 逐行抢全局锁 → 逐行生成 undo → 逐行写入（与业务 DML 同连接）。 UPDATE 的 undo 是「把整行（除主键）改回 before
 * image」，DELETE 的 undo 是「按 before image 插回整行」。
 *
 * <p>为什么不按需生成 {@code SET 变化的列=?}：通用路径并不解析 SET 表达式（它可能是子查询、函数、算术），
 * 无法在编译期知道哪些列会被改。整行还原是唯一不依赖"猜"的做法，且对本事务未改动的列是无副作用的回写。
 */
final class GenericAtExecutor {

    private GenericAtExecutor() {}

    /**
     * 通用快照路径的执行入口。
     *
     * <p>先查单列主键、拒绝「改主键」的 SET（否则 undo 无法定位行），再按快照 SELECT 读出所有受影响行 （超出 {@code plan.maxRows}
     * 即拒），逐行抢锁并生成整行级 undo，最后随业务 DML 同连接落库。 中途失败则整体 abort（释放已占锁、删除已写 undo）。
     *
     * @param context undo 生成上下文
     * @param connection 业务连接
     * @param xid 全局事务 id
     * @param plan 通用快照计划
     * @param parameters 业务语句的 JDBC 参数
     * @return 单行时返回单个 Capture，多行时返回合并后的 Capture
     * @throws SQLException 数据库访问异常
     */
    static SqlUndoLogGenerator.Capture execute(
            SqlUndoLogGenerator context,
            Connection connection,
            String xid,
            GenericSnapshotPlanner.Plan plan,
            Map<Integer, Object> parameters)
            throws SQLException {
        String primaryKey = SqlUndoLogGenerator.findPrimaryKey(connection, plan.target);
        if (plan.setColumns != null)
            for (String column : plan.setColumns)
                if (RecognizerSupport.unquote(column).equalsIgnoreCase(primaryKey))
                    throw new UnsupportedAtSqlException(
                            "Unsupported AT SQL; the primary key column cannot be assigned, "
                                    + "otherwise the row can no longer be located for undo: "
                                    + plan.snapshotSql);

        List<RowImage> rows = context.snapshot(connection, plan, parameters);
        if (rows.size() > plan.maxRows)
            throw new UnsupportedAtSqlException(
                    "Unsupported AT SQL; this statement affects more than "
                            + plan.maxRows
                            + " rows ("
                            + (rows.size() > plan.maxRows ? "more than " : "")
                            + plan.maxRows
                            + "), which is the configured AT limit; narrow the predicate or raise "
                            + "easy-at.sql.max-affected-rows: "
                            + plan.snapshotSql);
        List<SqlUndoLogGenerator.Capture> captures =
                new ArrayList<SqlUndoLogGenerator.Capture>(rows.size());
        try {
            for (RowImage row : rows)
                captures.add(executeOne(context, connection, xid, plan, primaryKey, row));
        } catch (SQLException | RuntimeException failure) {
            context.abort(connection, new SqlUndoLogGenerator.Capture(captures));
            throw failure;
        }
        return captures.size() == 1 ? captures.get(0) : new SqlUndoLogGenerator.Capture(captures);
    }

    /**
     * 单行处理：抢锁 → 拼整行级反向 SQL（DELETE 插回 / UPDATE 还原）→ 落库。
     *
     * <p>整行还原是本路径唯一不依赖「猜哪些列被改」的做法：UPDATE 把除主键外的全部列改回 before image， DELETE 则把整行按 before image 插回。
     *
     * @param context undo 生成上下文
     * @param connection 业务连接
     * @param xid 全局事务 id
     * @param plan 通用快照计划
     * @param primaryKey 目标表的单列主键列名
     * @param before 快照得到的 before image
     * @return 单行 Capture
     * @throws SQLException 数据库访问异常
     */
    private static SqlUndoLogGenerator.Capture executeOne(
            SqlUndoLogGenerator context,
            Connection connection,
            String xid,
            GenericSnapshotPlanner.Plan plan,
            String primaryKey,
            RowImage before)
            throws SQLException {
        Object key = value(before, primaryKey);
        if (key == null)
            throw new AtException(
                    "Cannot build AT undo: snapshot row has no primary key value for "
                            + plan.rawTable
                            + "."
                            + primaryKey);
        context.lock(plan.rawTable, key, xid);

        List<String> columns = new ArrayList<String>(before.getColumns().keySet());
        StringBuilder undo = new StringBuilder();
        Object[] values;
        if (plan.delete) {
            undo.append("INSERT INTO ").append(plan.tableRef).append(" (");
            values = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) undo.append(',');
                undo.append(context.dialect().quoteIdentifier(columns.get(i)));
                values[i] = before.getColumns().get(columns.get(i));
            }
            undo.append(") VALUES (");
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) undo.append(',');
                undo.append('?');
            }
            undo.append(')');
        } else {
            List<String> updatable = new ArrayList<String>(columns.size());
            for (String column : columns)
                if (!RecognizerSupport.unquote(column).equalsIgnoreCase(primaryKey))
                    updatable.add(column);
            undo.append("UPDATE ").append(plan.tableRef).append(" SET ");
            values = new Object[updatable.size() + 1];
            for (int i = 0; i < updatable.size(); i++) {
                if (i > 0) undo.append(',');
                undo.append(context.dialect().quoteIdentifier(updatable.get(i))).append("=?");
                values[i] = before.getColumns().get(updatable.get(i));
            }
            undo.append(" WHERE ")
                    .append(context.dialect().quoteIdentifier(primaryKey))
                    .append("=?");
            values[updatable.size()] = key;
        }
        UndoRecord record =
                context.record(
                        xid, plan.tableRef, primaryKey, key, undo.toString(), values, before);
        context.append(connection, record);
        return new SqlUndoLogGenerator.Capture(record, plan.rawTable, primaryKey, key);
    }

    /**
     * 按列名取快照行中的值。
     *
     * <p>必须大小写不敏感：H2/Oracle 的 {@code ResultSetMetaData#getColumnLabel} 返回大写列名， 而 JDBC
     * 元数据取回的主键名可能是小写，直接 {@code get} 会取到 null。
     */
    private static Object value(RowImage image, String column) {
        for (Map.Entry<String, Object> entry : image.getColumns().entrySet())
            if (entry.getKey().equalsIgnoreCase(RecognizerSupport.unquote(column)))
                return entry.getValue();
        return null;
    }
}

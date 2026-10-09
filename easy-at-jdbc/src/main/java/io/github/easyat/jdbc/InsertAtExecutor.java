package io.github.easyat.jdbc;

import io.github.easyat.core.AtException;
import io.github.easyat.core.UndoRecord;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;

/** INSERT 的 undo 是按主键 DELETE，因此每行都必须能在执行前算出主键值。 */
final class InsertAtExecutor implements AtStatementExecutor<InsertRecognizer.Plan> {
    @Override
    public SqlUndoLogGenerator.Capture execute(
            SqlUndoLogGenerator context,
            Connection connection,
            String xid,
            InsertRecognizer.Plan plan,
            Map<Integer, Object> parameters)
            throws SQLException {
        String primaryKey = SqlUndoLogGenerator.findPrimaryKey(connection, plan.table);
        int primaryKeyIndex = SqlUndoLogGenerator.indexOf(plan.columns, primaryKey);
        if (primaryKeyIndex < 0)
            throw new AtException(
                    "INSERT must explicitly include primary key "
                            + primaryKey
                            + " for AT undo; a row inserted without a primary key cannot be "
                            + "located for compensation");

        // 逐行走一遍，同时推进「下一个 ? 的下标」。字面量不消耗占位符，所以不能简单用 行号*列数+列号。
        List<Object> keys = new ArrayList<Object>(plan.rows.size());
        int next = 1;
        for (List<Expression> row : plan.rows) {
            Object key = null;
            for (int i = 0; i < row.size(); i++) {
                Expression value = row.get(i);
                if (value instanceof JdbcParameter) {
                    Object bound = SqlUndoLogGenerator.require(parameters, next);
                    next++;
                    if (i == primaryKeyIndex) key = bound;
                } else {
                    next += RecognizerSupport.parameterCount(value);
                    if (i == primaryKeyIndex)
                        key = RecognizerSupport.literal(value, plan.rawTable + "." + primaryKey);
                }
            }
            if (key == null)
                throw new AtException(
                        "INSERT row has no value for primary key "
                                + plan.rawTable
                                + "."
                                + primaryKey);
            keys.add(key);
        }
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

    private SqlUndoLogGenerator.Capture executeOne(
            SqlUndoLogGenerator context,
            Connection connection,
            String xid,
            InsertRecognizer.Plan plan,
            Object key)
            throws SQLException {
        String primaryKeyColumn = SqlUndoLogGenerator.findPrimaryKey(connection, plan.table);
        context.lock(plan.rawTable, key, xid);
        UndoRecord record =
                context.record(
                        xid,
                        plan.tableRef,
                        primaryKeyColumn,
                        key,
                        "DELETE FROM "
                                + plan.tableRef
                                + " WHERE "
                                + context.dialect().quoteIdentifier(primaryKeyColumn)
                                + "=?",
                        new Object[] {key},
                        null);
        context.append(connection, record);
        return new SqlUndoLogGenerator.Capture(record, plan.rawTable, primaryKeyColumn, key);
    }
}

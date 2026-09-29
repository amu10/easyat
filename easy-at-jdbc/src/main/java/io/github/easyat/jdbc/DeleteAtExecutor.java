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

final class DeleteAtExecutor implements AtStatementExecutor<DeleteRecognizer.Plan> {
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

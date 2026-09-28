package io.github.easyat.jdbc;

import io.github.easyat.core.AtException;
import io.github.easyat.core.UndoRecord;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

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
                    "INSERT must explicitly include primary key " + primaryKey + " for AT undo");
        Object key = SqlUndoLogGenerator.require(parameters, primaryKeyIndex + 1);
        context.lock(plan.rawTable, key, xid);
        String primaryKeyColumn = plan.columns.get(primaryKeyIndex);
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

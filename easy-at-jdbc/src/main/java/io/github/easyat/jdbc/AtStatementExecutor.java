package io.github.easyat.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/** Captures row images and persists Undo data for one recognized DML plan. */
interface AtStatementExecutor<P> {
    SqlUndoLogGenerator.Capture execute(
            SqlUndoLogGenerator context,
            Connection connection,
            String xid,
            P plan,
            Map<Integer, Object> parameters)
            throws SQLException;
}

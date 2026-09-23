package io.github.easyat.core;

import java.sql.Connection;

/**
 * Optional repository capability for writing undo information through the connection that executes
 * the business DML. Implementations must not commit or close that connection.
 */
public interface ConnectionBoundAtRepository extends AtRepository {
    void append(Connection connection, UndoRecord record);

    void updateUndo(Connection connection, String xid, String undoId, RowImage afterImage);

    void removeUndo(Connection connection, String xid, String undoId);
}

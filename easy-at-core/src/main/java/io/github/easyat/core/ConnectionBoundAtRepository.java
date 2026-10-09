package io.github.easyat.core;

import java.sql.Connection;

/**
 * Optional repository capability for writing undo information through the connection that executes
 * the business DML. Implementations must not commit or close that connection.
 */
public interface ConnectionBoundAtRepository extends AtRepository {
    /** 通过执行业务 DML 的那条连接写入一条 undo 记录，使其随业务本地事务一起提交。 */
    void append(Connection connection, UndoRecord record);

    /** 通过业务连接把某条 undo 记录的 after-image 更新为新值（用于「读后写」场景重拍快照）。 */
    void updateUndo(Connection connection, String xid, String undoId, RowImage afterImage);

    /** 通过业务连接删除一条 undo 记录（事务已提交、undo 不再需要时清理）。 */
    void removeUndo(Connection connection, String xid, String undoId);
}

package io.github.easyat.core;

import java.sql.Connection;

/**
 * 能通过执行业务 DML 的那条 {@link Connection} 写入 undo 的仓库能力选项。
 *
 * <p>实现必须<b>不要</b> commit 或 close 传入的连接——这条连接归业务事务所有， undo 要跟着业务 DML 一起提交或回滚。
 */
public interface ConnectionBoundUndoRepository extends UndoRepository {

    /** 通过执行业务 DML 的那条连接写入一条 undo 记录，使其随业务本地事务一起提交。 */
    void append(Connection connection, UndoRecord record);

    /** 通过业务连接把某条 undo 记录的 after-image 更新为新值（用于「读后写」场景重拍快照）。 */
    void updateUndo(Connection connection, String xid, String undoId, RowImage afterImage);

    /** 通过业务连接删除一条 undo 记录（事务已提交、undo 不再需要时清理）。 */
    void removeUndo(Connection connection, String xid, String undoId);
}

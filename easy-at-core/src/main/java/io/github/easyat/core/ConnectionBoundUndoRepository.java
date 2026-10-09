package io.github.easyat.core;

import java.sql.Connection;

/**
 * 能通过执行业务 DML 的那条 {@link Connection} 写入 undo 的仓库能力选项。
 *
 * <p>实现必须<b>不要</b> commit 或 close 传入的连接——这条连接归业务事务所有， undo 要跟着业务 DML 一起提交或回滚。
 */
public interface ConnectionBoundUndoRepository extends UndoRepository {

    void append(Connection connection, UndoRecord record);

    void updateUndo(Connection connection, String xid, String undoId, RowImage afterImage);

    void removeUndo(Connection connection, String xid, String undoId);
}

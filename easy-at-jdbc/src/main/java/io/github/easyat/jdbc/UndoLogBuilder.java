package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import java.util.UUID;

/**
 * undo 记录构造器：在 AT 上下文里便捷地创建一条 UndoRecord。
 *
 * <p>自动从当前线程的 AT 上下文取 xid，并生成随机 undo id；无活动事务时直接抛错。
 * 主要用于非自动（手动）场景或测试里手动拼 undo。
 */
public final class UndoLogBuilder {
    private UndoLogBuilder() {}

    /**
     * 构造一条绑定当前 xid 的 undo 记录。
     *
     * @param resource    资源 id（通常是数据源标识）
     * @param table       目标表名
     * @param pk          主键列名
     * @param value       主键值
     * @param rollbackSql 反向 SQL（delete/update/insert）
     * @param parameters  反向 SQL 的 JDBC 参数
     * @return 已绑定 xid 与随机 id 的 UndoRecord
     */
    public static UndoRecord of(
            String resource,
            String table,
            String pk,
            Object value,
            String rollbackSql,
            Object... parameters) {
        // 必须在 AT 事务上下文里调用，否则无法拿到 xid、undo 也无从挂靠。
        String xid = AtContext.xid();
        if (xid == null) throw new AtException("No active AT transaction");
        return new UndoRecord(
                UUID.randomUUID().toString(),
                xid,
                resource,
                table,
                pk,
                value,
                rollbackSql,
                parameters);
    }
}

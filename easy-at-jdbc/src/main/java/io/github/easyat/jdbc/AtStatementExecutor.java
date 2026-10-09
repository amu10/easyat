package io.github.easyat.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;

/**
 * 语句执行器：对某个已识别的执行计划，完成「快照/抢锁/生成 undo/落库」全流程，并返回 Capture。
 * 每个识别出的 Plan 类型对应一个执行器实现（INSERT/UPDATE/DELETE/通用快照）。
 */
// Captures row images and persists Undo data for one recognized DML plan.
interface AtStatementExecutor<P> {
    /**
     * 执行该计划：在给定业务连接上生成并持久化 undo，返回本次捕获（含行锁与 undo 记录）。
     *
     * @param context      undo 生成器上下文，提供抢锁/快照/落库等方法
     * @param connection   业务本地连接（undo 必须与之同事务提交）
     * @param xid          全局事务 id
     * @param plan         已识别的执行计划
     * @param parameters   业务语句的 JDBC 参数（{@code ?} 映射）
     * @return 本次执行产生的捕获（单行或多行合并）
     * @throws SQLException 数据库访问异常
     */
    SqlUndoLogGenerator.Capture execute(
            SqlUndoLogGenerator context,
            Connection connection,
            String xid,
            P plan,
            Map<Integer, Object> parameters)
            throws SQLException;
}

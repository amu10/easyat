package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * 独立对账命令行工具：不需要启动应用，直接连影子库/生产库的 easy_at 表出一份对账报告。
 *
 * <p>投产前"影子运行对账 ≥7 天"就是靠它（或等价的 {@code reconciliation.sql}）每天跑一次。
 *
 * <pre>
 * java -cp easy-at-*.jar:mysql-connector-j.jar io.github.easyat.jdbc.ReconciliationCli \
 *      jdbc:mysql://127.0.0.1:3306/easy_at_it root root
 * </pre>
 *
 * 三个参数：JDBC URL、用户名、口令（口令可省略）。退出码：0=无异常，2=发现需要处理的问题， 1=连不上库。方便直接挂到 cron / 监控里做告警。
 */
public final class ReconciliationCli {

    public static void main(String[] args) throws Exception {
        // Windows 控制台默认 GBK，会把报告里的中文输出成乱码；显式按 UTF-8 输出。
        System.setOut(
                new java.io.PrintStream(
                        new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        if (args.length < 1) {
            System.err.println(
                    "usage: ReconciliationCli <jdbc-url> [user] [password] [stuckAfterMillis]");
            System.exit(64);
        }
        String url = args[0];
        String user = args.length > 1 ? args[1] : null;
        String password = args.length > 2 ? args[2] : null;
        long stuckAfter = args.length > 3 ? Long.parseLong(args[3]) : 60000L;

        DataSource ds = new SimpleDataSource(url, user, password);
        try (Connection probe = ds.getConnection()) {
            probe.createStatement().execute("SELECT 1");
        } catch (SQLException e) {
            System.err.println("cannot connect to " + url + " : " + e.getMessage());
            System.exit(1);
            return;
        }
        try {
            JdbcAtRepository repository = new JdbcAtRepository(ds);
            JdbcGlobalLockManager locks = new JdbcGlobalLockManager(ds, 30000L);
            ReconciliationService service =
                    new ReconciliationService(repository, null, locks, "cli", stuckAfter, 1000);
            ReconciliationReport report = service.report();
            System.out.print(report.toString());
            System.out.println(advice(report));
            System.exit(report.problemCount() == 0 ? 0 : 2);
        } finally {
            // nothing to close: SimpleDataSource 不持有连接
        }
    }

    /**
     * 根据对账报告生成「处理建议」文本，按问题类型给出人工介入 / 重驱动 / 释放锁等指引。
     *
     * @param r 对账报告
     * @return 给运维/研发看的处理建议（中文）
     */
    private static String advice(ReconciliationReport r) {
        if (r.problemCount() == 0) return "  => OK: 未发现残留/泄漏/人工介入。";
        StringBuilder sb = new StringBuilder("  => 处理建议：\n");
        if (!r.getManualIntervention().isEmpty())
            sb.append("   1) MANUAL_INTERVENTION：先查这些事务的 undo before/after image，")
                    .append("确认业务数据实际状态后，用管理端点 POST /_easy-at/v1/transactions/{xid}/rollback\n")
                    .append("      或 /retry 重新驱动；无法自动收敛的按人工修复流程改数据后把状态置为 ROLLED_BACK。\n");
        if (!r.getDirtyWrite().isEmpty())
            sb.append("   2) DIRTY_WRITE：回滚时发现行已被改动，必须人工比对 before/after image 决定最终值。\n");
        if (!r.getLeakedLocks().isEmpty())
            sb.append("   3) 锁泄漏：确认对应事务已终态后，执行 DELETE FROM easy_at_lock WHERE xid=? 释放。\n");
        if (!r.getActiveTimedOut().isEmpty() || !r.getRollingBackStuck().isEmpty())
            sb.append("   4) 残留事务：确认恢复调度（easyat.recovery.*）在跑、实例能抢到租约；")
                    .append("必要时用管理端点触发 retry。\n");
        return sb.toString();
    }

    /** 极简 DataSource：只为 CLI 准备，不引入连接池依赖，每次 getConnection 走 DriverManager。 */
    private static final class SimpleDataSource implements DataSource {
        private final String url, user, password;
        private int loginTimeout;

        SimpleDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        /** 用构造时给定的 url/用户/口令打开一条连接（未给口令则匿名连接）。 */
        public Connection getConnection() throws SQLException {
            return user == null
                    ? java.sql.DriverManager.getConnection(url)
                    : java.sql.DriverManager.getConnection(url, user, password);
        }

        /** 忽略构造参数，用调用方传入的临时用户名/口令打开连接。 */
        public Connection getConnection(String u, String p) throws SQLException {
            return java.sql.DriverManager.getConnection(url, u, p);
        }

        /** 本实现不记日志，返回 null。 */
        public PrintWriter getLogWriter() throws SQLException {
            return null;
        }

        /** 本实现不记日志，空操作。 */
        public void setLogWriter(PrintWriter out) throws SQLException {}

        /** 设置登录超时（仅本地字段，DriverManager 实际超时走全局设置）。 */
        public void setLoginTimeout(int seconds) throws SQLException {
            this.loginTimeout = seconds;
        }

        /** @return 登录超时秒数。 */
        public int getLoginTimeout() throws SQLException {
            return loginTimeout;
        }

        /** 不支持父日志器，直接抛 SQLFeatureNotSupportedException。 */
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        /** 若本实例实现了目标接口则返回自身，否则抛 SQLException（DataSource 包装契约）。 */
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            throw new SQLException("Not a wrapper for " + iface);
        }

        /** 判断本实例是否是给定接口的包装目标。 */
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}

package io.github.easyat.jdbc;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * 测试专用故障注入数据源：作为 {@link AtDataSource} 的 delegate，在 JDBC 调用链的指定相位注入异常。
 *
 * <p>存在的意义：easyAt 的正确性命题（"崩溃后数据最终一致"）无法靠读代码证明，只能在确定的时点把进程从 链路中间掐断，再检查恢复结果。它<b>不修改任何生产代码</b>——只利用
 * {@link AtDataSource} 本身已经是 {@link DataSource} 代理这一事实，在其下层再插一层可注入故障的代理。
 *
 * <p>四个注入相位：
 *
 * <ol>
 *   <li>{@link #PHASE_BEFORE_EXEC}：SQL 执行<b>前</b>。模拟"语句还没进库就崩了"，涵盖 undo log 尚未写入、业务 DML 尚未落库的中间态。
 *   <li>{@link #PHASE_AFTER_EXEC}：SQL 执行<b>后</b>（结果未提交）。模拟"写了但进程立刻死"，此时变更仍 在连接的本地事务里，连接关闭时应由数据库回滚。
 *   <li>{@link #PHASE_BEFORE_COMMIT}：本地提交前。模拟事务协调器已决定提交、但 COMMIT 未到达数据库。
 *   <li>{@link #PHASE_AFTER_COMMIT}：本地提交<b>成功之后</b>才抛。这是最凶险的一种——业务数据与 undo 都已落库且不可撤销，只能靠全局回滚补偿（对应
 *       Seata 语境下的"二阶段失败必须重试到成功"）。
 * </ol>
 *
 * <p>注入的异常刻意选用 SQLState {@code 08006}（connection failure）而非自定义 RuntimeException，因为 生产上的崩溃就是
 * SQLException：只有走相同的异常传播路径，才能真正检验各处的 {@code try/ finally} 与 {@link SqlUndoLogGenerator#abort}
 * 补偿路径是否写对。
 */
public final class ChaosDataSource implements DataSource {

    /** SQL 执行前。 */
    public static final String PHASE_BEFORE_EXEC = "before-exec";

    /** SQL 执行后、提交前。 */
    public static final String PHASE_AFTER_EXEC = "after-exec";

    /** Connection.commit 调用时。 */
    public static final String PHASE_BEFORE_COMMIT = "before-commit";

    /** Connection.commit 成功返回之后。 */
    public static final String PHASE_AFTER_COMMIT = "after-commit";

    /** SQLState 08006 = connection failure，与真实断连一致。 */
    private static final String SQLSTATE_CRASH = "08006";

    private final DataSource delegate;
    private final List<Rule> rules = new ArrayList<Rule>();
    private final AtomicInteger fired = new AtomicInteger();

    public ChaosDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    /**
     * 注册一条故障规则。
     *
     * @param phase 注入相位，取 {@link #PHASE_BEFORE_EXEC} 等常量
     * @param sqlFragment SQL 子串，用于定位目标语句（如 {@code easy_at_undo_log}、{@code UPDATE account}）
     * @param occurrences 第几次匹配才触发（1 = 首次匹配即触发）
     */
    public ChaosDataSource fail(String phase, String sqlFragment, int occurrences) {
        rules.add(new Rule(phase, sqlFragment, occurrences));
        return this;
    }

    /** 在第 {@code occurrences} 次匹配到该 SQL 的执行前注入崩溃。 */
    public ChaosDataSource failBefore(String sqlFragment, int occurrences) {
        return fail(PHASE_BEFORE_EXEC, sqlFragment, occurrences);
    }

    /** 在第 {@code occurrences} 次匹配到该 SQL 的执行后注入崩溃。 */
    public ChaosDataSource failAfter(String sqlFragment, int occurrences) {
        return fail(PHASE_AFTER_EXEC, sqlFragment, occurrences);
    }

    /** 在 {@code Connection.commit()} 上注入崩溃（提交未发生）。 */
    public ChaosDataSource failOnCommit() {
        return fail(PHASE_BEFORE_COMMIT, "", 1);
    }

    /** 在 {@code Connection.commit()} 成功之后注入崩溃（本地事务已落库不可逆）。 */
    public ChaosDataSource failAfterCommit() {
        return fail(PHASE_AFTER_COMMIT, "", 1);
    }

    /** 累计注入次数，供测试断言"故障确实打到了预期的那一步"。 */
    public int firedCount() {
        return fired.get();
    }

    /** 构造一个模拟崩溃的 SQLException。 */
    public static SQLException crash(String what) {
        return new SQLException("Simulated crash: " + what, SQLSTATE_CRASH, 1317);
    }

    private void maybeFail(String phase, String sql) throws SQLException {
        if (sql == null) sql = "";
        for (Rule rule : rules) {
            if (!rule.phase.equals(phase)) continue;
            if (rule.sqlFragment != null
                    && !rule.sqlFragment.isEmpty()
                    && !sql.contains(rule.sqlFragment)) continue;
            if (rule.seen.incrementAndGet() != rule.occurrences) continue;
            fired.incrementAndGet();
            throw crash(phase + " on [" + abbreviate(sql) + "]");
        }
    }

    private static String abbreviate(String sql) {
        String flat = sql.replaceAll("\\s+", " ").trim();
        return flat.length() <= 60 ? flat : flat.substring(0, 60) + "...";
    }

    public Connection getConnection() throws SQLException {
        return proxy(delegate.getConnection());
    }

    public Connection getConnection(String user, String password) throws SQLException {
        return proxy(delegate.getConnection(user, password));
    }

    private Connection proxy(Connection target) {
        return (Connection)
                Proxy.newProxyInstance(
                        ChaosDataSource.class.getClassLoader(),
                        new Class[] {Connection.class},
                        new ConnectionHandler(target));
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final Connection target;

        ConnectionHandler(Connection target) {
            this.target = target;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("commit")) {
                maybeFail(PHASE_BEFORE_COMMIT, null);
                Object result = call(target, method, args);
                maybeFail(PHASE_AFTER_COMMIT, null);
                return result;
            }
            if (name.equals("prepareStatement")
                    && args != null
                    && args.length > 0
                    && args[0] instanceof String) {
                // 语义上 "before-exec" 只代表 "该语句执行前"，因此不在 prepareStatement 阶段触发，
                // 否则同一条 SQL 会被计数两次，让 occurrences 失去精确性。
                String sql = (String) args[0];
                PreparedStatement statement = (PreparedStatement) call(target, method, args);
                return statement(statement, sql);
            }
            if (name.equals("getDelegate") && (args == null || args.length == 0)) return target;
            return call(target, method, args);
        }

        Connection raw() {
            return target;
        }
    }

    private PreparedStatement statement(PreparedStatement target, String sql) {
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        ChaosDataSource.class.getClassLoader(),
                        new Class[] {PreparedStatement.class},
                        new StatementHandler(target, sql));
    }

    private final class StatementHandler implements InvocationHandler {
        private final PreparedStatement target;
        private final String sql;

        StatementHandler(PreparedStatement target, String sql) {
            this.target = target;
            this.sql = sql;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("getConnection")) return proxy(rawConnection());
            boolean execute =
                    name.equals("executeUpdate")
                            || name.equals("execute")
                            || name.equals("executeLargeUpdate")
                            || name.equals("executeQuery");
            if (execute) {
                maybeFail(PHASE_BEFORE_EXEC, sql);
                Object result = call(target, method, args);
                maybeFail(PHASE_AFTER_EXEC, sql);
                return result;
            }
            return call(target, method, args);
        }

        private Connection rawConnection() throws SQLException {
            return target.getConnection();
        }
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static final class Rule {
        final String phase;
        final String sqlFragment;
        final int occurrences;
        final AtomicInteger seen = new AtomicInteger();

        Rule(String phase, String sqlFragment, int occurrences) {
            this.phase = phase;
            this.sqlFragment = sqlFragment;
            this.occurrences = occurrences;
        }
    }

    public PrintWriter getLogWriter() throws SQLException {
        return delegate.getLogWriter();
    }

    public void setLogWriter(PrintWriter p) throws SQLException {
        delegate.setLogWriter(p);
    }

    public void setLoginTimeout(int s) throws SQLException {
        delegate.setLoginTimeout(s);
    }

    public int getLoginTimeout() throws SQLException {
        return delegate.getLoginTimeout();
    }

    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return delegate.getParentLogger();
    }

    public <T> T unwrap(Class<T> t) throws SQLException {
        return t.isInstance(this) ? t.cast(this) : delegate.unwrap(t);
    }

    public boolean isWrapperFor(Class<?> t) throws SQLException {
        return t.isInstance(this) || delegate.isWrapperFor(t);
    }
}

package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * JDBC DataSource 代理，是 AT 事务「拦截点」。
 *
 * <p>它用 JDK 动态代理包装了 {@link Connection} 与 {@link PreparedStatement}，在受支持的主键 DML 真正执行<b>之前</b>，先完成三件
 * AT 前置动作，再由 {@link SqlUndoLogGenerator} 生成并 持久化 undo log（见 {@link AtContext#active()} 与 {@link
 * AtContext#undoing()} 两个开关）：
 *
 * <ol>
 *   <li>校验是否存在 Spring 本地事务（当 {@code requireLocalTransaction=true} 时）；
 *   <li>{@code capture}：解析 SQL → 查 before image → 抢全局行锁 → 写 undo log；
 *   <li>执行原始 DML 后 {@code after}：回写 after image（成功）或 {@code abort}（失败丢弃 undo）。
 * </ol>
 *
 * <p>注意：代理只拦截带占位符的 {@code prepareStatement(String)} 路径； 框架自身的 undo SQL 通过 {@link
 * AtContext#undoing()} 标记被显式放行，不重复代理。
 */
public final class AtDataSource implements DataSource {
    /** Default safety cap for one explicit primary-key IN statement. */
    public static final int DEFAULT_MAX_AFFECTED_ROWS = 100;

    private final String resourceId;
    private final DataSource delegate;
    private final SqlUndoLogGenerator generator;
    private final LocalTransactionBridge bridge;
    private final boolean requireLocalTransaction;

    public AtDataSource(
            String resourceId,
            DataSource delegate,
            AtTransactionManager manager,
            GlobalLockManager locks) {
        this(
                resourceId,
                delegate,
                manager,
                locks,
                LocalTransactionBridge.NOOP,
                false,
                BranchRegistrar.NOOP);
    }

    public AtDataSource(
            String resourceId,
            DataSource delegate,
            AtTransactionManager manager,
            GlobalLockManager locks,
            LocalTransactionBridge bridge,
            boolean requireLocalTransaction) {
        this(
                resourceId,
                delegate,
                manager,
                locks,
                bridge,
                requireLocalTransaction,
                BranchRegistrar.NOOP);
    }

    public AtDataSource(
            String resourceId,
            DataSource delegate,
            AtTransactionManager manager,
            GlobalLockManager locks,
            LocalTransactionBridge bridge,
            boolean requireLocalTransaction,
            BranchRegistrar registrar) {
        this(
                resourceId,
                delegate,
                () -> manager,
                () -> locks,
                bridge,
                requireLocalTransaction,
                registrar,
                DEFAULT_MAX_AFFECTED_ROWS);
    }

    public AtDataSource(
            String resourceId,
            DataSource delegate,
            AtTransactionManager manager,
            GlobalLockManager locks,
            LocalTransactionBridge bridge,
            boolean requireLocalTransaction,
            BranchRegistrar registrar,
            int maxAffectedRows) {
        this(
                resourceId,
                delegate,
                () -> manager,
                () -> locks,
                bridge,
                requireLocalTransaction,
                registrar,
                maxAffectedRows);
    }

    public AtDataSource(
            String resourceId,
            DataSource delegate,
            java.util.function.Supplier<AtTransactionManager> manager,
            java.util.function.Supplier<GlobalLockManager> locks,
            LocalTransactionBridge bridge,
            boolean requireLocalTransaction,
            BranchRegistrar registrar) {
        this(
                resourceId,
                delegate,
                manager,
                locks,
                bridge,
                requireLocalTransaction,
                registrar,
                DEFAULT_MAX_AFFECTED_ROWS);
    }

    public AtDataSource(
            String resourceId,
            DataSource delegate,
            java.util.function.Supplier<AtTransactionManager> manager,
            java.util.function.Supplier<GlobalLockManager> locks,
            LocalTransactionBridge bridge,
            boolean requireLocalTransaction,
            BranchRegistrar registrar,
            int maxAffectedRows) {
        this.resourceId = resourceId;
        this.delegate = delegate;
        this.bridge = bridge;
        this.requireLocalTransaction = requireLocalTransaction;
        this.generator =
                new SqlUndoLogGenerator(
                        resourceId,
                        manager,
                        locks,
                        bridge,
                        AtSqlDialects.detect(delegate),
                        registrar,
                        maxAffectedRows);
    }

    public DataSource getDelegate() {
        return delegate;
    }

    public String getResourceId() {
        return resourceId;
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
                        target.getClass().getClassLoader(),
                        new Class[] {Connection.class},
                        new ConnectionHandler(target));
    }

    private final class ConnectionHandler implements InvocationHandler {
        private final Connection target;

        ConnectionHandler(Connection target) {
            this.target = target;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getName().equals("prepareStatement")
                    && args != null
                    && args.length > 0
                    && args[0] instanceof String)
                return statement((PreparedStatement) call(target, method, args), (String) args[0]);
            return call(target, method, args);
        }
    }

    private PreparedStatement statement(PreparedStatement target, String sql) {
        return (PreparedStatement)
                Proxy.newProxyInstance(
                        target.getClass().getClassLoader(),
                        new Class[] {PreparedStatement.class},
                        new StatementHandler(target, sql));
    }

    private final class StatementHandler implements InvocationHandler {
        private final PreparedStatement target;
        private final String sql;
        private final Map<Integer, Object> params = new HashMap<Integer, Object>();
        private final List<Map<Integer, Object>> batches = new ArrayList<Map<Integer, Object>>();

        StatementHandler(PreparedStatement target, String sql) {
            this.target = target;
            this.sql = sql;
        }

        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            // 记录 setXxx 传入的占位符参数，供 SQL 生成器拼装 undo 时定位主键与列值
            if (name.startsWith("set")
                    && args != null
                    && args.length >= 2
                    && args[0] instanceof Integer) {
                params.put((Integer) args[0], args[1]);
                return call(target, method, args);
            }
            if (name.equals("clearParameters")) {
                params.clear();
                return call(target, method, args);
            }
            if (name.equals("addBatch") && (args == null || args.length == 0)) {
                Object result = call(target, method, args);
                batches.add(new HashMap<Integer, Object>(params));
                return result;
            }
            if (name.equals("clearBatch")) {
                batches.clear();
                return call(target, method, args);
            }
            if ((name.equals("executeBatch") || name.equals("executeLargeBatch"))
                    && AtContext.active()
                    && !AtContext.undoing()) {
                requireLocalTransaction();
                return executeBatch(method, args);
            }
            // 仅当「处于 AT 事务中」且「当前不是在执行框架自己的 undo SQL」时才介入
            if ((name.equals("executeUpdate")
                            || name.equals("execute")
                            || name.equals("executeLargeUpdate"))
                    && AtContext.active()
                    && !AtContext.undoing()) {
                requireLocalTransaction();
                Connection connection = target.getConnection();
                // capture：解析 SQL、查 before image、抢全局锁、写 undo log
                SqlUndoLogGenerator.Capture capture = generator.capture(connection, sql, params);
                try {
                    Object result = call(target, method, args);
                    validateDmlResult(name, result, capture);
                    generator.after(connection, capture);
                    return result;
                } catch (Throwable failure) {
                    try {
                        generator.abort(connection, capture);
                    } catch (Throwable cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                    throw failure;
                }
            }
            return call(target, method, args);
        }

        private Object executeBatch(Method method, Object[] args) throws Throwable {
            if (batches.isEmpty()) return call(target, method, args);
            Connection connection = target.getConnection();
            List<SqlUndoLogGenerator.Capture> captures =
                    new ArrayList<SqlUndoLogGenerator.Capture>(batches.size());
            try {
                Set<String> targets = new HashSet<String>();
                for (Map<Integer, Object> parameters : batches) {
                    SqlUndoLogGenerator.Capture capture =
                            generator.capture(connection, sql, parameters);
                    captures.add(capture);
                    if (capture != null)
                        for (SqlUndoLogGenerator.Capture leaf : capture.leaves())
                            if (!targets.add(
                                    leaf.table
                                            + "\u0000"
                                            + leaf.pk
                                            + "\u0000"
                                            + String.valueOf(leaf.key)))
                                throw new UnsupportedAtSqlException(
                                        "A JDBC batch may not modify the same AT row more than once: "
                                                + leaf.table
                                                + "."
                                                + leaf.key);
                }
                Object result = call(target, method, args);
                validateBatchResult(result, captures.size());
                for (SqlUndoLogGenerator.Capture capture : captures)
                    generator.after(connection, capture);
                return result;
            } catch (Throwable failure) {
                for (int i = captures.size() - 1; i >= 0; i--) {
                    try {
                        generator.abort(connection, captures.get(i));
                    } catch (Throwable cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                }
                throw failure;
            } finally {
                batches.clear();
            }
        }

        private void requireLocalTransaction() {
            if (requireLocalTransaction && !bridge.isActive())
                throw new AtException(
                        "easyAt requires a Spring local transaction on resource '"
                                + resourceId
                                + "'; annotate the method with @Transactional");
        }

        private void validateBatchResult(Object result, int expected) {
            int actual;
            if (result instanceof int[]) {
                int[] counts = (int[]) result;
                actual = counts.length;
                for (int count : counts)
                    if (count == Statement.EXECUTE_FAILED)
                        throw new AtException("JDBC batch contains a failed AT statement");
            } else if (result instanceof long[]) {
                long[] counts = (long[]) result;
                actual = counts.length;
                for (long count : counts)
                    if (count == Statement.EXECUTE_FAILED)
                        throw new AtException("JDBC batch contains a failed AT statement");
            } else {
                throw new AtException("Unexpected JDBC batch result: " + result);
            }
            if (actual != expected)
                throw new AtException(
                        "JDBC batch result count " + actual + " does not match " + expected);
        }

        private void validateDmlResult(
                String methodName, Object result, SqlUndoLogGenerator.Capture capture)
                throws SQLException {
            if (capture == null) return;
            long actual;
            if ("executeUpdate".equals(methodName)) actual = ((Integer) result).longValue();
            else if ("executeLargeUpdate".equals(methodName)) actual = ((Long) result).longValue();
            else actual = target.getUpdateCount();
            int expected = capture.leaves().size();
            if (actual != expected)
                throw new AtException(
                        "AT row image count "
                                + expected
                                + " does not match JDBC affected rows "
                                + actual);
        }
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
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

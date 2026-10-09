package io.github.easyat.dbtests;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.opentest4j.TestAbortedException;

/**
 * 解析集成测试要用的真实数据库/Redis 目标。
 *
 * <p>优先级：
 *
 * <ol>
 *   <li><b>外部实例</b>：通过系统属性 {@code -Deasyat.it.mysql.url=...} 或环境变量 {@code
 *       EASYAT_IT_MYSQL_URL=...} 指向已有库。内网已有 MySQL/PostgreSQL 时最省事。
 *   <li><b>Testcontainers</b>：本机有 Docker 时自动拉起一次性容器。
 *   <li>两者都没有 → 抛 {@link TestAbortedException} <b>跳过</b>而不是失败：真实库测试是可选能力，
 *       不该因为它让没有 Docker 的环境构建变红。
 * </ol>
 */
final class RealDatabaseSupport {

    private RealDatabaseSupport() {}

    /** 一个可被测试使用的 JDBC 目标。 */
    interface JdbcTarget extends AutoCloseable {
        DataSource dataSource();

        /** JDBC URL，用于诊断输出。 */
        String url();
    }

    /** 一个可被测试使用的 Redis 目标。 */
    interface RedisTarget extends AutoCloseable {
        String host();

        int port();

        String password();

        /**
         * Redis 逻辑库编号。
         *
         * <p>用例开头会 {@code FLUSHDB}，而 {@code FLUSHDB} 只清当前 db。指向共享/开发用 Redis 时务必指定一个
         * 非 0 的空闲 db（例如 {@code -Deasyat.it.redis.database=5}），否则会把别人（或本机业务）的缓存清光。
         */
        int database();
    }

    static JdbcTarget mysql() {
        String url = property("easyat.it.mysql.url", "EASYAT_IT_MYSQL_URL");
        if (url != null) return external(url, "root", "easyat.it.mysql", "MySQL");
        return dockerMySql();
    }

    static JdbcTarget postgres() {
        String url = property("easyat.it.postgres.url", "EASYAT_IT_POSTGRES_URL");
        if (url != null) return external(url, "postgres", "easyat.it.postgres", "PostgreSQL");
        return dockerPostgres();
    }

    static RedisTarget redis() {
        String host = property("easyat.it.redis.host", "EASYAT_IT_REDIS_HOST");
        if (host != null) {
            String portValue = firstNonBlank(property("easyat.it.redis.port", "EASYAT_IT_REDIS_PORT"), "6379");
            String password = property("easyat.it.redis.password", "EASYAT_IT_REDIS_PASSWORD");
            int port = Integer.parseInt(portValue);
            int database =
                    Integer.parseInt(
                            firstNonBlank(
                                    property("easyat.it.redis.database", "EASYAT_IT_REDIS_DATABASE"),
                                    "0"));
            return new RedisTarget() {
                public String host() {
                    return host;
                }

                public int port() {
                    return port;
                }

                public String password() {
                    return password;
                }

                public int database() {
                    return database;
                }

                public void close() {}
            };
        }
        return dockerRedis();
    }

    // ------------------------------------------------------------ Testcontainers 兜底

    private static JdbcTarget dockerMySql() {
        try {
            return Containers.startMySql();
        } catch (RuntimeException | LinkageError e) {
            throw abort("MySQL", e);
        }
    }

    private static JdbcTarget dockerPostgres() {
        try {
            return Containers.startPostgres();
        } catch (RuntimeException | LinkageError e) {
            throw abort("PostgreSQL", e);
        }
    }

    private static RedisTarget dockerRedis() {
        try {
            return Containers.startRedis();
        } catch (RuntimeException | LinkageError e) {
            throw abort("Redis", e);
        }
    }

    /**
     * Testcontainers 调用隔离在这个内部类中：只有真正走到这里的测试才会加载 Testcontainers 的类
     * （{@code MySQLContainer} 等）。因此在"直接用外部实例"的场景下，即便本机没有 Docker 也不会出问题。
     */
    private static final class Containers {
        private Containers() {}

        static JdbcTarget startMySql() {
            org.testcontainers.containers.MySQLContainer<?> container =
                    new org.testcontainers.containers.MySQLContainer<>(
                                    org.testcontainers.utility.DockerImageName.parse("mysql:8.0"))
                            .withDatabaseName("easyat")
                            .withUsername("easyat")
                            .withPassword("easyat");
            container.start();
            SimpleDataSource ds =
                    new SimpleDataSource(container.getJdbcUrl(), "easyat", "easyat");
            return new PooledTarget(ds, container);
        }

        static JdbcTarget startPostgres() {
            org.testcontainers.containers.PostgreSQLContainer<?> container =
                    new org.testcontainers.containers.PostgreSQLContainer<>(
                                    org.testcontainers.utility.DockerImageName.parse(
                                            "postgres:16-alpine"))
                            .withDatabaseName("easyat")
                            .withUsername("easyat")
                            .withPassword("easyat");
            container.start();
            SimpleDataSource ds =
                    new SimpleDataSource(container.getJdbcUrl(), "easyat", "easyat");
            return new PooledTarget(ds, container);
        }

        static RedisTarget startRedis() {
            org.testcontainers.containers.GenericContainer<?> container =
                    new org.testcontainers.containers.GenericContainer<>(
                                    org.testcontainers.utility.DockerImageName.parse(
                                            "redis:7-alpine"))
                            .withExposedPorts(6379);
            container.start();
            return new RedisTarget() {
                public String host() {
                    return container.getHost();
                }

                public int port() {
                    return container.getMappedPort(6379);
                }

                public String password() {
                    return null;
                }

                public int database() {
                    return 0;
                }

                public void close() {
                    container.stop();
                }
            };
        }
    }

    // ------------------------------------------------------------ 通用实现

    private static JdbcTarget external(String url, String defaultUser, String prefix, String kind) {
        String user = firstNonBlank(property(prefix + ".user", null), defaultUser);
        String password = firstNonBlank(property(prefix + ".password", null), "");
        SimpleDataSource ds = new SimpleDataSource(url, user, password);
        // 连通性快速探测：连不上就跳过，避免把环境问题误报成产品缺陷
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("SELECT 1");
        } catch (SQLException e) {
            throw abort(kind + " at " + url, new IllegalStateException("cannot connect: " + e.getMessage()));
        }
        return new PooledTarget(ds, null);
    }

    /** 极简 DataSource：基于 DriverManager，避免为测试额外引入连接池依赖。 */
    private static final class SimpleDataSource implements DataSource {
        private final String url;
        private final String user;
        private final String password;
        private int loginTimeout;

        SimpleDataSource(String url, String user, String password) {
            this.url = url;
            this.user = user;
            this.password = password;
        }

        public Connection getConnection() throws SQLException {
            return user == null
                    ? DriverManager.getConnection(url)
                    : DriverManager.getConnection(url, user, password);
        }

        public Connection getConnection(String u, String p) throws SQLException {
            return DriverManager.getConnection(url, u, p);
        }

        public PrintWriter getLogWriter() throws SQLException {
            return null;
        }

        public void setLogWriter(PrintWriter out) throws SQLException {}

        public void setLoginTimeout(int seconds) throws SQLException {
            this.loginTimeout = seconds;
        }

        public int getLoginTimeout() throws SQLException {
            return loginTimeout;
        }

        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) return iface.cast(this);
            throw new SQLException("Not a wrapper for " + iface);
        }

        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }

    private static final class PooledTarget implements JdbcTarget {
        private final DataSource dataSource;
        private final AutoCloseable owned;

        PooledTarget(DataSource dataSource, AutoCloseable owned) {
            this.dataSource = dataSource;
            this.owned = owned;
        }

        public DataSource dataSource() {
            return dataSource;
        }

        public String url() {
            try (Connection c = dataSource.getConnection()) {
                return c.getMetaData().getURL();
            } catch (SQLException e) {
                return "<unknown>";
            }
        }

        public void close() {
            if (owned != null)
                try {
                    owned.close();
                } catch (Exception ignored) {
                    /* container shutdown must not mask test results */
                }
        }
    }

    private static TestAbortedException abort(String what, Throwable cause) {
        return new TestAbortedException(
                "Skipping real-database test: no usable "
                        + what
                        + " available. Provide -Deasyat.it.* properties to point at an existing"
                        + " instance, or start Docker so Testcontainers can launch one. Cause: "
                        + cause.getMessage(),
                cause);
    }

    private static String property(String systemKey, String envKey) {
        String v = System.getProperty(systemKey);
        if (v != null && !v.trim().isEmpty()) return v.trim();
        if (envKey != null) {
            String e = System.getenv(envKey);
            if (e != null && !e.trim().isEmpty()) return e.trim();
        }
        return null;
    }

    private static String firstNonBlank(String a, String fallback) {
        return a == null || a.trim().isEmpty() ? fallback : a.trim();
    }
}

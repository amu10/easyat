package io.github.easyat.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * 方言解析器：从 DataSource 的 JDBC 元数据里读出数据库产品名，据此挑选具体方言实现。 连不上库或读不到元数据时回退到 GenericAtSqlDialect，保证 AT
 * 仍可用（只是不做引用）。
 */
// Resolves the active dialect from a DataSource's JDBC metadata.
public final class AtSqlDialects {
    /** 工具类，禁止实例化。 */
    private AtSqlDialects() {}

    /**
     * 通过 DataSource 打开一条连接读取 JDBC 元数据来探测方言。
     *
     * <p>任何异常（连接失败、元数据为空）都不会上抛，而是静默回退到 GenericAtSqlDialect， 让 AT 在未知/受限环境里依然可用。
     *
     * @param dataSource 业务数据源
     * @return 探测到的方言实现
     */
    public static AtSqlDialect detect(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            String product = md == null ? null : md.getDatabaseProductName();
            return detect(product);
        } catch (SQLException e) {
            return new GenericAtSqlDialect();
        }
    }

    /**
     * 按数据库产品名挑选方言。产品名为 null 或无法识别时回退到 Generic。
     *
     * <p>匹配是子串匹配且大小写不敏感：含 "mysql" 走 MySQL 方言，含 "postgre" 走 PostgreSQL 方言。
     *
     * @param productName 来自 {@link DatabaseMetaData#getDatabaseProductName()} 的产品名
     * @return 对应的方言实现
     */
    public static AtSqlDialect detect(String productName) {
        if (productName == null) return new GenericAtSqlDialect();
        String p = productName.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("mysql")) return new MysqlAtSqlDialect();
        if (p.contains("postgre")) return new PostgresAtSqlDialect();
        return new GenericAtSqlDialect();
    }
}

package io.github.easyat.jdbc;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** PostgreSQL 14+ 方言：双引号引用与常见保留字集合。 */
public final class PostgresAtSqlDialect implements AtSqlDialect {
    /** PostgreSQL 下需要引用才安全的常见保留字（不完全，覆盖高频关键字即可）。 */
    private static final Set<String> RESERVED =
            new HashSet<String>(
                    Arrays.asList(
                            "SELECT",
                            "FROM",
                            "WHERE",
                            "UPDATE",
                            "DELETE",
                            "INSERT",
                            "INTO",
                            "SET",
                            "VALUES",
                            "ORDER",
                            "GROUP",
                            "BY",
                            "AND",
                            "OR",
                            "TABLE",
                            "INDEX",
                            "KEY",
                            "PRIMARY",
                            "FOREIGN",
                            "REFERENCES",
                            "NOT",
                            "NULL",
                            "DEFAULT",
                            "UNIQUE",
                            "JOIN",
                            "ON",
                            "AS",
                            "LIMIT",
                            "OFFSET",
                            "DUAL",
                            "READ",
                            "WRITE",
                            "LOCK",
                            "CASE",
                            "WHEN",
                            "THEN",
                            "ELSE",
                            "END",
                            "CAST",
                            "USER",
                            "SHOW"));

    /** @return 方言产品名 {@code PostgreSQL}。 */
    @Override
    public String productName() {
        return "PostgreSQL";
    }

    /**
     * 用双引号引用标识符。已加双引号的原样返回；标识符内部的双引号按 SQL 标准双写转义。
     */
    @Override
    public String quoteIdentifier(String id) {
        if (id == null) return null;
        if (id.length() >= 2 && id.charAt(0) == '"' && id.charAt(id.length() - 1) == '"') return id;
        // SQL 标准：双引号内的双引号写成两个双引号来转义。
        return "\"" + id.replace("\"", "\"\"") + "\"";
    }

    /** 判断标识符是否为 PostgreSQL 保留字（大小写不敏感）。 */
    @Override
    public boolean isReservedWord(String id) {
        return id != null && RESERVED.contains(id.toUpperCase(Locale.ROOT));
    }

    /** 在通用白名单（COALESCE/ABS）基础上额外放行 PostgreSQL 的 NOW / CURRENT_TIMESTAMP。 */
    @Override
    public boolean supportsUpdateFunction(String functionName) {
        return AtSqlDialect.super.supportsUpdateFunction(functionName)
                || "NOW".equalsIgnoreCase(functionName)
                || "CURRENT_TIMESTAMP".equalsIgnoreCase(functionName);
    }
}

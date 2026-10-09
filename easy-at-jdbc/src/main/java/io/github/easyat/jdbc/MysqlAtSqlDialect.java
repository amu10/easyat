package io.github.easyat.jdbc;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** MySQL 8 方言：反引号引用与常见保留字集合。 */
public final class MysqlAtSqlDialect implements AtSqlDialect {
    /** MySQL 下需要引用才安全的常见保留字（不完全，覆盖高频关键字即可）。 */
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
                            "CALL",
                            "CASE",
                            "WHEN",
                            "THEN",
                            "ELSE",
                            "END",
                            "CAST",
                            "USE",
                            "SHOW"));

    /**
     * @return 方言产品名 {@code MySQL}。
     */
    @Override
    public String productName() {
        return "MySQL";
    }

    /** 用反引号引用标识符。已加反引号的原样返回；标识符内部的反引号按 MySQL 规则双写转义。 */
    @Override
    public String quoteIdentifier(String id) {
        if (id == null) return null;
        if (id.length() >= 2 && id.charAt(0) == '`' && id.charAt(id.length() - 1) == '`') return id;
        // MySQL 字符串里的反引号要写成两个反引号来转义，避免提前闭合引用。
        return "`" + id.replace("`", "``") + "`";
    }

    /** 判断标识符是否为 MySQL 保留字（大小写不敏感）。 */
    @Override
    public boolean isReservedWord(String id) {
        return id != null && RESERVED.contains(id.toUpperCase(java.util.Locale.ROOT));
    }

    /** 在通用白名单（COALESCE/ABS）基础上额外放行 MySQL 的 NOW / CURRENT_TIMESTAMP。 */
    @Override
    public boolean supportsUpdateFunction(String functionName) {
        return AtSqlDialect.super.supportsUpdateFunction(functionName)
                || "NOW".equalsIgnoreCase(functionName)
                || "CURRENT_TIMESTAMP".equalsIgnoreCase(functionName);
    }
}

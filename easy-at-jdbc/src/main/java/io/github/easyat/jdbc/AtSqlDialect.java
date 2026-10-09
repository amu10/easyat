package io.github.easyat.jdbc;

/**
 * SQL 方言抽象：把「标识符引用（加引号）、schema/catalog 处理、保留字判断」隔离到具体方言实现里， 让上层（recognizer / planner /
 * executor）拼出来的 SQL 与具体数据库无关、可移植。
 */
// SQL dialect abstraction so identifier quoting, schema/catalog handling and reserved words are
// portable.
public interface AtSqlDialect {
    /** 方言对应的数据库产品名（如 MySQL / PostgreSQL / Generic），仅用于诊断与日志。 */
    String productName();

    /** 引用单个标识符（列名、表名或 schema 名）。已加引号的标识符原样返回，避免重复加引号。 */
    // Quote a single identifier (column, table or schema). Already-quoted identifiers are returned
    // unchanged.
    String quoteIdentifier(String identifier);

    /** 判断给定标识符是否为该方言的保留字（避免拼出的列名/表名撞保留字导致语法错误）。 */
    boolean isReservedWord(String identifier);

    /**
     * 判断某个标量函数能否安全地出现在 AT UPDATE 的赋值表达式里（用于松散校验 SET 右侧）。 默认只放行 COALESCE / ABS，具体方言可额外放行（如 NOW /
     * CURRENT_TIMESTAMP）。
     */
    // Whether a scalar function is safe inside an AT UPDATE assignment.
    default boolean supportsUpdateFunction(String functionName) {
        if (functionName == null) return false;
        String name = functionName.toUpperCase(java.util.Locale.ROOT);
        return "COALESCE".equals(name) || "ABS".equals(name);
    }

    /** 引用一个可能带 schema 限定的表引用（{@code schema.table}）。schema 为空时只引用表名。 */
    // Quote a possibly schema-qualified table reference.
    default String quoteTable(String schema, String table) {
        String t = quoteIdentifier(table);
        if (schema == null || schema.isEmpty()) return t;
        return quoteIdentifier(schema) + "." + t;
    }
}

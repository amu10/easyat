package io.github.easyat.jdbc;

import io.github.easyat.core.AtException;
import io.github.easyat.core.UnsupportedAtSqlException;
import java.util.Collection;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

final class RecognizerSupport {
    private RecognizerSupport() {}

    static String primaryKeyPredicate(Expression expression, String sql) {
        if (!(expression instanceof EqualsTo)) throw unsupported(sql);
        EqualsTo equal = (EqualsTo) expression;
        if (!(equal.getLeftExpression() instanceof Column)
                || !(equal.getRightExpression() instanceof JdbcParameter)) throw unsupported(sql);
        return ((Column) equal.getLeftExpression()).getColumnName();
    }

    static String tableName(Table table) {
        if (table == null || table.getAlias() != null)
            throw new AtException("Aliased or missing table is not supported for AT SQL");
        return table.getFullyQualifiedName();
    }

    static String identifier(String value) {
        return value == null ? null : unquote(value);
    }

    static String unquote(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0), last = value.charAt(value.length() - 1);
        return (first == '`' && last == '`')
                        || (first == '"' && last == '"')
                        || (first == '[' && last == ']')
                ? value.substring(1, value.length() - 1)
                : value;
    }

    static boolean notEmpty(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    static void reject(boolean condition, String sql) {
        if (condition) throw unsupported(sql);
    }

    static UnsupportedAtSqlException unsupported(String sql) {
        return new UnsupportedAtSqlException(
                "Unsupported AT SQL; only single-row INSERT/UPDATE/DELETE by primary key are allowed; UPDATE values may use parameters, scalar literals, whitelisted functions, or same-column arithmetic (+, -, *, /, %): "
                        + sql);
    }
}

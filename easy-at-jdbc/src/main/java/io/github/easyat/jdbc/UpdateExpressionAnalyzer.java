package io.github.easyat.jdbc;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.HexValue;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;
import net.sf.jsqlparser.expression.operators.arithmetic.Addition;
import net.sf.jsqlparser.expression.operators.arithmetic.Division;
import net.sf.jsqlparser.expression.operators.arithmetic.Modulo;
import net.sf.jsqlparser.expression.operators.arithmetic.Multiplication;
import net.sf.jsqlparser.expression.operators.arithmetic.Subtraction;
import net.sf.jsqlparser.schema.Column;

/** Validates a conservative UPDATE assignment AST and counts its JDBC parameters. */
final class UpdateExpressionAnalyzer {
    private UpdateExpressionAnalyzer() {}

    /** Returns the parameter count, or {@code -1} when the expression is unsupported. */
    static int parameterCount(Column target, Expression expression) {
        if (expression instanceof JdbcParameter) return 1;
        if (literal(expression)) return 0;
        if (expression instanceof Column) return sameColumn(target, (Column) expression) ? 0 : -1;
        if (expression instanceof Parenthesis)
            return parameterCount(target, ((Parenthesis) expression).getExpression());
        if (expression instanceof SignedExpression)
            return parameterCount(target, ((SignedExpression) expression).getExpression());
        if (arithmetic(expression))
            return binaryParameterCount(target, (BinaryExpression) expression);
        return -1;
    }

    private static int binaryParameterCount(Column target, BinaryExpression expression) {
        int left = parameterCount(target, expression.getLeftExpression());
        if (left < 0) return -1;
        int right = parameterCount(target, expression.getRightExpression());
        return right < 0 ? -1 : left + right;
    }

    private static boolean arithmetic(Expression expression) {
        return expression instanceof Addition
                || expression instanceof Subtraction
                || expression instanceof Multiplication
                || expression instanceof Division
                || expression instanceof Modulo;
    }

    private static boolean literal(Expression expression) {
        return expression instanceof NullValue
                || expression instanceof LongValue
                || expression instanceof DoubleValue
                || expression instanceof HexValue
                || expression instanceof StringValue
                || expression instanceof DateValue
                || expression instanceof TimeValue
                || expression instanceof TimestampValue;
    }

    private static boolean sameColumn(Column target, Column referenced) {
        return unquote(target.getColumnName())
                .equalsIgnoreCase(unquote(referenced.getColumnName()));
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        return (first == '`' && last == '`')
                        || (first == '"' && last == '"')
                        || (first == '[' && last == ']')
                ? value.substring(1, value.length() - 1)
                : value;
    }
}

package io.github.easyat.jdbc;

import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.HexValue;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.TimeKeyExpression;
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

    /** 用兜底方言统计赋值表达式的参数个数；不支持时返回 {@code -1}。 */
    static int parameterCount(Column target, Expression expression) {
        return parameterCount(target, expression, new GenericAtSqlDialect());
    }

    /**
     * 统计赋值表达式里的 JDBC 占位符个数；不支持的表达式返回 {@code -1}。
     *
     * <p>递归下降识别：占位符=1，字面量/同列引用=0，括号/正负号透传，算术按左右子树求和，
     * 时间关键字与函数按方言放行，其余一律 {@code -1}（交给通用快照路径）。
     *
     * @param target   被赋值的列（用于判断「同列自引用」是否安全）
     * @param expression SET 右侧的表达式
     * @param dialect   当前方言（决定哪些函数/关键字可用）
     * @return 占位符个数，或 {@code -1}（不支持）
     */
    static int parameterCount(Column target, Expression expression, AtSqlDialect dialect) {
        if (expression instanceof JdbcParameter) return 1;
        if (literal(expression)) return 0;
        if (expression instanceof Column) return sameColumn(target, (Column) expression) ? 0 : -1;
        if (expression instanceof Parenthesis)
            return parameterCount(target, ((Parenthesis) expression).getExpression(), dialect);
        if (expression instanceof SignedExpression)
            return parameterCount(target, ((SignedExpression) expression).getExpression(), dialect);
        if (arithmetic(expression))
            return binaryParameterCount(target, (BinaryExpression) expression, dialect);
        if (expression instanceof TimeKeyExpression)
            return dialect.supportsUpdateFunction(((TimeKeyExpression) expression).getStringValue())
                    ? 0
                    : -1;
        if (expression instanceof Function)
            return functionParameterCount(target, (Function) expression, dialect);
        return -1;
    }

    /** 二元算术表达式的参数个数 = 左子树 + 右子树，任一不支持则整体 {@code -1}。 */
    private static int binaryParameterCount(
            Column target, BinaryExpression expression, AtSqlDialect dialect) {
        int left = parameterCount(target, expression.getLeftExpression(), dialect);
        if (left < 0) return -1;
        int right = parameterCount(target, expression.getRightExpression(), dialect);
        return right < 0 ? -1 : left + right;
    }

    /** 函数表达式的参数个数；不支持的函数形态（聚合/去重/带 Order By 等）或方言不放行时返回 {@code -1}。 */
    private static int functionParameterCount(
            Column target, Function function, AtSqlDialect dialect) {
        if (!dialect.supportsUpdateFunction(function.getName())
                || function.isAllColumns()
                || function.isDistinct()
                || function.getNamedParameters() != null
                || function.getAttribute() != null
                || function.getKeep() != null
                || function.getOrderByElements() != null) return -1;
        int count = 0;
        if (function.getParameters() != null) {
            for (Expression argument : function.getParameters()) {
                int argumentCount = parameterCount(target, argument, dialect);
                if (argumentCount < 0) return -1;
                count += argumentCount;
            }
        }
        String name = function.getName();
        if ("NOW".equalsIgnoreCase(name) || "CURRENT_TIMESTAMP".equalsIgnoreCase(name))
            return function.getParameters() == null || function.getParameters().isEmpty() ? 0 : -1;
        return count;
    }

    /** 判断是否二元算术表达式（+ - * / %）。 */
    private static boolean arithmetic(Expression expression) {
        return expression instanceof Addition
                || expression instanceof Subtraction
                || expression instanceof Multiplication
                || expression instanceof Division
                || expression instanceof Modulo;
    }

    /** 判断是否为「标量字面量」（null/数字/十六进制/字符串/日期时间），这类不消耗参数。 */
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

    /** 判断引用列是否与目标列同名（大小写不敏感、去掉引号），用于「同列自引用」安全校验。 */
    private static boolean sameColumn(Column target, Column referenced) {
        return unquote(target.getColumnName())
                .equalsIgnoreCase(unquote(referenced.getColumnName()));
    }

    /** 去掉标识符两端的引号（反引号 / 双引号 / 方括号），未加引号则原样返回。 */
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

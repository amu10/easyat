package io.github.easyat.jdbc;

import io.github.easyat.core.AtException;
import io.github.easyat.core.UnsupportedAtSqlException;
import java.util.Collection;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

/**
 * 识别器共用的工具类：负责「主键谓词解析、表名提取、参数计数、字面量还原、引用去除、拒绝/不支持抛错」。
 *
 * <p>所有 DML 识别器（INSERT/UPDATE/DELETE/通用快照）都依赖这里，避免在各自代码里重复 SQL 解析假设。 关键不变量：任何「无法安全生成 undo」的语句都在此抛
 * {@link UnsupportedAtSqlException}， 让上层决定是拒绝还是退回通用快照路径。
 */
final class RecognizerSupport {
    private RecognizerSupport() {}

    /**
     * 从 WHERE 表达式里解析「主键精确条件」。
     *
     * <p>只接受两种安全形态：{@code 主键 = ?}（参数个数为 1），或 {@code 主键 IN (?,...,?)} （且元素全为 {@code ?}、个数在 maxRows
     * 内）。其它任何形态（范围、NOT IN、子查询、多列）都拒绝， 因为严格路径必须能枚举出「会被改/删的每一行主键」才能逐行生成 undo。
     *
     * @param expression WHERE 子句表达式
     * @param sql 原始 SQL（用于报错信息）
     * @param maxRows 主键 IN 的大小上限
     * @return 含命中主键列名与参数个数的 PredicatePlan
     */
    static PredicatePlan primaryKeyPredicate(Expression expression, String sql, int maxRows) {
        if (expression instanceof EqualsTo) {
            EqualsTo equal = (EqualsTo) expression;
            if (equal.getLeftExpression() instanceof Column
                    && equal.getRightExpression() instanceof JdbcParameter)
                return new PredicatePlan(((Column) equal.getLeftExpression()).getColumnName(), 1);
            throw unsupported(sql);
        }
        if (maxRows > 1 && expression instanceof InExpression) {
            InExpression in = (InExpression) expression;
            if (in.isNot()
                    || !(in.getLeftExpression() instanceof Column)
                    || !(in.getRightExpression() instanceof ExpressionList)) throw unsupported(sql);
            ExpressionList<?> values = (ExpressionList<?>) in.getRightExpression();
            if (values.isEmpty() || values.size() > maxRows) throw unsupported(sql);
            for (Object value : values)
                if (!(value instanceof JdbcParameter)) throw unsupported(sql);
            return new PredicatePlan(
                    ((Column) in.getLeftExpression()).getColumnName(), values.size());
        }
        throw unsupported(sql);
    }

    /** 取表的「schema.表」全限定名；带别名的表拒绝（别名交给通用快照路径处理）。 */
    static String tableName(Table table) {
        if (table == null) throw new AtException("Missing table is not supported for AT SQL");
        // 别名此前直接抛 AtException。改成"不支持"，让 SqlUndoLogGenerator 有机会退回通用快照路径
        // （通用路径是支持别名的），而不是让一条普通 JOIN UPDATE 直接把业务打断。
        if (table.getAlias() != null) throw unsupported(table.getFullyQualifiedName());
        return table.getFullyQualifiedName();
    }

    /**
     * 统计表达式里的 JDBC 占位符 {@code ?} 个数。
     *
     * <p>通用快照路径靠它算参数偏移：UPDATE 的 SET 子句在前、WHERE/ORDER BY/LIMIT 在后， 而快照 SELECT 只保留后半部分，因此必须知道前面被 SET
     * 消耗掉了几个参数。
     */
    static int parameterCount(Expression expression) {
        if (expression == null) return 0;
        final int[] count = new int[1];
        // 必须用 deparser 而不是 ExpressionVisitorAdapter：后者不会下钻到子查询，
        // 于是 WHERE id IN (SELECT ... WHERE x=?) 里的 ? 会被漏掉，参数偏移算错。
        // deparser 走的是完整渲染路径，顺带也正确处理字符串字面量里的 '?'（不该计数）。
        net.sf.jsqlparser.util.deparser.SelectDeParser selectDeParser =
                new net.sf.jsqlparser.util.deparser.SelectDeParser();
        net.sf.jsqlparser.util.deparser.ExpressionDeParser expressionDeParser =
                new net.sf.jsqlparser.util.deparser.ExpressionDeParser(
                        selectDeParser, new StringBuilder()) {
                    @Override
                    public void visit(JdbcParameter parameter) {
                        count[0]++;
                    }
                };
        selectDeParser.setExpressionVisitor(expressionDeParser);
        expression.accept(expressionDeParser);
        return count[0];
    }

    /**
     * 把简单字面量还原成 Java 值（多行 INSERT 里混用字面量时需要）。
     *
     * <p>只认标量字面量：函数、表达式、子查询都算不出来，一律拒绝——AT 的 undo 依赖确定的主键值， 拿不到就明确失败，不做猜测。
     */
    static Object literal(Expression expression, String target) {
        if (expression instanceof net.sf.jsqlparser.expression.LongValue)
            return Long.valueOf(((net.sf.jsqlparser.expression.LongValue) expression).getValue());
        if (expression instanceof net.sf.jsqlparser.expression.DoubleValue)
            return Double.valueOf(
                    ((net.sf.jsqlparser.expression.DoubleValue) expression).getValue());
        if (expression instanceof net.sf.jsqlparser.expression.StringValue)
            return ((net.sf.jsqlparser.expression.StringValue) expression).getValue();
        if (expression instanceof net.sf.jsqlparser.expression.NullValue) return null;
        throw new UnsupportedAtSqlException(
                "Unsupported AT SQL; cannot resolve a literal value for "
                        + target
                        + " (only ?, numbers, strings and NULL are supported): "
                        + expression);
    }

    /** 带原因的拒绝：比通用文案更能说明「为什么这条语句没法生成 undo」。 */
    static void reject(boolean condition, String sql, String reason) {
        if (condition)
            throw new UnsupportedAtSqlException("Unsupported AT SQL; " + reason + ": " + sql);
    }

    /** 规范化标识符：去掉引号后返回（用于从 schema 节点取裸名）。 */
    static String identifier(String value) {
        return value == null ? null : unquote(value);
    }

    /** 去掉标识符两端的引号（反引号 / 双引号 / 方括号），未加引号则原样返回。 */
    static String unquote(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0), last = value.charAt(value.length() - 1);
        return (first == '`' && last == '`')
                        || (first == '"' && last == '"')
                        || (first == '[' && last == ']')
                ? value.substring(1, value.length() - 1)
                : value;
    }

    /** 判断集合非 null 且非空。 */
    static boolean notEmpty(Collection<?> values) {
        return values != null && !values.isEmpty();
    }

    /** 通用拒绝：条件成立时抛出「仅支持主键 DML / 白名单表达式」的标准不支持异常。 */
    static void reject(boolean condition, String sql) {
        if (condition) throw unsupported(sql);
    }

    /** 构造标准 UnsupportedAtSqlException（统一文案：仅支持主键 INSERT/UPDATE/DELETE 与受限表达式）。 */
    static UnsupportedAtSqlException unsupported(String sql) {
        return new UnsupportedAtSqlException(
                "Unsupported AT SQL; only primary-key INSERT/UPDATE/DELETE and bounded primary-key IN updates/deletes are allowed; UPDATE values may use parameters, scalar literals, whitelisted functions, or same-column arithmetic (+, -, *, /, %): "
                        + sql);
    }

    static final class PredicatePlan {
        /** WHERE 条件里「命中主键」的那个列名（严格路径要求主键精确条件）。 */
        final String column;

        /** 该谓词里占位符 {@code ?} 的个数（主键条件为 1，主键 IN 为集合大小）。 */
        final int parameterCount;

        PredicatePlan(String column, int parameterCount) {
            this.column = column;
            this.parameterCount = parameterCount;
        }
    }
}

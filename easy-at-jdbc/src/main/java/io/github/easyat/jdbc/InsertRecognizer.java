package io.github.easyat.jdbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.Values;

/**
 * 识别 INSERT，支持<b>多行 VALUES</b>（{@code INSERT INTO t(a,b) VALUES (?,?),(?,?)}）。
 *
 * <p>不支持 {@code INSERT ... SELECT}：被插入的行只有在执行后才存在，而 AT 的 undo 必须先在业务 DML 之前拿到主键——没有 {@code
 * RETURN_GENERATED_KEYS} 就无从定位，此时宁可明确拒绝，也不能生成猜的 undo。
 */
final class InsertRecognizer implements AtSqlRecognizer<Insert, InsertRecognizer.Plan> {
    /**
     * 解析并校验一条 INSERT 语句，返回带方言信息的执行计划。
     *
     * <p>只接受「显式列 + 逐行 VALUES（含多行）」形式；{@code INSERT ... SELECT}、ON CONFLICT、
     * {@code INSERT ... SET}、WITH 子句一律拒绝（无法在执行前拿到主键）。
     *
     * @param insert  JSqlParser 解析出的 INSERT 语句
     * @param dialect 当前方言（用于拼加引号的表引用）
     * @return 含列名与每行值表达式的计划
     */
    @Override
    public Plan recognize(Insert insert, AtSqlDialect dialect) {
        String sql = insert.toString();
        RecognizerSupport.reject(
                insert.getWithItemsList() != null
                        || insert.getColumns() == null
                        || insert.getConflictAction() != null
                        || RecognizerSupport.notEmpty(insert.getDuplicateUpdateSets())
                        || RecognizerSupport.notEmpty(insert.getSetUpdateSets()),
                sql);
        Table table = insert.getTable();
        String rawTable = RecognizerSupport.tableName(table);
        List<String> columns = new ArrayList<String>();
        for (Column column : insert.getColumns()) columns.add(column.getColumnName());

        Select select = insert.getSelect();
        RecognizerSupport.reject(
                !(select instanceof Values),
                sql,
                "INSERT ... SELECT 的行只有在执行后才存在，AT 无法在执行前取到主键来生成 undo；" + "请改为显式给出主键的逐行 INSERT");
        ExpressionList<?> outer = ((Values) select).getExpressions();
        RecognizerSupport.reject(outer == null || outer.isEmpty(), sql);

        // 单行时 getExpressions() 直接给出列值；多行时每个元素是代表一行的小 ExpressionList。
        List<List<Expression>> rows = new ArrayList<List<Expression>>();
        if (outer.get(0) instanceof ExpressionList) {
            for (Object element : outer) {
                ExpressionList<?> row = (ExpressionList<?>) element;
                RecognizerSupport.reject(row.size() != columns.size(), sql);
                List<Expression> values = new ArrayList<Expression>(row.size());
                for (Object value : row) values.add((Expression) value);
                rows.add(values);
            }
        } else {
            RecognizerSupport.reject(outer.size() != columns.size(), sql);
            List<Expression> values = new ArrayList<Expression>(outer.size());
            for (Object value : outer) values.add((Expression) value);
            rows.add(values);
        }
        return new Plan(
                table,
                rawTable,
                dialect.quoteTable(
                        RecognizerSupport.identifier(table.getSchemaName()), table.getName()),
                columns,
                rows);
    }

    static final class Plan {
        /** 被插入的目标表（AST 节点，用于查主键元数据）。 */
        final Table table;

        /** 未加引号的「schema.表」名，多实例间锁键必须一致。 */
        final String rawTable;

        /** 已按方言加引号的表引用，用于拼 undo SQL。 */
        final String tableRef;

        /** INSERT 显式给出的列名列表（顺序与 VALUES 一致）。 */
        final List<String> columns;

        /** 每一行的列值表达式（外层 List=行，内层 List=该行的列值）；只支持占位符与简单字面量。 */
        final List<List<Expression>> rows;

        Plan(
                Table table,
                String rawTable,
                String tableRef,
                List<String> columns,
                List<List<Expression>> rows) {
            this.table = table;
            this.rawTable = rawTable;
            this.tableRef = tableRef;
            this.columns = Collections.unmodifiableList(new ArrayList<String>(columns));
            List<List<Expression>> copy = new ArrayList<List<Expression>>(rows.size());
            for (List<Expression> row : rows)
                copy.add(Collections.unmodifiableList(new ArrayList<Expression>(row)));
            this.rows = Collections.unmodifiableList(copy);
        }
    }
}

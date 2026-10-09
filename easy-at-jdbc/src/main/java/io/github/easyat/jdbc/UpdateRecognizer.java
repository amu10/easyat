package io.github.easyat.jdbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

/**
 * UPDATE 识别器（严格主键路径）：要求 {@code WHERE 主键 = ?} 或 {@code 主键 IN (?,...,?)}。
 *
 * <p>严格路径靠参数直接反推「会改哪些行」，且 SET 右侧必须是「参数 / 标量字面量 / 白名单函数 / 同列算术」，可由 {@link UpdateExpressionAnalyzer}
 * 静态校验（无需先 SELECT）。 任何会让受影响行集不确定或值算不出的语法（FROM/JOIN/LIMIT/ORDER BY/WITH、子查询赋值等）一律拒绝， 让调用方退回通用快照路径。
 */
final class UpdateRecognizer implements AtSqlRecognizer<Update, UpdateRecognizer.Plan> {
    /** 单条语句影响行数上限（主键 IN 的大小上限），超出即拒绝生成 undo。 */
    private final int maxAffectedRows;

    UpdateRecognizer() {
        this(AtDataSource.DEFAULT_MAX_AFFECTED_ROWS);
    }

    UpdateRecognizer(int maxAffectedRows) {
        this.maxAffectedRows = Math.max(1, maxAffectedRows);
    }

    /**
     * 解析并校验一条 UPDATE 语句，返回严格主键路径的执行计划。
     *
     * <p>拒绝 FROM/JOIN/LIMIT/ORDER BY/WITH；每个 SET 必须是单列单列赋值且右侧可被 {@link UpdateExpressionAnalyzer}
     * 接受；只接受 {@code WHERE 主键 = ?}（或 {@code 主键 IN}）。
     *
     * @param update JSqlParser 解析出的 UPDATE 语句
     * @param dialect 当前方言（用于拼加引号的表引用）
     * @return 严格主键路径的 Plan
     */
    @Override
    public Plan recognize(Update update, AtSqlDialect dialect) {
        RecognizerSupport.reject(
                update.getWithItemsList() != null
                        || update.getFromItem() != null
                        || RecognizerSupport.notEmpty(update.getJoins())
                        || update.getLimit() != null
                        || RecognizerSupport.notEmpty(update.getOrderByElements()),
                update.toString());
        Table table = update.getTable();
        String rawTable = RecognizerSupport.tableName(table);
        List<String> columns = new ArrayList<String>();
        int parameterCount = 0;
        for (UpdateSet set : update.getUpdateSets()) {
            int count =
                    set.getColumns().size() == 1 && set.getValues().size() == 1
                            ? UpdateExpressionAnalyzer.parameterCount(
                                    set.getColumn(0), set.getValue(0), dialect)
                            : -1;
            RecognizerSupport.reject(
                    set.getColumns().size() != 1 || set.getValues().size() != 1 || count < 0,
                    update.toString());
            columns.add(set.getColumn(0).getColumnName());
            parameterCount += count;
        }
        RecognizerSupport.PredicatePlan predicate =
                RecognizerSupport.primaryKeyPredicate(
                        update.getWhere(), update.toString(), maxAffectedRows);
        return new Plan(
                table,
                rawTable,
                dialect.quoteTable(
                        RecognizerSupport.identifier(table.getSchemaName()), table.getName()),
                columns,
                parameterCount,
                predicate.column,
                predicate.parameterCount);
    }

    static final class Plan {
        /** 被更新的目标表（已解析的 AST 节点，用于查主键元数据）。 */
        final Table table;

        /** 未加引号的「schema.表」名，多实例间锁键必须一致。 */
        final String rawTable;

        /** 已按方言加引号的表引用，用于拼 undo SQL。 */
        final String tableRef;

        /** SET 子句里被赋值的列名列表（顺序与 SQL 一致）。 */
        final List<String> columns;

        /** SET 子句中占位符 {@code ?} 的个数（用于拼接 undo 时定位参数下标）。 */
        final int assignmentParameterCount;

        /** WHERE 命中的主键列名。 */
        final String primaryKeyColumn;

        /** WHERE 条件里占位符 {@code ?} 的个数。 */
        final int predicateParameterCount;

        Plan(
                Table table,
                String rawTable,
                String tableRef,
                List<String> columns,
                int assignmentParameterCount,
                String primaryKeyColumn,
                int predicateParameterCount) {
            this.table = table;
            this.rawTable = rawTable;
            this.tableRef = tableRef;
            this.columns = Collections.unmodifiableList(new ArrayList<String>(columns));
            this.assignmentParameterCount = assignmentParameterCount;
            this.primaryKeyColumn = primaryKeyColumn;
            this.predicateParameterCount = predicateParameterCount;
        }
    }
}

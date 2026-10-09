package io.github.easyat.jdbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

final class UpdateRecognizer implements AtSqlRecognizer<Update, UpdateRecognizer.Plan> {
    /** 单条语句影响行数上限（主键 IN 的大小上限），超出即拒绝生成 undo。 */
    private final int maxAffectedRows;

    UpdateRecognizer() {
        this(AtDataSource.DEFAULT_MAX_AFFECTED_ROWS);
    }

    UpdateRecognizer(int maxAffectedRows) {
        this.maxAffectedRows = Math.max(1, maxAffectedRows);
    }

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

package io.github.easyat.jdbc;

import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;

final class DeleteRecognizer implements AtSqlRecognizer<Delete, DeleteRecognizer.Plan> {
    /** 单条语句影响行数上限（主键 IN 的大小上限），超出即拒绝生成 undo。 */
    private final int maxAffectedRows;

    DeleteRecognizer() {
        this(AtDataSource.DEFAULT_MAX_AFFECTED_ROWS);
    }

    DeleteRecognizer(int maxAffectedRows) {
        this.maxAffectedRows = Math.max(1, maxAffectedRows);
    }

    @Override
    public Plan recognize(Delete delete, AtSqlDialect dialect) {
        RecognizerSupport.reject(
                delete.getWithItemsList() != null
                        || RecognizerSupport.notEmpty(delete.getTables())
                        || RecognizerSupport.notEmpty(delete.getUsingList())
                        || RecognizerSupport.notEmpty(delete.getJoins())
                        || delete.getLimit() != null
                        || RecognizerSupport.notEmpty(delete.getOrderByElements()),
                delete.toString());
        Table table = delete.getTable();
        String rawTable = RecognizerSupport.tableName(table);
        RecognizerSupport.PredicatePlan predicate =
                RecognizerSupport.primaryKeyPredicate(
                        delete.getWhere(), delete.toString(), maxAffectedRows);
        return new Plan(
                table,
                rawTable,
                dialect.quoteTable(
                        RecognizerSupport.identifier(table.getSchemaName()), table.getName()),
                predicate.column,
                predicate.parameterCount);
    }

    static final class Plan {
        /** 被删除的目标表（AST 节点，用于查主键元数据）。 */
        final Table table;

        /** 未加引号的「schema.表」名，多实例间锁键必须一致。 */
        final String rawTable;

        /** 已按方言加引号的表引用，用于拼 undo SQL。 */
        final String tableRef;

        /** WHERE 命中的主键列名。 */
        final String primaryKeyColumn;

        /** WHERE 条件里占位符 {@code ?} 的个数。 */
        final int predicateParameterCount;

        Plan(
                Table table,
                String rawTable,
                String tableRef,
                String primaryKeyColumn,
                int predicateParameterCount) {
            this.table = table;
            this.rawTable = rawTable;
            this.tableRef = tableRef;
            this.primaryKeyColumn = primaryKeyColumn;
            this.predicateParameterCount = predicateParameterCount;
        }
    }
}

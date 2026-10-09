package io.github.easyat.jdbc;

import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;

/**
 * DELETE 识别器（严格主键路径）：要求 {@code WHERE 主键 = ?} 或 {@code 主键 IN (?,...,?)}。
 *
 * <p>严格路径靠参数直接反推「会被删哪些行」，因此不需要先 SELECT 快照、也不需要理解 WHERE 的复杂逻辑；
 * 任何会让受影响行集不确定的语法（JOIN、USING、LIMIT、ORDER BY、WITH、多表）一律拒绝， 让调用方退回通用快照路径。
 */
final class DeleteRecognizer implements AtSqlRecognizer<Delete, DeleteRecognizer.Plan> {
    /** 单条语句影响行数上限（主键 IN 的大小上限），超出即拒绝生成 undo。 */
    private final int maxAffectedRows;

    DeleteRecognizer() {
        this(AtDataSource.DEFAULT_MAX_AFFECTED_ROWS);
    }

    DeleteRecognizer(int maxAffectedRows) {
        this.maxAffectedRows = Math.max(1, maxAffectedRows);
    }

    /**
     * 解析并校验一条 DELETE 语句，返回严格主键路径的执行计划。
     *
     * <p>会拒绝所有「影响行集不确定」的语法（WITH / 多表 / USING / JOIN / LIMIT / ORDER BY）。 只接受 {@code WHERE 主键 = ?}（或
     * {@code 主键 IN (...)}，且 IN 大小在 maxAffectedRows 内）。
     *
     * @param delete JSqlParser 解析出的 DELETE 语句
     * @param dialect 当前方言（用于拼加引号的表引用）
     * @return 严格主键路径的 Plan
     */
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

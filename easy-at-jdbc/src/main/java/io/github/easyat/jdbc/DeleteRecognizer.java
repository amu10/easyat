package io.github.easyat.jdbc;

import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;

final class DeleteRecognizer implements AtSqlRecognizer<Delete, DeleteRecognizer.Plan> {
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
        return new Plan(
                table,
                rawTable,
                dialect.quoteTable(
                        RecognizerSupport.identifier(table.getSchemaName()), table.getName()),
                RecognizerSupport.primaryKeyPredicate(delete.getWhere(), delete.toString()));
    }

    static final class Plan {
        final Table table;
        final String rawTable;
        final String tableRef;
        final String primaryKeyColumn;

        Plan(Table table, String rawTable, String tableRef, String primaryKeyColumn) {
            this.table = table;
            this.rawTable = rawTable;
            this.tableRef = tableRef;
            this.primaryKeyColumn = primaryKeyColumn;
        }
    }
}

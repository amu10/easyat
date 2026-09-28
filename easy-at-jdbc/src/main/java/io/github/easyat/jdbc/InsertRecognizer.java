package io.github.easyat.jdbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.insert.Insert;

final class InsertRecognizer implements AtSqlRecognizer<Insert, InsertRecognizer.Plan> {
    @Override
    public Plan recognize(Insert insert, AtSqlDialect dialect) {
        RecognizerSupport.reject(
                insert.getWithItemsList() != null
                        || insert.getColumns() == null
                        || insert.getValues() == null
                        || insert.getConflictAction() != null
                        || RecognizerSupport.notEmpty(insert.getDuplicateUpdateSets())
                        || RecognizerSupport.notEmpty(insert.getSetUpdateSets()),
                insert.toString());
        Table table = insert.getTable();
        String rawTable = RecognizerSupport.tableName(table);
        List<String> columns = new ArrayList<String>();
        for (Column column : insert.getColumns()) columns.add(column.getColumnName());
        List<?> values = insert.getValues().getExpressions();
        RecognizerSupport.reject(columns.size() != values.size(), insert.toString());
        for (Object value : values)
            RecognizerSupport.reject(!(value instanceof JdbcParameter), insert.toString());
        return new Plan(
                table,
                rawTable,
                dialect.quoteTable(
                        RecognizerSupport.identifier(table.getSchemaName()), table.getName()),
                columns);
    }

    static final class Plan {
        final Table table;
        final String rawTable;
        final String tableRef;
        final List<String> columns;

        Plan(Table table, String rawTable, String tableRef, List<String> columns) {
            this.table = table;
            this.rawTable = rawTable;
            this.tableRef = tableRef;
            this.columns = Collections.unmodifiableList(new ArrayList<String>(columns));
        }
    }
}

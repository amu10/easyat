package io.github.easyat.jdbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

final class UpdateRecognizer implements AtSqlRecognizer<Update, UpdateRecognizer.Plan> {
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
        final Table table;
        final String rawTable;
        final String tableRef;
        final List<String> columns;
        final int assignmentParameterCount;
        final String primaryKeyColumn;
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

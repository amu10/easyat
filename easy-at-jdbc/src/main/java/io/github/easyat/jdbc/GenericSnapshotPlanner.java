package io.github.easyat.jdbc;

import io.github.easyat.core.UnsupportedAtSqlException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

/**
 * 为「严格主键路径」覆盖不到的 DML 构造 before-image 快照查询。
 *
 * <p>严格路径要求 {@code WHERE 主键 = ?}，因此只能靠参数反推受影响行。这里换一个思路： <b>把原语句的 FROM / WHERE / ORDER BY / LIMIT
 * 原样拿过来拼一条 SELECT，先读出所有会被影响的行</b>， 于是子查询、EXISTS、JOIN 过滤、任意条件谓词都不再需要被"理解"——数据库自己会算出来。
 * 快照拿到主键后，再按主键逐行生成 undo，并逐行抢全局锁。
 *
 * <p>两条硬性边界（不是保守，是正确性）：
 *
 * <ul>
 *   <li><b>必须单目标表</b>：{@code UPDATE t1,t2 SET t1.a=?,t2.b=?} 会拒。快照只能按一个表的行来建 undo。
 *   <li><b>表必须有单列主键</b>：没有主键就无法定位行，undo 无从下手。
 * </ul>
 */
final class GenericSnapshotPlanner {
    private GenericSnapshotPlanner() {}

    static final class Plan {
        /** 用于查主键元数据（别名不影响）。 */
        final Table target;

        /** 全局锁用的表名（未加引号，多实例间必须一致）。 */
        final String rawTable;

        /** 拼 undo SQL 用的表引用（已按方言加引号）。 */
        final String tableRef;

        /** before-image 快照 SQL：SELECT &lt;qualifier&gt;.* FROM 原 FROM 子句 WHERE 原 WHERE 子句。 */
        final String snapshotSql;

        /** 快照参数在原语句中的起始下标（1-based 减 1，即前面被 SET 消耗掉的参数个数）。 */
        final int parameterOffset;

        /** 快照参数个数。 */
        final int parameterCount;

        /** 允许的最大影响行数，超出即拒（防止一条语句拖出几十万条 undo）。 */
        final int maxRows;

        final boolean delete;

        /** UPDATE 被赋值的列名；DELETE 为 null。执行期用来拒绝"改主键"。 */
        final List<String> setColumns;

        Plan(
                Table target,
                String rawTable,
                String tableRef,
                String snapshotSql,
                int parameterOffset,
                int parameterCount,
                int maxRows,
                boolean delete,
                List<String> setColumns) {
            this.target = target;
            this.rawTable = rawTable;
            this.tableRef = tableRef;
            this.snapshotSql = snapshotSql;
            this.parameterOffset = parameterOffset;
            this.parameterCount = parameterCount;
            this.maxRows = maxRows;
            this.delete = delete;
            this.setColumns = setColumns;
        }
    }

    /**
     * 为一条 UPDATE 构造 before-image 快照计划。
     *
     * <p>把原语句的 FROM / WHERE / ORDER BY / LIMIT 原样搬进一条 SELECT 快照，并收集 SET 消耗的参数个数
     * （用于计算快照参数的下标偏移）。多目标表、别名指向他表的 SET、WITH 子句一律拒绝。
     *
     * @param update  JSqlParser 解析出的 UPDATE 语句
     * @param dialect 当前方言
     * @param maxRows 允许的最大影响行数（超出即拒）
     * @return 通用快照计划（DELETE=false）
     */
    static Plan forUpdate(Update update, AtSqlDialect dialect, int maxRows) {
        String sql = update.toString();
        RecognizerSupport.reject(
                update.getWithItemsList() != null, sql, "WITH 子句无法为 AT 生成 before-image 快照");
        Table table = update.getTable();
        if (table == null) throw RecognizerSupport.unsupported(sql);
        String qualifier = qualifier(table);

        List<String> setColumns = new ArrayList<String>();
        int setParameters = 0;
        for (UpdateSet set : update.getUpdateSets()) {
            if (set.getColumns().size() != set.getValues().size())
                throw RecognizerSupport.unsupported(sql);
            for (int i = 0; i < set.getColumns().size(); i++) {
                Column column = set.getColumn(i);
                String owner = column.getTable() == null ? null : column.getTable().getName();
                // 多目标表 UPDATE（SET 里出现另一个别名）无法按单行建 undo，明确拒绝而不是猜。
                if (owner != null && !owner.equalsIgnoreCase(qualifier))
                    throw unsupportedMultiTarget(sql, owner);
                setColumns.add(column.getColumnName());
                setParameters += RecognizerSupport.parameterCount(set.getValue(i));
            }
        }

        String from =
                fromClause(table, update.getFromItem(), update.getStartJoins(), update.getJoins());
        int trailing = RecognizerSupport.parameterCount(update.getWhere());
        trailing += orderByParameters(update.getOrderByElements());
        trailing += limitParameters(update.getLimit());
        String snapshot =
                snapshot(
                        qualifier,
                        from,
                        update.getWhere(),
                        update.getOrderByElements(),
                        update.getLimit());
        return new Plan(
                table,
                rawTable(table),
                tableRef(dialect, table),
                snapshot,
                setParameters,
                trailing,
                maxRows,
                false,
                setColumns);
    }

    /**
     * 为一条 DELETE 构造 before-image 快照计划。
     *
     * <p>只支持删主表（或没指定别名的单表 DELETE）；{@code DELETE ... USING}、多表、WITH 子句一律拒绝。
     * 快照参数偏移为 0（DELETE 没有 SET 子句）。
     *
     * @param delete  JSqlParser 解析出的 DELETE 语句
     * @param dialect 当前方言
     * @param maxRows 允许的最大影响行数（超出即拒）
     * @return 通用快照计划（DELETE=true）
     */
    static Plan forDelete(Delete delete, AtSqlDialect dialect, int maxRows) {
        String sql = delete.toString();
        RecognizerSupport.reject(
                delete.getWithItemsList() != null, sql, "WITH 子句无法为 AT 生成 before-image 快照");
        Table table = delete.getTable();
        if (table == null) throw RecognizerSupport.unsupported(sql);
        String qualifier = qualifier(table);

        // DELETE a FROM a JOIN b ... —— 只有删的是主表（或没指定别名）才支持。
        if (RecognizerSupport.notEmpty(delete.getTables())) {
            if (delete.getTables().size() > 1) throw unsupportedMultiTarget(sql, null);
            String owner = delete.getTables().get(0).getName();
            if (!owner.equalsIgnoreCase(qualifier)) throw unsupportedMultiTarget(sql, owner);
        }
        if (RecognizerSupport.notEmpty(delete.getUsingList()))
            throw RecognizerSupport.unsupported(sql);

        String from = fromClause(table, null, null, delete.getJoins());
        int trailing = RecognizerSupport.parameterCount(delete.getWhere());
        trailing += orderByParameters(delete.getOrderByElements());
        trailing += limitParameters(delete.getLimit());
        String snapshot =
                snapshot(
                        qualifier,
                        from,
                        delete.getWhere(),
                        delete.getOrderByElements(),
                        delete.getLimit());
        return new Plan(
                table,
                rawTable(table),
                tableRef(dialect, table),
                snapshot,
                0,
                trailing,
                maxRows,
                true,
                null);
    }

    private static String snapshot(
            String qualifier,
            String from,
            net.sf.jsqlparser.expression.Expression where,
            List<OrderByElement> orderBy,
            Limit limit) {
        StringBuilder b = new StringBuilder("SELECT ");
        b.append(qualifier).append(".* FROM ").append(from);
        if (where != null) b.append(" WHERE ").append(where);
        if (RecognizerSupport.notEmpty(orderBy)) {
            b.append(" ORDER BY ");
            for (int i = 0; i < orderBy.size(); i++) {
                if (i > 0) b.append(',');
                b.append(orderBy.get(i));
            }
        }
        if (limit != null) b.append(limit);
        return b.toString();
    }

    /**
     * 还原原语句的 FROM 子句。
     *
     * <p>{@code UPDATE a JOIN b ON ...} 的 JOIN 落在 {@code startJoins}；{@code UPDATE a, b SET ...} 的
     * 第二个表也落在 {@code startJoins}，但它是逗号连接、渲染出来不带 JOIN 关键字——这里要补个逗号， 否则拼出来是 {@code FROM a b} 这种语法错误。
     */
    private static String fromClause(
            Table table, FromItem fromItem, List<Join> startJoins, List<Join> joins) {
        StringBuilder b = new StringBuilder(table.toString());
        if (startJoins != null) for (Join join : startJoins) b.append(separator(join)).append(join);
        if (fromItem != null) b.append(',').append(fromItem);
        if (joins != null) for (Join join : joins) b.append(separator(join)).append(join);
        return b.toString();
    }

    private static String separator(Join join) {
        String text = String.valueOf(join).trim();
        String upper = text.toUpperCase(Locale.ROOT);
        return upper.startsWith("JOIN")
                        || upper.startsWith("INNER")
                        || upper.startsWith("LEFT")
                        || upper.startsWith("RIGHT")
                        || upper.startsWith("CROSS")
                        || upper.startsWith("STRAIGHT_JOIN")
                        || upper.startsWith("OUTER")
                ? " "
                : ", ";
    }

    private static int orderByParameters(List<OrderByElement> orderBy) {
        if (!RecognizerSupport.notEmpty(orderBy)) return 0;
        int count = 0;
        for (OrderByElement element : orderBy)
            count += RecognizerSupport.parameterCount(element.getExpression());
        return count;
    }

    private static int limitParameters(Limit limit) {
        if (limit == null) return 0;
        return RecognizerSupport.parameterCount(limit.getRowCount())
                + RecognizerSupport.parameterCount(limit.getOffset());
    }

    private static String qualifier(Table table) {
        String alias =
                table.getAlias() == null
                        ? null
                        : RecognizerSupport.unquote(table.getAlias().getName());
        if (alias != null && !alias.isEmpty()) return alias;
        return RecognizerSupport.unquote(table.getName());
    }

    private static String rawTable(Table table) {
        String name = RecognizerSupport.unquote(table.getName());
        String schema = RecognizerSupport.unquote(table.getSchemaName());
        return schema == null ? name : schema + "." + name;
    }

    private static String tableRef(AtSqlDialect dialect, Table table) {
        return dialect.quoteTable(
                RecognizerSupport.unquote(table.getSchemaName()),
                RecognizerSupport.unquote(table.getName()));
    }

    private static UnsupportedAtSqlException unsupportedMultiTarget(String sql, String owner) {
        return new UnsupportedAtSqlException(
                "Unsupported AT SQL; only single-target DML can build a before-image snapshot"
                        + (owner == null ? "" : " (assignment target '" + owner + "')")
                        + ": "
                        + sql);
    }
}

package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import java.sql.*;
import java.util.*;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

/**
 * 用 JSqlParser 解析受限的单行 DML，并生成对应的 before/after image 与 undo SQL。
 *
 * <p>这是 AT 模式「正确性」的核心。它刻意采用<b>保守策略</b>：只接受带主键精确条件的 单行 INSERT/UPDATE/DELETE，且值必须是 {@code ?}
 * 占位符；任何多表、批量、子查询、 函数表达式、别名表、无主键表都直接抛 {@link UnsupportedAtSqlException}， 绝不允许生成「猜测性」的 undo
 * log（DESIGN.md §7.5）。
 *
 * <p>三种 DML 的 undo 逻辑（对应 DESIGN.md §7）：
 *
 * <ul>
 *   <li><b>UPDATE</b>：before image 是被更新行的旧值，undo = 反向 UPDATE 把这些列改回旧值；
 *   <li><b>DELETE</b>：before image 是整行，undo = 按列 INSERT 回原行；
 *   <li><b>INSERT</b>：after image 是新插入行，undo = 按主键 DELETE（首版要求 INSERT 显式携带主键）。
 * </ul>
 *
 * <p>undo SQL 通过 {@link AtTransactionManager#append} 走连接绑定路径写入，与业务 DML 同连接。
 */
final class SqlUndoLogGenerator {
    private final String resourceId;
    private final java.util.function.Supplier<AtTransactionManager> manager;
    private final java.util.function.Supplier<GlobalLockManager> locks;
    private final LocalTransactionBridge bridge;
    private final AtSqlDialect dialect;
    private final BranchRegistrar registrar;
    private Runnable afterCommitHook;

    SqlUndoLogGenerator(
            String resourceId,
            AtTransactionManager manager,
            GlobalLockManager locks,
            LocalTransactionBridge bridge) {
        this(resourceId, manager, locks, bridge, new GenericAtSqlDialect(), BranchRegistrar.NOOP);
    }

    SqlUndoLogGenerator(
            String resourceId,
            AtTransactionManager manager,
            GlobalLockManager locks,
            LocalTransactionBridge bridge,
            AtSqlDialect dialect) {
        this(resourceId, manager, locks, bridge, dialect, BranchRegistrar.NOOP);
    }

    SqlUndoLogGenerator(
            String resourceId,
            AtTransactionManager manager,
            GlobalLockManager locks,
            LocalTransactionBridge bridge,
            BranchRegistrar registrar) {
        this(resourceId, manager, locks, bridge, new GenericAtSqlDialect(), registrar);
    }

    SqlUndoLogGenerator(
            String resourceId,
            AtTransactionManager manager,
            GlobalLockManager locks,
            LocalTransactionBridge bridge,
            AtSqlDialect dialect,
            BranchRegistrar registrar) {
        this(resourceId, () -> manager, () -> locks, bridge, dialect, registrar);
    }

    SqlUndoLogGenerator(
            String resourceId,
            java.util.function.Supplier<AtTransactionManager> manager,
            java.util.function.Supplier<GlobalLockManager> locks,
            LocalTransactionBridge bridge,
            AtSqlDialect dialect,
            BranchRegistrar registrar) {
        this.resourceId = resourceId;
        this.manager = manager;
        this.locks = locks;
        this.bridge = bridge;
        this.dialect = dialect == null ? new GenericAtSqlDialect() : dialect;
        this.registrar = registrar == null ? BranchRegistrar.NOOP : registrar;
    }

    void setAfterCommitHook(Runnable hook) {
        this.afterCommitHook = hook;
    }

    Capture capture(Connection c, String sql, Map<Integer, Object> parameters) throws SQLException {
        String xid = AtContext.xid();
        if (xid == null || AtContext.undoing()) return null;
        Statement statement = parse(sql);
        // Framework repository/lock/branch SQL uses the same proxied DataSource. It must be
        // bypassed before branch registration, otherwise registering easy_at_branch recursively
        // attempts to register another branch until the connection pool is exhausted.
        if (internalStatement(statement)) return null;
        registrar.register(xid, resourceId);
        if (statement instanceof Update) return update(c, xid, (Update) statement, parameters);
        if (statement instanceof Delete) return delete(c, xid, (Delete) statement, parameters);
        if (statement instanceof Insert) return insert(c, xid, (Insert) statement, parameters);
        throw unsupported(sql);
    }

    private Capture update(Connection c, String xid, Update u, Map<Integer, Object> p)
            throws SQLException {
        // 拒绝多表 JOIN / LIMIT / ORDER BY 等复杂形态，只保留单表单行
        reject(
                u.getWithItemsList() != null
                        || u.getFromItem() != null
                        || notEmpty(u.getJoins())
                        || u.getLimit() != null
                        || notEmpty(u.getOrderByElements()),
                u.toString());
        Table parsed = u.getTable();
        String rawTable = tableName(parsed);
        if (internal(rawTable)) return null;
        String tableRef = dialect.quoteTable(identifier(parsed.getSchemaName()), parsed.getName());
        List<String> columns = new ArrayList<String>();
        for (UpdateSet set : u.getUpdateSets()) {
            reject(
                    set.getColumns().size() != 1
                            || set.getValues().size() != 1
                            || !(set.getValue(0) instanceof JdbcParameter),
                    u.toString());
            columns.add(set.getColumn(0).getColumnName());
        }
        Where where = where(u.getWhere(), u.toString());
        Object key = require(p, columns.size() + 1);
        assertPrimaryKey(c, parsed, where.column);
        lock(rawTable, key, xid);
        // 查 before image（被更新列的旧值）
        RowImage before = select(c, rawTable, columns, where.column, key);
        if (before == null)
            throw new AtException("UPDATE target does not exist: " + rawTable + "." + key);
        // undo = 反向 UPDATE，把每列改回旧值
        StringBuilder undo = new StringBuilder("UPDATE ").append(tableRef).append(" SET ");
        Object[] values = new Object[columns.size() + 1];
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) undo.append(',');
            undo.append(dialect.quoteIdentifier(columns.get(i))).append("=?");
            values[i] = column(before, columns.get(i));
        }
        undo.append(" WHERE ").append(dialect.quoteIdentifier(where.column)).append("=?");
        values[values.length - 1] = key;
        UndoRecord r = record(xid, tableRef, where.column, key, undo.toString(), values, before);
        manager.get().append(c, r);
        return new Capture(r, rawTable, where.column, key);
    }

    private Capture delete(Connection c, String xid, Delete d, Map<Integer, Object> p)
            throws SQLException {
        reject(
                d.getWithItemsList() != null
                        || notEmpty(d.getTables())
                        || notEmpty(d.getUsingList())
                        || notEmpty(d.getJoins())
                        || d.getLimit() != null
                        || notEmpty(d.getOrderByElements()),
                d.toString());
        Table parsed = d.getTable();
        String rawTable = tableName(parsed);
        if (internal(rawTable)) return null;
        String tableRef = dialect.quoteTable(identifier(parsed.getSchemaName()), parsed.getName());
        Where where = where(d.getWhere(), d.toString());
        Object key = require(p, 1);
        assertPrimaryKey(c, parsed, where.column);
        lock(rawTable, key, xid);
        // before image = 被删行的完整内容，undo = 按列 INSERT 回原行
        RowImage before = selectAll(c, rawTable, where.column, key);
        if (before == null)
            throw new AtException("DELETE target does not exist: " + rawTable + "." + key);
        List<String> cols = new ArrayList<String>(before.getColumns().keySet());
        StringBuilder undo = new StringBuilder("INSERT INTO ").append(tableRef).append(" (");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) undo.append(',');
            undo.append(dialect.quoteIdentifier(cols.get(i)));
        }
        undo.append(") VALUES (");
        Object[] vals = new Object[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) undo.append(',');
            undo.append('?');
            vals[i] = before.getColumns().get(cols.get(i));
        }
        undo.append(')');
        UndoRecord r = record(xid, tableRef, where.column, key, undo.toString(), vals, before);
        manager.get().append(c, r);
        return new Capture(r, rawTable, where.column, key);
    }

    private Capture insert(Connection c, String xid, Insert in, Map<Integer, Object> p)
            throws SQLException {
        reject(
                in.getWithItemsList() != null
                        || in.getColumns() == null
                        || in.getValues() == null
                        || in.getConflictAction() != null
                        || notEmpty(in.getDuplicateUpdateSets())
                        || notEmpty(in.getSetUpdateSets()),
                in.toString());
        Table parsed = in.getTable();
        String rawTable = tableName(parsed);
        if (internal(rawTable)) return null;
        String tableRef = dialect.quoteTable(identifier(parsed.getSchemaName()), parsed.getName());
        List<String> columns = new ArrayList<String>();
        for (Column column : in.getColumns()) columns.add(column.getColumnName());
        List<?> values = in.getValues().getExpressions();
        reject(columns.size() != values.size(), in.toString());
        for (Object value : values) reject(!(value instanceof JdbcParameter), in.toString());
        // INSERT 必须显式携带主键，否则无法生成按主键 DELETE 的 undo
        String pk = findPrimaryKey(c, parsed);
        int pkIndex = indexOf(columns, pk);
        if (pkIndex < 0)
            throw new AtException(
                    "INSERT must explicitly include primary key " + pk + " for AT undo");
        Object key = require(p, pkIndex + 1);
        lock(rawTable, key, xid);
        // undo = 按主键 DELETE（before image 为 null，after image 在 after() 里回填）
        UndoRecord r =
                record(
                        xid,
                        tableRef,
                        columns.get(pkIndex),
                        key,
                        "DELETE FROM "
                                + tableRef
                                + " WHERE "
                                + dialect.quoteIdentifier(columns.get(pkIndex))
                                + "=?",
                        new Object[] {key},
                        null);
        manager.get().append(c, r);
        return new Capture(r, rawTable, columns.get(pkIndex), key);
    }

    /** DML 执行成功后回填 after image；若存在本地事务则在提交后再触发 afterCommit 钩子（分支提交用）。 */
    void after(Connection c, Capture capture) throws SQLException {
        if (capture == null) return;
        RowImage image = selectAll(c, capture.table, capture.pk, capture.key);
        if (image == null && capture.record.getBeforeImage() == null)
            throw new AtException(
                    "INSERT did not create expected row: " + capture.table + "." + capture.key);
        manager.get().updateUndo(c, capture.record.getXid(), capture.record.getId(), image);
        if (bridge != null && bridge.isActive() && afterCommitHook != null)
            bridge.afterCommit(afterCommitHook);
    }

    /** DML 执行失败时丢弃已写入的 undo 记录，避免残留脏 undo。 */
    void abort(Connection c, Capture capture) {
        if (capture != null)
            manager.get().discardUndo(c, capture.record.getXid(), capture.record.getId());
    }

    private UndoRecord record(
            String xid,
            String table,
            String pk,
            Object key,
            String sql,
            Object[] values,
            RowImage before) {
        UndoRecord r =
                new UndoRecord(
                        UUID.randomUUID().toString(),
                        xid,
                        resourceId,
                        stripQuotes(table),
                        pk,
                        key,
                        sql,
                        values);
        r.setBeforeImage(before);
        return r;
    }

    private void lock(String table, Object key, String xid) {
        GlobalLockManager lockManager = locks == null ? null : locks.get();
        if (lockManager != null) lockManager.acquire(resourceId, table, String.valueOf(key), xid);
    }

    private static Statement parse(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            throw new UnsupportedAtSqlException("Unsupported AT SQL: " + sql, e);
        }
    }

    private static Where where(Expression expression, String sql) {
        if (!(expression instanceof EqualsTo)) throw unsupported(sql);
        EqualsTo equal = (EqualsTo) expression;
        if (!(equal.getLeftExpression() instanceof Column)
                || !(equal.getRightExpression() instanceof JdbcParameter)) throw unsupported(sql);
        return new Where(((Column) equal.getLeftExpression()).getColumnName());
    }

    private static String findPrimaryKey(Connection c, Table table) throws SQLException {
        DatabaseMetaData metadata = c.getMetaData();
        String tableSchema = identifier(table.getSchemaName());
        String tableName = identifier(table.getName());

        // MySQL exposes the database name as a JDBC catalog rather than a schema. Passing a
        // null catalog makes getPrimaryKeys search every database, so identically named tables
        // can look like one table with a composite primary key.
        String catalog = tableSchema != null ? tableSchema : c.getCatalog();
        String schema = tableSchema != null ? tableSchema : c.getSchema();

        for (String candidate : tableNameCandidates(tableName)) {
            List<String> primaryKeys = readPrimaryKeys(metadata, catalog, schema, candidate);
            if (primaryKeys.isEmpty() && tableSchema != null) {
                // MySQL needs the parsed qualifier in the catalog argument and ignores schema.
                primaryKeys = readPrimaryKeys(metadata, tableSchema, null, candidate);
            }
            if (primaryKeys.isEmpty()) {
                continue;
            }
            if (primaryKeys.size() > 1) {
                throw new AtException("Composite primary keys are not supported: " + table);
            }
            return primaryKeys.get(0);
        }
        throw new AtException("Table must have a primary key: " + table);
    }

    private static List<String> readPrimaryKeys(
            DatabaseMetaData metadata, String catalog, String schema, String tableName)
            throws SQLException {
        SortedMap<Short, String> keys = new TreeMap<Short, String>();
        try (ResultSet result = metadata.getPrimaryKeys(catalog, schema, tableName)) {
            while (result.next()) {
                short sequence = result.getShort("KEY_SEQ");
                String column = result.getString("COLUMN_NAME");
                String existing = keys.put(sequence, column);
                if (existing != null && !existing.equalsIgnoreCase(column)) {
                    throw new AtException(
                            "Conflicting primary-key metadata for table " + tableName);
                }
            }
        }
        return new ArrayList<String>(keys.values());
    }

    private static List<String> tableNameCandidates(String tableName) {
        LinkedHashSet<String> candidates = new LinkedHashSet<String>();
        candidates.add(tableName);
        candidates.add(tableName.toUpperCase(Locale.ROOT));
        candidates.add(tableName.toLowerCase(Locale.ROOT));
        return new ArrayList<String>(candidates);
    }

    private static void assertPrimaryKey(Connection c, Table table, String column)
            throws SQLException {
        String actual = findPrimaryKey(c, table);
        if (!unquote(column).equalsIgnoreCase(actual))
            throw new AtException("WHERE column must be the primary key: " + table + "." + column);
    }

    private static String tableName(Table table) {
        if (table == null || table.getAlias() != null)
            throw new AtException("Aliased or missing table is not supported for AT SQL");
        return table.getFullyQualifiedName();
    }

    private static boolean internal(String table) {
        String normalized = unquote(table.substring(table.lastIndexOf('.') + 1));
        return normalized.toLowerCase(Locale.ROOT).startsWith("easy_at_");
    }

    private static boolean internalStatement(Statement statement) {
        if (statement instanceof Update)
            return internal(tableName(((Update) statement).getTable()));
        if (statement instanceof Delete)
            return internal(tableName(((Delete) statement).getTable()));
        if (statement instanceof Insert)
            return internal(tableName(((Insert) statement).getTable()));
        return false;
    }

    private static int indexOf(List<String> columns, String name) {
        for (int i = 0; i < columns.size(); i++)
            if (unquote(columns.get(i)).equalsIgnoreCase(name)) return i;
        return -1;
    }

    private static String identifier(String value) {
        return value == null ? null : unquote(value);
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2) return value;
        char first = value.charAt(0), last = value.charAt(value.length() - 1);
        return (first == '`' && last == '`')
                        || (first == '"' && last == '"')
                        || (first == '[' && last == ']')
                ? value.substring(1, value.length() - 1)
                : value;
    }

    private static String stripQuotes(String value) {
        return unquote(value);
    }

    private static Object require(Map<Integer, Object> p, int i) {
        if (!p.containsKey(i)) throw new AtException("Missing JDBC parameter " + i);
        return p.get(i);
    }

    private static Object column(RowImage image, String name) {
        for (Map.Entry<String, Object> entry : image.getColumns().entrySet())
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        throw new AtException("Before image does not contain column: " + name);
    }

    private RowImage select(Connection c, String rawTable, List<String> cols, String pk, Object key)
            throws SQLException {
        StringBuilder b = new StringBuilder("SELECT ");
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) b.append(',');
            b.append(dialect.quoteIdentifier(cols.get(i)));
        }
        b.append(" FROM ")
                .append(dialect.quoteTable(null, rawTable))
                .append(" WHERE ")
                .append(dialect.quoteIdentifier(pk))
                .append("=?");
        return query(c, b.toString(), key);
    }

    private RowImage selectAll(Connection c, String rawTable, String pk, Object key)
            throws SQLException {
        return query(
                c,
                "SELECT * FROM "
                        + dialect.quoteTable(null, rawTable)
                        + " WHERE "
                        + dialect.quoteIdentifier(pk)
                        + "=?",
                key);
    }

    private static RowImage query(Connection c, String sql, Object key) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setObject(1, key);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? image(r) : null;
            }
        }
    }

    private static RowImage image(ResultSet r) throws SQLException {
        ResultSetMetaData md = r.getMetaData();
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        for (int i = 1; i <= md.getColumnCount(); i++)
            values.put(md.getColumnLabel(i), r.getObject(i));
        return new RowImage(values);
    }

    private static boolean notEmpty(Collection<?> value) {
        return value != null && !value.isEmpty();
    }

    private static void reject(boolean condition, String sql) {
        if (condition) throw unsupported(sql);
    }

    private static UnsupportedAtSqlException unsupported(String sql) {
        return new UnsupportedAtSqlException(
                "Unsupported AT SQL; only single-row INSERT/UPDATE/DELETE by primary key with '?' values are allowed: "
                        + sql);
    }

    private static final class Where {
        final String column;

        Where(String column) {
            this.column = column;
        }
    }

    static final class Capture {
        final UndoRecord record;
        final String table, pk;
        final Object key;

        Capture(UndoRecord record, String table, String pk, Object key) {
            this.record = record;
            this.table = table;
            this.pk = pk;
            this.key = key;
        }
    }
}

package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;

/** Executes compensating SQL and refuses to overwrite data changed after this AT branch. */
public final class JdbcUndoExecutor implements UndoExecutor {
    private final Map<String, DataSource> resources;

    public JdbcUndoExecutor(Map<String, DataSource> resources) {
        this.resources = new HashMap<String, DataSource>(resources);
    }

    /**
     * 执行一条 undo（补偿）SQL。执行前先做脏写校验（{@link #assertNoDirtyWrite}）， 通过 {@link AtContext#beginUndo()}
     * 标记，避免 undo SQL 本身又被 AtDataSource 拦截。
     */
    @Override
    public void rollback(UndoRecord record) throws Exception {
        DataSource ds = resources.get(record.getResourceId());
        if (ds == null) throw new AtException("Unknown resource: " + record.getResourceId());
        AtContext.beginUndo();
        try (Connection c = ds.getConnection()) {
            assertNoDirtyWrite(c, record);
            try (PreparedStatement p = c.prepareStatement(record.getRollbackSql())) {
                Object[] values = record.getParameters();
                for (int i = 0; i < values.length; i++) bind(p, i + 1, values[i]);
                if (p.executeUpdate() != 1)
                    throw new AtException(
                            "Undo affected an unexpected number of rows: " + record.getId());
            }
        } finally {
            AtContext.endUndo();
        }
    }

    /**
     * 脏写校验（DESIGN.md §7.2）：回滚前把当前行与 after image 逐列比对。
     *
     * <ul>
     *   <li>当前行 == after image → 允许 undo；
     *   <li>当前行 != after image（或行缺失/多出）→ 抛 {@link DirtyWriteException}， 拒绝覆盖并发修改的数据，交人工处理。
     * </ul>
     */
    private void assertNoDirtyWrite(Connection c, UndoRecord record) throws SQLException {
        String sql =
                "SELECT * FROM "
                        + record.getTableName()
                        + " WHERE "
                        + record.getPrimaryKeyColumn()
                        + "=?";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            bind(
                    p,
                    1,
                    coerce(
                            c,
                            record.getTableName(),
                            record.getPrimaryKeyColumn(),
                            record.getPrimaryKeyValue()));
            try (ResultSet rs = p.executeQuery()) {
                boolean exists = rs.next();
                RowImage expected = record.getAfterImage();
                if (expected == null) {
                    if (exists)
                        throw new DirtyWriteException(
                                "Dirty write detected for deleted row: "
                                        + record.getTableName()
                                        + "/"
                                        + record.getPrimaryKeyValue());
                    return;
                }
                if (!exists)
                    throw new DirtyWriteException(
                            "Dirty write detected: row is missing: "
                                    + record.getTableName()
                                    + "/"
                                    + record.getPrimaryKeyValue());
                ResultSetMetaData meta = rs.getMetaData();
                Map<String, Object> actual =
                        new TreeMap<String, Object>(String.CASE_INSENSITIVE_ORDER);
                for (int i = 1; i <= meta.getColumnCount(); i++)
                    actual.put(meta.getColumnLabel(i), rs.getObject(i));
                for (Map.Entry<String, Object> entry : expected.getColumns().entrySet()) {
                    if (!actual.containsKey(entry.getKey())
                            || !sameValue(actual.get(entry.getKey()), entry.getValue()))
                        throw new DirtyWriteException(
                                "Dirty write detected for "
                                        + record.getTableName()
                                        + "/"
                                        + record.getPrimaryKeyValue()
                                        + ", column="
                                        + entry.getKey());
                }
            }
        }
    }

    /**
     * 按参数值的<b>实际 Java 类型</b>选择 JDBC setter，而不是统一用 {@code setObject}。
     *
     * <p>原因（真实 PostgreSQL 用例暴露的 P0 缺陷）：{@code setObject} 把类型推断交给驱动，PostgreSQL 驱动 会把一个 {@code Long}
     * 主键值当成 {@code varchar} 发送，于是 {@code WHERE id=?} 变成 {@code bigint = character varying}，数据库直接报错
     * {@code ERROR: operator does not exist: bigint = character varying}——<b>PostgreSQL 上的回滚 100%
     * 失败</b>。 H2 与 MySQL 会做隐式转换掩盖这个问题，所以只有真库能发现。
     *
     * <p>null 仍走 {@code setNull} 的通用路径：
     */
    static void bind(PreparedStatement p, int index, Object value) throws SQLException {
        if (value == null) {
            p.setNull(index, java.sql.Types.NULL);
        } else if (value instanceof Long) {
            p.setLong(index, (Long) value);
        } else if (value instanceof Integer) {
            p.setInt(index, (Integer) value);
        } else if (value instanceof Short) {
            p.setShort(index, (Short) value);
        } else if (value instanceof Double) {
            p.setDouble(index, (Double) value);
        } else if (value instanceof Float) {
            p.setFloat(index, (Float) value);
        } else if (value instanceof BigDecimal) {
            p.setBigDecimal(index, (BigDecimal) value);
        } else if (value instanceof String) {
            p.setString(index, (String) value);
        } else if (value instanceof Boolean) {
            p.setBoolean(index, (Boolean) value);
        } else if (value instanceof Timestamp) {
            p.setTimestamp(index, (Timestamp) value);
        } else if (value instanceof java.util.Date) {
            p.setTimestamp(index, new Timestamp(((java.util.Date) value).getTime()));
        } else {
            p.setObject(index, value);
        }
    }

    /**
     * 把主键值按目标列的真实类型还原。
     *
     * <p>为什么必须做：{@code easy_at_undo_log.pk_value} 是 VARCHAR 列，{@code JdbcAtRepository} 读回时用 {@code
     * getString}，因此<b>无论业务主键原本是什么类型，取回来一律是 String</b>。MySQL 与 H2 会做隐式类型 转换把这个问题藏起来，PostgreSQL
     * 不会——它会直接报 {@code ERROR: operator does not exist: bigint = character varying}，导致<b>PG 上的回滚
     * 100% 失败</b>。
     *
     * <p>因此这里查一次列元数据，把字符串还原成列本身的类型再绑定。元数据结果按 表.列 缓存，避免每条 undo 都往返一次数据库。查不到（例如表已被删除）时原样返回，交给驱动处理。
     */
    private static Object coerce(Connection c, String table, String column, Object value)
            throws SQLException {
        if (!(value instanceof String)) return value;
        Integer type = columnType(c, table, column);
        if (type == null) return value;
        String s = ((String) value).trim();
        try {
            switch (type.intValue()) {
                case Types.BIGINT:
                case Types.INTEGER:
                case Types.SMALLINT:
                case Types.TINYINT:
                    return Long.valueOf(s);
                case Types.NUMERIC:
                case Types.DECIMAL:
                    return new BigDecimal(s);
                case Types.DOUBLE:
                case Types.FLOAT:
                case Types.REAL:
                    return Double.valueOf(s);
                default:
                    return value;
            }
        } catch (NumberFormatException notNumeric) {
            return value;
        }
    }

    private static final Map<String, Integer> COLUMN_TYPES =
            new ConcurrentHashMap<String, Integer>();

    private static Integer columnType(Connection c, String table, String column) {
        String key = table + "." + column;
        Integer cached = COLUMN_TYPES.get(key);
        if (cached != null) return cached;
        for (String name : new String[] {table, table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rs = c.getMetaData().getColumns(null, null, name, column)) {
                if (rs.next()) {
                    Integer type = Integer.valueOf(rs.getInt("DATA_TYPE"));
                    COLUMN_TYPES.put(key, type);
                    return type;
                }
            } catch (SQLException ignored) {
                /* fall through to the next candidate */
            }
        }
        return null;
    }

    private boolean sameValue(Object left, Object right) {
        if (left instanceof Number && right instanceof Number)
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        if (left instanceof byte[] && right instanceof byte[])
            return Arrays.equals((byte[]) left, (byte[]) right);
        return Objects.equals(left, right);
    }
}

package io.github.easyat.jdbc;

/**
 * 兜底方言：不做任何引用（标识符原样返回）。仅在无法探测到数据库产品时使用，
 * 此时 AT 仍可用，但拼出的 SQL 在大小写敏感或含保留字的数据库上可能出错。
 */
public final class GenericAtSqlDialect implements AtSqlDialect {
    /** @return 方言产品名，固定为 {@code Generic}。 */
    @Override
    public String productName() {
        return "Generic";
    }

    /** 不引用，直接原样返回标识符（兜底方言不做转义）。 */
    @Override
    public String quoteIdentifier(String id) {
        return id;
    }

    /** 兜底方言不认识任何保留字，恒返回 false。 */
    @Override
    public boolean isReservedWord(String id) {
        return false;
    }
}

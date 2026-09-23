package io.github.easyat.jdbc;

/** Fallback dialect that performs no quoting. Used when the product cannot be detected. */
public final class GenericAtSqlDialect implements AtSqlDialect {
    @Override
    public String productName() {
        return "Generic";
    }

    @Override
    public String quoteIdentifier(String id) {
        return id;
    }

    @Override
    public boolean isReservedWord(String id) {
        return false;
    }
}

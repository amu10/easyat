package io.github.easyat.jdbc;

/** SQL dialect abstraction so identifier quoting, schema/catalog handling and reserved words are portable. */
public interface AtSqlDialect {
    String productName();
    /** Quote a single identifier (column, table or schema). Already-quoted identifiers are returned unchanged. */
    String quoteIdentifier(String identifier);
    boolean isReservedWord(String identifier);
    /** Quote a possibly schema-qualified table reference. */
    default String quoteTable(String schema, String table){
        String t=quoteIdentifier(table);
        if(schema==null||schema.isEmpty())return t;
        return quoteIdentifier(schema)+"."+t;
    }
}

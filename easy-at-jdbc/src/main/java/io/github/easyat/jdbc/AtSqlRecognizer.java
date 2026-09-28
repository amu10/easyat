package io.github.easyat.jdbc;

import net.sf.jsqlparser.statement.Statement;

/** Converts a parsed DML statement into a validated, database-independent execution plan. */
interface AtSqlRecognizer<S extends Statement, P> {
    P recognize(S statement, AtSqlDialect dialect);
}

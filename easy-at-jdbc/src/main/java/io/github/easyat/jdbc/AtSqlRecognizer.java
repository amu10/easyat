package io.github.easyat.jdbc;

import net.sf.jsqlparser.statement.Statement;

/**
 * SQL 识别器：把 JSqlParser 解析出的某类 DML 语句转换为「与数据库无关、已校验」的执行计划（Plan）。
 * 每种 DML（INSERT/UPDATE/DELETE/通用快照）各有一个实现。
 */
// Converts a parsed DML statement into a validated, database-independent execution plan.
interface AtSqlRecognizer<S extends Statement, P> {
    /** 解析并校验语句，返回带方言信息的执行计划；不合法/不支持的语句抛出 UnsupportedAtSqlException。 */
    P recognize(S statement, AtSqlDialect dialect);
}

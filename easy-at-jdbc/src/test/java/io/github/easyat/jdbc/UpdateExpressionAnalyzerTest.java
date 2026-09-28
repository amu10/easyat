package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import org.junit.jupiter.api.Test;

class UpdateExpressionAnalyzerTest {
    private final Column target = new Column("balance");

    @Test
    void acceptsWhitelistedFunctionsAndCountsParameters() throws Exception {
        assertEquals(1, count("COALESCE(balance, ?)", new MysqlAtSqlDialect()));
        assertEquals(0, count("ABS(balance)", new PostgresAtSqlDialect()));
        assertEquals(0, count("NOW()", new MysqlAtSqlDialect()));
        assertEquals(0, count("CURRENT_TIMESTAMP", new PostgresAtSqlDialect()));
    }

    @Test
    void rejectsUnknownFunctionsCrossColumnArgumentsAndDialectMismatch() throws Exception {
        assertEquals(-1, count("RAND()", new MysqlAtSqlDialect()));
        assertEquals(-1, count("COALESCE(credit, ?)", new MysqlAtSqlDialect()));
        assertEquals(-1, count("NOW()", new GenericAtSqlDialect()));
    }

    private int count(String sql, AtSqlDialect dialect) throws Exception {
        return UpdateExpressionAnalyzer.parameterCount(
                target, CCJSqlParserUtil.parseExpression(sql), dialect);
    }
}

package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.easyat.core.UnsupportedAtSqlException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.insert.Insert;
import org.junit.jupiter.api.Test;

class InsertRecognizerTest {
    private final InsertRecognizer recognizer = new InsertRecognizer();

    @Test
    void recognizesParameterizedValues() throws Exception {
        InsertRecognizer.Plan plan =
                recognizer.recognize(
                        (Insert)
                                CCJSqlParserUtil.parse(
                                        "INSERT INTO account(id,balance) VALUES(?,?)"),
                        new PostgresAtSqlDialect());
        assertEquals(2, plan.columns.size());
        assertEquals("id", plan.columns.get(0));
    }

    @Test
    void rejectsLiteralInsertValues() throws Exception {
        Insert insert =
                (Insert) CCJSqlParserUtil.parse("INSERT INTO account(id,balance) VALUES(?,100)");
        assertThrows(
                UnsupportedAtSqlException.class,
                () -> recognizer.recognize(insert, new PostgresAtSqlDialect()));
    }
}

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

    /** 字面量值此前被拒绝，现在支持：多行 INSERT 里混用字面量很常见，而主键值只要能算出来就能建 undo。 （非主键列的字面量无需还原，undo 是按主键 DELETE。） */
    @Test
    void acceptsLiteralInsertValues() throws Exception {
        InsertRecognizer.Plan plan =
                recognizer.recognize(
                        (Insert)
                                CCJSqlParserUtil.parse(
                                        "INSERT INTO account(id,balance) VALUES(?,100)"),
                        new PostgresAtSqlDialect());
        assertEquals(2, plan.columns.size());
        assertEquals(1, plan.rows.size());
    }

    @Test
    void recognizesMultipleValueRows() throws Exception {
        InsertRecognizer.Plan plan =
                recognizer.recognize(
                        (Insert)
                                CCJSqlParserUtil.parse(
                                        "INSERT INTO account(id,balance) VALUES(?,?),(?,?),(?,?)"),
                        new PostgresAtSqlDialect());
        assertEquals(3, plan.rows.size());
        assertEquals(2, plan.rows.get(0).size());
    }

    /** INSERT ... SELECT 仍然拒绝：被插入的行执行前不存在，拿不到主键就无法补偿。 */
    @Test
    void rejectsInsertSelect() throws Exception {
        Insert insert =
                (Insert)
                        CCJSqlParserUtil.parse(
                                "INSERT INTO account(id,balance) SELECT aid,amount FROM frozen WHERE status=?");
        assertThrows(
                UnsupportedAtSqlException.class,
                () -> recognizer.recognize(insert, new PostgresAtSqlDialect()));
    }
}

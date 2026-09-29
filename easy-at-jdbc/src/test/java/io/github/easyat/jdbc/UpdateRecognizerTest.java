package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.easyat.core.UnsupportedAtSqlException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.update.Update;
import org.junit.jupiter.api.Test;

class UpdateRecognizerTest {
    private final UpdateRecognizer recognizer = new UpdateRecognizer();

    @Test
    void recognizesAssignmentParametersAndPrimaryKeyPredicate() throws Exception {
        UpdateRecognizer.Plan plan =
                recognizer.recognize(
                        (Update)
                                CCJSqlParserUtil.parse(
                                        "UPDATE account SET balance=COALESCE(balance,?)+? WHERE id=?"),
                        new MysqlAtSqlDialect());
        assertEquals(2, plan.assignmentParameterCount);
        assertEquals("id", plan.primaryKeyColumn);
        assertEquals(1, plan.columns.size());
    }

    @Test
    void rejectsUnsafePredicate() throws Exception {
        Update update =
                (Update)
                        CCJSqlParserUtil.parse(
                                "UPDATE account SET balance=? WHERE id=? OR status=?");
        assertThrows(
                UnsupportedAtSqlException.class,
                () -> recognizer.recognize(update, new MysqlAtSqlDialect()));
    }
}

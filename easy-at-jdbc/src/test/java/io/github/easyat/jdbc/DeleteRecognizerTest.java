package io.github.easyat.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.easyat.core.UnsupportedAtSqlException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.delete.Delete;
import org.junit.jupiter.api.Test;

class DeleteRecognizerTest {
    private final DeleteRecognizer recognizer = new DeleteRecognizer();

    @Test
    void recognizesPrimaryKeyDelete() throws Exception {
        DeleteRecognizer.Plan plan =
                recognizer.recognize(
                        (Delete) CCJSqlParserUtil.parse("DELETE FROM account WHERE id=?"),
                        new MysqlAtSqlDialect());
        assertEquals("id", plan.primaryKeyColumn);
        assertEquals("account", plan.rawTable);
    }

    @Test
    void rejectsNonPrimaryKeyShapeBeforeMetadataAccess() throws Exception {
        Delete delete = (Delete) CCJSqlParserUtil.parse("DELETE FROM account WHERE id IN (?,?)");
        assertThrows(
                UnsupportedAtSqlException.class,
                () -> recognizer.recognize(delete, new MysqlAtSqlDialect()));
    }
}

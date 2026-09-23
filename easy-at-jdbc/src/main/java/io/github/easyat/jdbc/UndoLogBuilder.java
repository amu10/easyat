package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import java.util.UUID;

public final class UndoLogBuilder {
    private UndoLogBuilder() {}

    public static UndoRecord of(
            String resource,
            String table,
            String pk,
            Object value,
            String rollbackSql,
            Object... parameters) {
        String xid = AtContext.xid();
        if (xid == null) throw new AtException("No active AT transaction");
        return new UndoRecord(
                UUID.randomUUID().toString(),
                xid,
                resource,
                table,
                pk,
                value,
                rollbackSql,
                parameters);
    }
}

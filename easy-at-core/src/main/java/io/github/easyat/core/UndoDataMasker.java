package io.github.easyat.core;

/** SPI to mask sensitive column values before they are written to logs or diagnostics. */
public interface UndoDataMasker {
    /** @return true if the given resource/table/column holds sensitive data and should be masked. */
    boolean shouldMask(String resourceId, String tableName, String column);
    /** @return the masked representation (e.g. "***") shown in logs/diagnostics only. */
    String mask(String resourceId, String tableName, String column, Object value);
}

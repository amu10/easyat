package io.github.easyat.core;

public interface UndoExecutor {
    void rollback(UndoRecord record) throws Exception;
}

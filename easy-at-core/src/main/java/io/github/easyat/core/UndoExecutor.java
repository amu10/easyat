package io.github.easyat.core;

/**
 * 补偿（回滚）执行器：把一条 {@link UndoRecord} 反映的 before image 写回数据库。
 *
 * <p>实现必须先做脏写校验（当前行是否仍等于 after image），再执行 rollback 语句； 执行期间通过 {@link AtContext#beginUndo()} 标记自己，避免
 * undo SQL 被再次代理、产生二次 undo。
 */
public interface UndoExecutor {
    void rollback(UndoRecord record) throws Exception;
}

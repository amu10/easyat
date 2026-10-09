package io.github.easyat.core;

/**
 * 当 undo 执行器回滚某行时，发现该行当前内容已不等于记录的 after-image（即已被别人脏写）， 抛出此异常。
 *
 * <p>该异常意味着数据一致性已无法仅靠框架自动回滚保证，必须把事务转入 {@code DIRTY_WRITE} 终态， 等待人工介入修复。
 */
public final class DirtyWriteException extends AtException {
    /** 用描述信息构造异常，异常信息通常携带发生脏写的表名，便于人工定位。 */
    public DirtyWriteException(String message) {
        super(message);
    }

    /** 用描述信息与底层原因构造异常，保留原始异常链。 */
    public DirtyWriteException(String message, Throwable cause) {
        super(message, cause);
    }
}

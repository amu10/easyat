package io.github.easyat.core;

/**
 * 当在配置的等待超时内无法获取到全局行锁时抛出。
 *
 * <p>说明当前行已被另一个全局事务持有锁（且未释放），本事务应进入回滚或重试路径， 而不是继续推进，否则会破坏 AT 的隔离性。
 */
public final class GlobalLockConflictException extends AtException {
    /** 用描述信息构造异常，通常包含资源/表/主键等冲突定位信息。 */
    public GlobalLockConflictException(String message) {
        super(message);
    }

    /** 用描述信息与底层原因构造异常，保留原始异常链。 */
    public GlobalLockConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}

package io.github.easyat.core;

/**
 * 线程级 AT 上下文，用两个 ThreadLocal 在请求线程内传递事务状态。
 *
 * <ul>
 *   <li>{@code XID}：当前线程绑定的全局事务 ID。{@code active()} 判断是否处于 AT 事务中， 这是 {@code AtDataSource} 决定「要不要生成
 *       undo log」的唯一开关。
 *   <li>{@code UNDOING}：回滚补偿执行标记。当框架自己执行 undo SQL 时置为 true， 防止 undo SQL 又被 {@code AtDataSource}
 *       拦截、产生二次 undo log（无限递归）。
 * </ul>
 *
 * <p>两个 ThreadLocal 都在请求结束（AOP finally / Filter）时清理，避免线程池复用导致的串台。
 */
public final class AtContext {
    private static final ThreadLocal<String> XID = new ThreadLocal<String>();
    private static final ThreadLocal<Boolean> UNDOING = new ThreadLocal<Boolean>();

    private AtContext() {}

    /** 返回当前线程绑定的全局事务 ID；不在事务中时返回 null。 */
    public static String xid() {
        return XID.get();
    }

    /** 当前线程是否处于 AT 全局事务中（即 XID 已被绑定）。这是 {@code AtDataSource} 判断是否生成 undo log 的唯一开关。 */
    public static boolean active() {
        return XID.get() != null;
    }

    /** 当前是否正在由框架执行 undo 补偿（防止 undo SQL 被再次拦截产生二次 undo log）。 */
    public static boolean undoing() {
        return Boolean.TRUE.equals(UNDOING.get());
    }

    /** 置位 undo 补偿标记：框架开始执行 undo SQL 前调用，使后续 undo 语句不被 {@code AtDataSource} 再拦截。 */
    public static void beginUndo() {
        UNDOING.set(Boolean.TRUE);
    }

    /** 清除 undo 补偿标记：undo SQL 执行结束后调用。 */
    public static void endUndo() {
        UNDOING.remove();
    }

    /** 把指定 xid 绑定到当前线程，开启该线程的 AT 事务上下文。 */
    public static void bind(String xid) {
        XID.set(xid);
    }

    /** 解绑当前线程的 xid，通常在请求结束（AOP finally / Filter）时调用，避免线程池复用串台。 */
    public static void clear() {
        XID.remove();
    }
}

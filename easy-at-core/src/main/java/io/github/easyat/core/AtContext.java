package io.github.easyat.core;
/**
 * 线程级 AT 上下文，用两个 ThreadLocal 在请求线程内传递事务状态。
 *
 * <ul>
 *   <li>{@code XID}：当前线程绑定的全局事务 ID。{@code active()} 判断是否处于 AT 事务中，
 *       这是 {@code AtDataSource} 决定「要不要生成 undo log」的唯一开关。</li>
 *   <li>{@code UNDOING}：回滚补偿执行标记。当框架自己执行 undo SQL 时置为 true，
 *       防止 undo SQL 又被 {@code AtDataSource} 拦截、产生二次 undo log（无限递归）。</li>
 * </ul>
 *
 * <p>两个 ThreadLocal 都在请求结束（AOP finally / Filter）时清理，避免线程池复用导致的串台。
 */
public final class AtContext {
  private static final ThreadLocal<String> XID = new ThreadLocal<String>();
  private static final ThreadLocal<Boolean> UNDOING = new ThreadLocal<Boolean>();
  private AtContext() { }
  public static String xid() { return XID.get(); } public static boolean active() { return XID.get()!=null; }
  public static boolean undoing() { return Boolean.TRUE.equals(UNDOING.get()); } public static void beginUndo(){UNDOING.set(Boolean.TRUE);} public static void endUndo(){UNDOING.remove();}
  public static void bind(String xid) { XID.set(xid); } public static void clear() { XID.remove(); }
}

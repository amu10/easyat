package io.github.easyat.core;
public final class AtContext {
  private static final ThreadLocal<String> XID = new ThreadLocal<String>();
  private static final ThreadLocal<Boolean> UNDOING = new ThreadLocal<Boolean>();
  private AtContext() { }
  public static String xid() { return XID.get(); } public static boolean active() { return XID.get()!=null; }
  public static boolean undoing() { return Boolean.TRUE.equals(UNDOING.get()); } public static void beginUndo(){UNDOING.set(Boolean.TRUE);} public static void endUndo(){UNDOING.remove();}
  public static void bind(String xid) { XID.set(xid); } public static void clear() { XID.remove(); }
}

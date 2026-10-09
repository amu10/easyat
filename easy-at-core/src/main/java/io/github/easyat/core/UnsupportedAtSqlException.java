package io.github.easyat.core;

/**
 * 在真正执行 DML 之前抛出，表示这条 SQL 无法被安全补偿（生成可靠的 undo / 反向 SQL）。
 *
 * <p>例如多表 JOIN 更新、不带主键的 UPDATE/DELETE、或框架无法解析的语句都会触发， 目的是"提前失败"，避免产生无法回滚的脏数据。
 */
public final class UnsupportedAtSqlException extends AtException {
    /** 用描述信息构造异常，说明具体不支持的 SQL 形态。 */
    public UnsupportedAtSqlException(String message) {
        super(message);
    }

    /** 用描述信息与底层原因构造异常，保留原始异常链。 */
    public UnsupportedAtSqlException(String message, Throwable cause) {
        super(message, cause);
    }
}

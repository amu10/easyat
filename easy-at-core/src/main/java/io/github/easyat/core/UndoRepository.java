package io.github.easyat.core;

import java.util.List;

/**
 * undo 记录的独立存储 SPI。
 *
 * <p>把 undo 持久化从 {@link AtRepository} 里拆出来，是为了支持<b>混合存储</b>： undo log 必须与业务 DML 在同一个本地事务里提交（这是 AT
 * 正确性的前提），所以它必须在业务库里； 而全局事务状态、分支、锁可以在 Redis 这类独立存储里。
 *
 * <p>三种组合：
 *
 * <ul>
 *   <li><b>jdbc</b>：{@code AtRepository} 与 {@code UndoRepository} 是同一个 {@code JdbcAtRepository}——本
 *       SPI 不介入，行为与之前完全一致。
 *   <li><b>redis</b>：undo 由 {@code RedisAtRepository} 自己管（{@code UndoRepository} 传 null）。 注意此组合下
 *       undo 不与业务本地事务原子提交，详见 {@code PRODUCTION_GAPS.md} §18.4。
 *   <li><b>hybrid</b>：{@code AtRepository = RedisAtRepository}、{@code UndoRepository =
 *       JdbcUndoRepository}。undo 走业务连接，其余走 Redis。
 * </ul>
 *
 * <p>若实现同时需要处理"业务连接"，请实现 {@link ConnectionBoundUndoRepository}。
 */
public interface UndoRepository {

    /** 读取某个事务的全部 undo 记录，按写入顺序返回。 */
    List<UndoRecord> load(String xid);

    /**
     * 整体替换某个事务的 undo 记录（先删后插）。
     *
     * <p>与 {@code AtRepository#save} 一样：这个方法<b>不负责状态迁移</b>，状态必须走 CAS。
     */
    void replaceAll(String xid, List<UndoRecord> records);

    /** 删除某个事务的全部 undo 记录。清理链路用。 */
    void deleteByXid(String xid);
}

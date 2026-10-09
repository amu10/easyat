package io.github.easyat.core;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 把多个 {@link UndoRepository} 聚合成一个，用于<b>多数据源场景</b>。
 *
 * <h2>为什么需要它</h2>
 *
 * <p>单数据源时，混合存储只装配一个 {@code UndoRepository}（见 {@code JdbcUndoRepository}）， 一切正常。但业务一旦有多个 DataSource，就会出两个问题：
 *
 * <ol>
 *   <li><b>装配期直接失败</b>：自动配置里 {@code UndoRepository} 需要注入一个 {@code DataSource}， 多数据源会触发
 *       {@code NoUniqueBeanDefinitionException}，或者静默只认 {@code @Primary} 那一个。
 *   <li><b>更危险的静默错误</b>：即使侥幸注入成功，{@code load(xid)} 也只会查那一个库。 而回滚是靠 {@code
 *       AtTransactionManager#required} → {@code load(xid)} 拿到 undo 列表再逐条补偿的—— 于是<b>其它库的 undo
 *       永远不会被补偿</b>，事务处于半回滚状态却显示 ROLLED_BACK。
 * </ol>
 *
 * <h2>语义</h2>
 *
 * <ul>
 *   <li>{@link #load(String)}：<b>跨所有库合并</b>。每个库内部按自己的 created_at 有序， 跨库之间按数据源声明顺序拼接（不同库的表通常互不相干，
 *       跨库的先后一般不影响正确性）。
 *   <li>{@link #replaceAll(String, List)}：<b>按 resourceId 分组下发</b>。每个库各自「先删 xid 全部、再插入属于自己的那部分」。
 *       必须对<b>每个</b>库都下发（可能是空集合）——否则某个库的 undo 被 {@code discardUndo} 摘掉后， 残留行永远删不掉。
 *   <li>{@link #deleteByXid(String)}：<b>广播到所有库</b>（清理链路级联用）。
 *   <li>连接绑定三件套（{@link #append} / {@link #updateUndo} / {@link #removeUndo}）： SQL 全部在<b>传入的那条业务连接</b>上执行，因此落到哪个库由连接本身决定，
 *       与选哪个 delegate 无关。这里仍优先按 resourceId 精确匹配，匹配不到就退回第一个， 只为语义清晰、便于排障。
 * </ul>
 */
public final class CompositeUndoRepository implements ConnectionBoundUndoRepository {

    /** resourceId → 该库对应的 undo 仓库。 */
    private final Map<String, UndoRepository> byResource;

    /** 去重后的 delegate 列表，保证遍历顺序稳定可预期。 */
    private final List<UndoRepository> delegates;

    /**
     * @param byResource resourceId → undo 仓库，至少一项
     * @throws IllegalArgumentException 传入为空
     */
    public CompositeUndoRepository(Map<String, UndoRepository> byResource) {
        if (byResource == null || byResource.isEmpty())
            throw new IllegalArgumentException("CompositeUndoRepository requires at least one delegate");
        LinkedHashMap<String, UndoRepository> copy = new LinkedHashMap<String, UndoRepository>(byResource);
        this.byResource = Collections.unmodifiableMap(copy);
        LinkedHashSet<UndoRepository> unique = new LinkedHashSet<UndoRepository>(copy.values());
        this.delegates =
                Collections.unmodifiableList(new ArrayList<UndoRepository>(unique));
    }

    /** resourceId → 仓库 的只读视图（排障/自测用）。 */
    public Map<String, UndoRepository> getByResource() {
        return byResource;
    }

    /** 聚合的库数量。 */
    public int size() {
        return delegates.size();
    }

    /**
     * 跨所有库读取同一事务的 undo 记录。
     *
     * <p>某个库查询失败会直接抛出——多数据源下静默吞掉异常会导致"少补偿一部分却显示回滚成功"， 那正是本类要消灭的假绿。
     */
    @Override
    public List<UndoRecord> load(String xid) {
        List<UndoRecord> out = new ArrayList<UndoRecord>();
        for (UndoRepository delegate : delegates) {
            List<UndoRecord> part = delegate.load(xid);
            if (part != null) out.addAll(part);
        }
        return out;
    }

    /**
     * 整体替换：按 resourceId 把记录分发到各自所属的库。
     *
     * <p>每个库都调用一次（可能是空列表），从而把"本轮已不再属于该库"的残留 undo 一并清掉。
     */
    @Override
    public void replaceAll(String xid, List<UndoRecord> records) {
        Map<UndoRepository, List<UndoRecord>> grouped = groupByOwner(records);
        for (UndoRepository delegate : delegates) {
            List<UndoRecord> subset = grouped.get(delegate);
            delegate.replaceAll(xid, subset == null ? Collections.<UndoRecord>emptyList() : subset);
        }
    }

    /** 广播删除：清理链路级联回收业务库 undo 日志时用。 */
    @Override
    public void deleteByXid(String xid) {
        for (UndoRepository delegate : delegates) delegate.deleteByXid(xid);
    }

    /** 在业务连接上写入 undo——落库由连接决定，此处仅做 resourceId 精确匹配以便排障。 */
    @Override
    public void append(Connection connection, UndoRecord record) {
        bound(record == null ? null : record.getResourceId()).append(connection, record);
    }

    /** 在业务连接上更新 after image（连接决定落哪个库）。 */
    @Override
    public void updateUndo(Connection connection, String xid, String undoId, RowImage afterImage) {
        bound(null).updateUndo(connection, xid, undoId, afterImage);
    }

    /** 在业务连接上删除一条 undo（连接决定落哪个库）。 */
    @Override
    public void removeUndo(Connection connection, String xid, String undoId) {
        bound(null).removeUndo(connection, xid, undoId);
    }

    /** 按 resourceId 把记录归到各自的仓库；无法识别 resourceId 时归到第一个（主）库。 */
    private Map<UndoRepository, List<UndoRecord>> groupByOwner(List<UndoRecord> records) {
        Map<UndoRepository, List<UndoRecord>> grouped =
                new LinkedHashMap<UndoRepository, List<UndoRecord>>();
        if (records == null) return grouped;
        for (UndoRecord r : records) {
            UndoRepository owner = null;
            if (r != null && r.getResourceId() != null) owner = byResource.get(r.getResourceId());
            if (owner == null) owner = delegates.get(0);
            List<UndoRecord> bucket = grouped.get(owner);
            if (bucket == null) {
                bucket = new ArrayList<UndoRecord>();
                grouped.put(owner, bucket);
            }
            bucket.add(r);
        }
        return grouped;
    }

    /**
     * 选出一个能走业务连接的仓库：优先 resourceId 精确匹配，否则取第一个连接绑定的实现。
     *
     * @throws AtException 没有任何 delegate 实现 {@link ConnectionBoundUndoRepository}
     */
    private ConnectionBoundUndoRepository bound(String resourceId) {
        if (resourceId != null) {
            UndoRepository hit = byResource.get(resourceId);
            if (hit instanceof ConnectionBoundUndoRepository)
                return (ConnectionBoundUndoRepository) hit;
        }
        for (UndoRepository delegate : delegates)
            if (delegate instanceof ConnectionBoundUndoRepository)
                return (ConnectionBoundUndoRepository) delegate;
        throw new AtException("No connection-bound undo repository among " + delegates.size() + " delegates");
    }
}

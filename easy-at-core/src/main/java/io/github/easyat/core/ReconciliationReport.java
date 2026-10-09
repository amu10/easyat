package io.github.easyat.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 影子运行对账报告。
 *
 * <p>投产前必须连续观察若干天（建议 ≥7 天）的对象就是这份报告里的几个数字。它们对应的都是 <b>静默故障</b>——系统不会报错、不会告警、也不会自行收敛，只能靠对账发现：
 *
 * <ul>
 *   <li><b>残留事务</b>：ACTIVE 已超时却没人推进、ROLLING_BACK/COMMITTING 卡住不动 → 事务永不收敛， 全局锁也跟着泄漏。
 *   <li><b>锁泄漏</b>：全局锁还挂着，但它所属的事务已经终态（提交/回滚/人工介入）或干脆不存在 → 其他事务访问同一行会被永久拒绝。
 *   <li><b>MANUAL_INTERVENTION 计数</b>：框架承认"自己搞不定，需要人来看"的次数。这个值 > 0 就说明 有数据需要人工修复。
 *   <li><b>脏写 / 回滚失败</b>：回滚时发现行已被别人改过，或补偿反复失败。
 *   <li><b>分支悬挂</b>（跨服务）：分支已终态但全局事务还在途，或反之。
 * </ul>
 */
public final class ReconciliationReport {

    /** 严重级别：OK 可直接放量，WARN 需排查，CRITICAL 必须立刻人工介入。 */
    public enum Level {
        OK,
        WARN,
        CRITICAL
    }

    private final long generatedAt;
    private final String node;
    private final long stuckAfterMillis;

    /** 超时未收敛的 ACTIVE 事务。 */
    private final List<String> activeTimedOut = new ArrayList<String>();

    /** 卡在 ROLLING_BACK（没有有效租约在推进）的事务。 */
    private final List<String> rollingBackStuck = new ArrayList<String>();

    /** 卡在 COMMITTING（本地已提交、全局未收敛）的事务。 */
    private final List<String> committingStuck = new ArrayList<String>();

    private final List<String> rollbackFailed = new ArrayList<String>();
    private final List<String> dirtyWrite = new ArrayList<String>();
    private final List<String> manualIntervention = new ArrayList<String>();

    /** 所属事务已终态或已不存在、却仍被持有的全局锁。 */
    private final List<String> leakedLocks = new ArrayList<String>();

    /** 分支已终态但全局事务仍在途（跨服务场景的悬挂信号）。 */
    private final List<String> hangingBranches = new ArrayList<String>();

    private int scannedTransactions;
    private int scannedLocks;

    public ReconciliationReport(String node, long stuckAfterMillis) {
        this.generatedAt = System.currentTimeMillis();
        this.node = node == null ? "unknown" : node;
        this.stuckAfterMillis = stuckAfterMillis;
    }

    public long getGeneratedAt() {
        return generatedAt;
    }

    public String getNode() {
        return node;
    }

    public long getStuckAfterMillis() {
        return stuckAfterMillis;
    }

    public List<String> getActiveTimedOut() {
        return activeTimedOut;
    }

    public List<String> getRollingBackStuck() {
        return rollingBackStuck;
    }

    public List<String> getCommittingStuck() {
        return committingStuck;
    }

    public List<String> getRollbackFailed() {
        return rollbackFailed;
    }

    public List<String> getDirtyWrite() {
        return dirtyWrite;
    }

    public List<String> getManualIntervention() {
        return manualIntervention;
    }

    public List<String> getLeakedLocks() {
        return leakedLocks;
    }

    public List<String> getHangingBranches() {
        return hangingBranches;
    }

    public void setScannedTransactions(int n) {
        this.scannedTransactions = n;
    }

    public void setScannedLocks(int n) {
        this.scannedLocks = n;
    }

    /** 需要人工处理的总条数。 */
    public int problemCount() {
        return activeTimedOut.size()
                + rollingBackStuck.size()
                + committingStuck.size()
                + rollbackFailed.size()
                + dirtyWrite.size()
                + manualIntervention.size()
                + leakedLocks.size()
                + hangingBranches.size();
    }

    public Level level() {
        if (!manualIntervention.isEmpty()
                || !dirtyWrite.isEmpty()
                || !hangingBranches.isEmpty()
                || !leakedLocks.isEmpty()) return Level.CRITICAL;
        if (problemCount() > 0) return Level.WARN;
        return Level.OK;
    }

    /** 转成可直接 JSON 序列化的结构（管理端点与 CLI 共用）。 */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("generatedAt", generatedAt);
        m.put("node", node);
        m.put("stuckAfterMillis", stuckAfterMillis);
        m.put("level", level().name());
        m.put("healthy", problemCount() == 0);
        m.put("problemCount", problemCount());
        Map<String, Object> counts = new LinkedHashMap<String, Object>();
        counts.put("scannedTransactions", scannedTransactions);
        counts.put("scannedLocks", scannedLocks);
        counts.put("activeTimedOut", activeTimedOut.size());
        counts.put("rollingBackStuck", rollingBackStuck.size());
        counts.put("committingStuck", committingStuck.size());
        counts.put("rollbackFailed", rollbackFailed.size());
        counts.put("dirtyWrite", dirtyWrite.size());
        counts.put("manualIntervention", manualIntervention.size());
        counts.put("leakedLocks", leakedLocks.size());
        counts.put("hangingBranches", hangingBranches.size());
        m.put("counts", counts);
        // 样本最多各 20 条，避免端点返回体过大
        m.put("samples", samples());
        return m;
    }

    private Map<String, Object> samples() {
        Map<String, Object> s = new LinkedHashMap<String, Object>();
        s.put("activeTimedOut", head(activeTimedOut));
        s.put("rollingBackStuck", head(rollingBackStuck));
        s.put("committingStuck", head(committingStuck));
        s.put("rollbackFailed", head(rollbackFailed));
        s.put("dirtyWrite", head(dirtyWrite));
        s.put("manualIntervention", head(manualIntervention));
        s.put("leakedLocks", head(leakedLocks));
        s.put("hangingBranches", head(hangingBranches));
        return s;
    }

    private static List<String> head(List<String> in) {
        return new ArrayList<String>(in.subList(0, Math.min(20, in.size())));
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("easyAt reconciliation @ ")
                .append(node)
                .append(" level=")
                .append(level().name())
                .append(" problems=")
                .append(problemCount())
                .append('\n');
        sb.append("  scanned: transactions=")
                .append(scannedTransactions)
                .append(" locks=")
                .append(scannedLocks)
                .append('\n');
        line(sb, "activeTimedOut     ", activeTimedOut);
        line(sb, "rollingBackStuck   ", rollingBackStuck);
        line(sb, "committingStuck    ", committingStuck);
        line(sb, "rollbackFailed     ", rollbackFailed);
        line(sb, "dirtyWrite         ", dirtyWrite);
        line(sb, "manualIntervention ", manualIntervention);
        line(sb, "leakedLocks        ", leakedLocks);
        line(sb, "hangingBranches    ", hangingBranches);
        if (problemCount() == 0) sb.append("  no anomalies found\n");
        return sb.toString();
    }

    private static void line(StringBuilder sb, String name, List<String> items) {
        sb.append("  ")
                .append(name)
                .append('=')
                .append(items.size())
                .append(items.isEmpty() ? "" : " " + head(items))
                .append('\n');
    }
}

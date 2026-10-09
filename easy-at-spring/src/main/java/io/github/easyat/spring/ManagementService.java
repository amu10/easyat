package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.time.Instant;
import java.util.*;

/**
 * Servlet-agnostic management/ops API core: inspect transactions, force a retry or rollback, and
 * keep an in-memory audit trail of every human action. The Boot 2/Boot 3 starters expose this
 * through {@code @RestController} adapters and perform the token check.
 */
public final class ManagementService {
    /** 事务/分支存储，用于查询事务、强制状态迁移。 */
    private final AtRepository repository;

    /** 全局事务管理器，执行强制回滚/状态迁移。 */
    private final AtTransactionManager manager;

    /** 指标采集器。 */
    private final EasyAtMetrics metrics;

    /** 管理端点是否启用；关闭时所有操作鉴权直接失败。 */
    private final boolean enabled;

    /** 管理端点访问 token；为空表示启用但不鉴权（dev 方便）。 */
    private final String token;

    /** undo 数据编解码器，用于把 before/after image 转成诊断字符串（可脱敏）。 */
    private final UndoDataCodec codec;

    /** 人类操作的内存审计轨迹（线程安全列表）。 */
    private final List<AuditEntry> audit =
            Collections.synchronizedList(new ArrayList<AuditEntry>());

    /** 可选的对账服务；挂上后 reconciliation() 才返回真实报告。 */
    private ReconciliationService reconciliation;

    public ManagementService(
            AtRepository repository,
            AtTransactionManager manager,
            EasyAtMetrics metrics,
            boolean enabled,
            String token,
            UndoDataCodec codec) {
        this.repository = repository;
        this.manager = manager;
        this.metrics = metrics;
        this.enabled = enabled;
        this.token = token;
        this.codec = codec;
    }

    /**
     * 挂上对账服务（可选）。挂上后 {@code GET /_easy-at/v1/reconciliation} 才会返回真实报告， 否则返回 {@code
     * not_configured}——没有存储/锁管理器就没法对账，明确说出来好过给个空绿报告。
     */
    public void setReconciliationService(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    /**
     * 影子运行对账报告：残留事务、锁泄漏、MANUAL_INTERVENTION / DIRTY_WRITE 计数。
     *
     * <p>投产前建议连续 ≥7 天定时拉取并做告警，阈值见 {@code RUNBOOK.md}。
     */
    public Map<String, Object> reconciliation() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (reconciliation == null) {
            m.put("error", "not_configured");
            m.put(
                    "hint",
                    "no ReconciliationService wired (needs AtRepository + GlobalLockManager)");
            return m;
        }
        return reconciliation.report().toMap();
    }

    /**
     * @return 管理端点是否启用。
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns true when the supplied admin token is acceptable. When disabled, never authorized.
     */
    public boolean authorized(String providedToken) {
        if (!enabled) return false;
        if (token == null || token.isEmpty())
            return true; // enabled without a token: dev convenience
        return token.equals(providedToken);
    }

    /**
     * 查询单个事务详情（含 undo 诊断信息）。
     *
     * @param xid 全局事务 id
     * @return 事务映射，不存在时返回 {@code not_found} 错误体
     */
    public Map<String, Object> getTransaction(String xid) {
        Optional<AtTransaction> tx = repository.find(xid);
        if (!tx.isPresent()) return notFound(xid);
        return toMap(tx.get());
    }

    /**
     * 按状态列举事务。
     *
     * @param status 状态名（{@link AtStatus} 枚举名）
     * @param limit 最多返回条数
     * @return 事务映射列表
     */
    public List<Map<String, Object>> listByStatus(String status, int limit) {
        AtStatus s = AtStatus.valueOf(status);
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (AtTransaction tx : repository.findByStatus(s, limit)) out.add(toMap(tx));
        return out;
    }

    /** Re-enters the recovery path for a stuck transaction (e.g. MANUAL_INTERVENTION). */
    public Map<String, Object> retry(String xid, String operator, String reason) {
        AtTransaction tx = required(xid);
        if (tx.getStatus().isTerminal())
            return bad("Transaction already terminal: " + tx.getStatus());
        manager.forceTransition(xid, AtStatus.ROLLING_BACK);
        audit("retry", xid, operator, reason, "re-entered ROLLING_BACK");
        metrics.recordManualIntervention();
        return toMap(required(xid));
    }

    /** Force a rollback now. */
    public Map<String, Object> rollback(String xid, String operator, String reason) {
        required(xid);
        manager.forceTransition(xid, AtStatus.ROLLING_BACK);
        try {
            manager.rollback(xid);
        } catch (AtException e) {
            audit("rollback", xid, operator, reason, "failed: " + e.getMessage());
            throw e;
        }
        audit("rollback", xid, operator, reason, "ok");
        return toMap(required(xid));
    }

    /**
     * @return 全部人工操作审计记录的快照。
     */
    public List<AuditEntry> audit() {
        return new ArrayList<AuditEntry>(audit);
    }

    /**
     * 按 xid 取出事务，不存在则抛 {@link AtException}。
     *
     * @param xid 全局事务 id
     * @return 事务对象
     */
    private AtTransaction required(String xid) {
        return repository
                .find(xid)
                .orElseThrow(() -> new AtException("Transaction not found: " + xid));
    }

    /**
     * 把事务对象转成诊断用的映射，附带 undo 的 before/after 诊断字符串（经 {@link UndoDataCodec} 脱敏）。
     *
     * @param tx 事务对象
     * @return 可读的映射结构
     */
    private Map<String, Object> toMap(AtTransaction tx) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("xid", tx.getXid());
        m.put("name", tx.getName());
        m.put("status", tx.getStatus().name());
        m.put("retries", tx.getRetries());
        m.put("owner", tx.getOwner());
        m.put("leaseUntil", tx.getLeaseUntil() == 0 ? null : tx.getLeaseUntil());
        m.put("nextRetryAt", tx.getNextRetryAt() == 0 ? null : tx.getNextRetryAt());
        if (tx.getDirtyWriteTable() != null) m.put("dirtyWriteTable", tx.getDirtyWriteTable());
        if (tx.getDirtyWriteKey() != null) m.put("dirtyWriteKey", tx.getDirtyWriteKey());
        if (codec != null) {
            List<Map<String, Object>> undo = new ArrayList<Map<String, Object>>();
            for (UndoRecord r : tx.getUndoRecords()) {
                Map<String, Object> u = new LinkedHashMap<String, Object>();
                u.put("id", r.getId());
                u.put("resourceId", r.getResourceId());
                u.put("table", r.getTableName());
                UndoContext ctx = new UndoContext(r.getResourceId(), r.getTableName());
                if (r.getBeforeImage() != null)
                    u.put("before", codec.toDiagnosticString(r.getBeforeImage(), ctx));
                if (r.getAfterImage() != null)
                    u.put("after", codec.toDiagnosticString(r.getAfterImage(), ctx));
                undo.add(u);
            }
            m.put("undo", undo);
        }
        return m;
    }

    /** 构造 not_found 错误体。 */
    private Map<String, Object> notFound(String xid) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("error", "not_found");
        m.put("xid", xid);
        return m;
    }

    /** 构造通用错误体。 */
    private Map<String, Object> bad(String msg) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("error", msg);
        return m;
    }

    /** 记录一条人类操作审计。 */
    private void audit(String action, String xid, String operator, String reason, String result) {
        audit.add(new AuditEntry(Instant.now(), action, xid, operator, reason, result));
    }

    /** 一条人工操作审计记录（操作时间、动作、xid、操作人、原因、结果）。 */
    public static final class AuditEntry {
        public final Instant at;
        public final String action, xid, operator, reason, result;

        AuditEntry(
                Instant at,
                String action,
                String xid,
                String operator,
                String reason,
                String result) {
            this.at = at;
            this.action = action;
            this.xid = xid;
            this.operator = operator;
            this.reason = reason;
            this.result = result;
        }
    }
}

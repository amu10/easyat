package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

/**
 * Coordinates AT branches: registration, idempotent commit/rollback delivery, and reliable retry.
 * Local branches are resolved through the in-process {@link AtTransactionManager}; remote branches
 * are driven over HTTP to their registered callback URL with HMAC-signed headers.
 *
 * <p>Correctness rules implemented here (these are what make cross-service rollback safe):
 *
 * <ul>
 *   <li><b>Empty rollback</b>: if a rollback arrives for a branch that was never registered, we
 *       persist a {@code ROLLED_BACK} placeholder instead of throwing. Retry therefore converges,
 *       and — more importantly — the placeholder is the evidence the business side later needs to
 *       reject the late-arriving request.
 *   <li><b>Anti-hanging</b>: a branch that is already {@code ROLLED_BACK} must never be "revived"
 *       by a late business request, otherwise its undo would sit under a terminal global
 *       transaction and never run.
 *   <li><b>CAS transitions</b>: every status change is a compare-and-set, so concurrent recovery
 *       and delivery cannot silently overwrite each other.
 *   <li><b>No whitewashing</b>: a branch in {@code ROLLING_BACK}/{@code ROLLBACK_FAILED} is never
 *       promoted to {@code COMMITTED} by an out-of-order commit.
 * </ul>
 */
public final class BranchCoordinator {
    /** 分支存储，负责分支的注册、查询、CAS 状态迁移与重试信息更新。 */
    private final BranchRepository branches;

    /** 本地事务管理器，用于驱动本进程内分支的提交/回滚。 */
    private final AtTransactionManager manager;

    /** 用于给跨服务回调请求头签名的 HMAC 签名器。 */
    private final HmacSigner signer;

    /** 本服务名，写入回调请求 SOURCE 头。 */
    private final String appName;

    /** 指标采集器（可为 null）。 */
    private final EasyAtMetrics metrics;

    /** 用于向远程分支回调地址发送 commit/rollback 的 HTTP 客户端。 */
    private final RestTemplate rest = new RestTemplate();

    public BranchCoordinator(
            BranchRepository branches,
            AtTransactionManager manager,
            HmacSigner signer,
            String appName,
            EasyAtMetrics metrics) {
        this.branches = branches;
        this.manager = manager;
        this.signer = signer;
        this.appName = appName;
        this.metrics = metrics;
    }

    /**
     * 注册一个分支。对同一 (xid, resourceId) 幂等——已存在时直接返回既有分支 id。 若同一资源已被回滚过，则抛出 {@link AtException}（防悬挂）。
     *
     * @param xid 全局事务 id
     * @param resourceId 资源 id
     * @param serviceName 发起方服务名
     * @param callbackUrl 远程回调地址；本地分支传 null
     * @return 分支 id
     */
    public synchronized String register(
            String xid, String resourceId, String serviceName, String callbackUrl) {
        Optional<AtBranch> existing = branches.findByXidResource(xid, resourceId);
        if (existing.isPresent()) {
            AtBranch found = existing.get();
            // 防悬挂：该资源已被回滚过，绝不能再让业务 DML 挂到这个已终结的分支上。
            if (found.getStatus().isRolledBack())
                throw new AtException(
                        "Branch already rolled back for xid=" + xid + " resource=" + resourceId);
            return found.getBranchId();
        }
        AtBranch b =
                new AtBranch(
                        xid,
                        resourceId,
                        serviceName,
                        normalize(callbackUrl),
                        branches.byXid(xid).size() + 1);
        branches.register(b);
        return b.getBranchId();
    }

    /** 驱动某全局事务下所有分支提交（本地只提交一次，远程逐个回调）。 */
    public void commit(String xid) {
        drive(xid, false);
    }

    /** 驱动某全局事务下所有分支回滚（本地只回滚一次，远程逐个回调）。 */
    public void rollback(String xid) {
        drive(xid, true);
    }

    /** 提交单个分支（按 branchId）。 */
    public BranchStatus commitBranch(String branchId) {
        return act(branchId, false, null, null);
    }

    /** 回滚单个分支（按 branchId）。 */
    public BranchStatus rollbackBranch(String branchId) {
        return act(branchId, true, null, null);
    }

    /**
     * Rollback with full branch coordinates. Carrying {@code xid}/{@code resourceId} lets the
     * receiving side perform an empty rollback and leave the anti-hanging evidence behind.
     */
    public BranchStatus rollbackBranch(String branchId, String xid, String resourceId) {
        return act(branchId, true, xid, resourceId);
    }

    private BranchStatus act(String branchId, boolean rollback, String xid, String resourceId) {
        Optional<AtBranch> found = branches.find(branchId);
        if (!found.isPresent()) {
            AtBranch alias = branches.findByXidResource(xid, resourceId).orElse(null);
            if (alias != null) return actExisting(alias, rollback);
            if (!rollback)
                // 提交时分支不存在，说明该资源从未执行过 DML。不能凭空认定 COMMITTED（那会洗掉真实的
                // 失败状态），抛异常让调用端退避重试，最终由重试上限收敛到人工介入。
                throw new AtException("Branch not found: " + branchId);
            return emptyRollback(branchId, xid, resourceId);
        }
        return actExisting(found.get(), rollback);
    }

    /**
     * 空回滚（Empty Rollback）：rollback 先于分支注册到达（请求还在路上就超时了，或注册前进程崩溃）。
     *
     * <p>必须落一条 {@code ROLLED_BACK} 占位记录而不是报错：① 让协调者的重试幂等成功，不再无限重试； ② 给随后迟到的业务请求留下"本资源已被回滚"的证据，由
     * {@link DefaultBranchRegistrar} 拒绝执行， 从而彻底阻止悬挂数据。
     */
    private BranchStatus emptyRollback(String branchId, String xid, String resourceId) {
        if (xid == null || resourceId == null)
            throw new AtException(
                    "Cannot empty-rollback without xid/resourceId, branch=" + branchId);
        long now = System.currentTimeMillis();
        AtBranch placeholder =
                new AtBranch(
                        branchId,
                        xid,
                        resourceId,
                        appName == null ? "unknown" : appName,
                        null,
                        1,
                        BranchStatus.ROLLED_BACK,
                        0,
                        now,
                        now,
                        0L);
        try {
            branches.register(placeholder);
        } catch (RuntimeException concurrent) {
            // 并发下可能已有占位或真实分支写入；再次校验，只要已是回滚态即视为成功（幂等）。
            AtBranch fresh = branches.findByXidResource(xid, resourceId).orElse(null);
            if (fresh == null || !fresh.getStatus().isRolledBack())
                throw new AtException("Cannot record empty rollback for " + branchId, concurrent);
        }
        return BranchStatus.ROLLED_BACK;
    }

    private BranchStatus actExisting(AtBranch b, boolean rollback) {
        if (b.getStatus().isTerminal()) return b.getStatus();
        BranchStatus target = rollback ? BranchStatus.ROLLED_BACK : BranchStatus.COMMITTED;
        // 已进入回滚流程的分支不允许被乱序 commit 洗白，否则回滚失败会被静默固化为"已提交"。
        if (!rollback && !b.getStatus().canTransitionTo(BranchStatus.COMMITTED))
            return b.getStatus();
        if (isLocal(b)) {
            if (rollback) manager.rollback(b.getXid());
            return advance(b, target);
        }
        return deliverRemote(b, rollback);
    }

    private void drive(String xid, boolean rollback) {
        List<AtBranch> list = new ArrayList<AtBranch>(branches.byXid(xid));
        if (rollback)
            // 逆序通知：后注册的分支先撤销，与 undo 的逆序补偿语义一致。
            Collections.sort(
                    list,
                    new Comparator<AtBranch>() {
                        public int compare(AtBranch a, AtBranch c) {
                            return Integer.compare(c.getSequence(), a.getSequence());
                        }
                    });
        boolean localHandled = false;
        RuntimeException failure = null;
        for (AtBranch b : list) {
            if (b.getStatus().isTerminal()) continue;
            try {
                if (isLocal(b)) {
                    // 本地分支共享同一个全局事务，只回滚一次
                    if (rollback && !localHandled) {
                        manager.rollback(xid);
                        localHandled = true;
                    }
                    advance(b, rollback ? BranchStatus.ROLLED_BACK : BranchStatus.COMMITTED);
                } else {
                    deliverRemote(b, rollback);
                }
            } catch (RuntimeException e) {
                // 单个分支失败必须隔离：否则剩余分支永久停在 REGISTERED，再也没人驱动它们。
                failure = e;
            }
        }
        if (failure != null) throw failure;
    }

    private BranchStatus deliverRemote(AtBranch b, boolean rollback) {
        BranchStatus target = rollback ? BranchStatus.ROLLED_BACK : BranchStatus.COMMITTED;
        try {
            String url = b.getCallbackUrl() + (rollback ? "/rollback" : "/commit");
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            long deadline = System.currentTimeMillis() + 30000L;
            headers.set(AtTransportHeaders.XID, b.getXid());
            headers.set(AtTransportHeaders.DEADLINE, Long.toString(deadline));
            headers.set(AtTransportHeaders.SOURCE, appName);
            if (signer.isConfigured())
                headers.set(
                        AtTransportHeaders.SIGNATURE, signer.sign(b.getXid(), deadline, appName));
            // 携带 xid/resourceId，让对端在分支缺失时也能完成空回滚并留下防悬挂证据。
            Map<String, String> body = new HashMap<String, String>();
            body.put("branchId", b.getBranchId());
            body.put("xid", b.getXid());
            body.put("resourceId", b.getResourceId());
            ResponseEntity<String> res =
                    rest.postForEntity(
                            url, new HttpEntity<Map<String, String>>(body, headers), String.class);
            if (res.getStatusCode().is2xxSuccessful()) return advance(b, target);
            return fail(b, b.getXid());
        } catch (Exception e) {
            return fail(b, e.getMessage());
        }
    }

    /** 投递失败：写失败态并记录退避后的下一次重试点，避免 next_retry_at 为空时被立即重复投递。 */
    private BranchStatus fail(AtBranch b, String reason) {
        BranchStatus reached = advance(b, BranchStatus.ROLLBACK_FAILED);
        int retries = b.getRetries() + 1;
        branches.updateRecovery(
                b.getBranchId(), retries, System.currentTimeMillis() + backoff(retries));
        if (metrics != null) metrics.recordRollbackFailure();
        return reached;
    }

    /** CAS 推进分支状态。任何非法或被打断的迁移都返回存储中的真实值，绝不无条件覆盖——这保证了 "曾经发生过什么"不会被后来的写入抹掉。 */
    private BranchStatus advance(AtBranch b, BranchStatus next) {
        BranchStatus current = b.getStatus();
        if (current == next) return next;
        if (!current.canTransitionTo(next) || !branches.transition(b.getBranchId(), current, next))
            return branches.find(b.getBranchId()).map(AtBranch::getStatus).orElse(current);
        return next;
    }

    private boolean isLocal(AtBranch b) {
        return b.getCallbackUrl() == null || b.getCallbackUrl().isEmpty();
    }

    private static String normalize(String url) {
        return url == null || url.trim().isEmpty() ? null : url.trim();
    }

    /** 指数退避：1s、2s、4s… 上限 5 分钟。 */
    private static long backoff(int retries) {
        return Math.min(300000L, 1000L << Math.min(retries, 8));
    }
}

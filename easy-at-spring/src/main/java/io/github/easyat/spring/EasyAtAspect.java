package io.github.easyat.spring;

import io.github.easyat.annotation.EasyAtTransactional;
import io.github.easyat.core.*;
import java.lang.reflect.Method;
import org.aspectj.lang.*;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;

/**
 * Runs as the OUTER aspect (high precedence) so the Spring {@code @Transactional} interceptor opens
 * the local transaction inside the easyAt global transaction. Business DML and the undo log
 * therefore share the same local database transaction.
 *
 * <p>After the local commit/rollback it drives any registered branches through the {@link
 * BranchCoordinator}, so cross-service commit/rollback is coordinated from here.
 */
@Aspect
@Order(100)
public final class EasyAtAspect {
    private static final Logger log = LoggerFactory.getLogger(EasyAtAspect.class);

    /** 全局事务管理器，负责 begin/commit/rollback 与本地 undo 推进。 */
    private final AtTransactionManager manager;

    /** 分支协调器，业务完成后由其驱动跨服务分支的提交/回滚（可为 null）。 */
    private final BranchCoordinator coordinator;

    /** 指标采集器（可为 null）。 */
    private final EasyAtMetrics metrics;

    private final AtGrayDecider grayDecider;
    private final AtGrayKeyResolver grayKeyResolver;
    private final String applicationName;

    public EasyAtAspect(AtTransactionManager m) {
        this(m, null, null, null, null);
    }

    public EasyAtAspect(AtTransactionManager m, BranchCoordinator c, EasyAtMetrics metrics) {
        this(m, c, metrics, null, null);
    }

    public EasyAtAspect(
            AtTransactionManager m,
            BranchCoordinator c,
            EasyAtMetrics metrics,
            AtGrayDecider grayDecider,
            EasyAtProperties properties) {
        manager = m;
        coordinator = c;
        this.metrics = metrics;
        this.grayDecider = grayDecider;
        this.grayKeyResolver = new AtGrayKeyResolver();
        this.applicationName =
                properties == null ? "unknown-service" : properties.getApplicationName();
    }

    @Around("@annotation(io.github.easyat.annotation.EasyAtTransactional)")
    public Object around(ProceedingJoinPoint p) throws Throwable {
        // 若当前线程已处于 AT 事务（嵌套/传播），直接放行，避免重复开事务
        if (AtContext.active()) return p.proceed();
        Method m = ((MethodSignature) p.getSignature()).getMethod();
        EasyAtTransactional a = m.getAnnotation(EasyAtTransactional.class);
        AtGrayDecision gray = decideGray(p, m, a);
        if (metrics != null) metrics.recordGrayDecision(a.grayScene(), gray);
        putGrayMdc(a.grayScene(), gray);
        if (!gray.isEnabled()) {
            try {
                return p.proceed();
            } finally {
                clearGrayMdc();
            }
        }
        String name =
                a.name().isEmpty()
                        ? m.getDeclaringClass().getSimpleName() + "." + m.getName()
                        : a.name();
        // 开全局事务：持久化 ACTIVE 行 + 绑定 XID 到线程上下文
        AtTransaction tx = manager.begin(name, a.timeout());
        try {
            Object result = p.proceed();
            // 业务成功：本地事务已提交，这里收敛全局状态并协调各分支提交
            manager.commit(tx.getXid());
            if (coordinator != null) coordinator.commit(tx.getXid());
            if (metrics != null) metrics.recordCommit();
            return result;
        } catch (Throwable e) {
            // 业务异常：先回滚本地 undo，再通知各分支回滚；回滚失败只附加为 suppressed，不吞原异常
            try {
                manager.rollback(tx.getXid());
            } catch (Throwable rollback) {
                e.addSuppressed(rollback);
            }
            try {
                if (coordinator != null) coordinator.rollback(tx.getXid());
            } catch (Throwable rollback) {
                e.addSuppressed(rollback);
            }
            if (metrics != null) metrics.recordRollback();
            throw e;
        } finally {
            AtContext.clear();
            clearGrayMdc();
        }
    }

    private AtGrayDecision decideGray(
            ProceedingJoinPoint point, Method method, EasyAtTransactional annotation) {
        if (annotation.grayScene().trim().isEmpty() || grayDecider == null)
            return AtGrayDecision.enabled("LEGACY_FULL", -1);
        try {
            String key = grayKeyResolver.resolve(point, annotation.grayKey());
            return grayDecider.decide(
                    new AtGrayRequest(
                            annotation.grayScene(),
                            key,
                            applicationName,
                            method.getDeclaringClass().getName() + "." + method.getName()));
        } catch (RuntimeException failure) {
            // 灰度基础设施异常时不创建新的分布式事务；普通本地事务仍可继续。
            log.warn(
                    "easyAt gray decision failed for scene={}, new AT transaction disabled",
                    annotation.grayScene(),
                    failure);
            return AtGrayDecision.disabled("DECISION_FAILURE", -1);
        }
    }

    private static void putGrayMdc(String scene, AtGrayDecision decision) {
        MDC.put("atGrayScene", scene == null || scene.isEmpty() ? "legacy" : scene);
        MDC.put("atGrayEnabled", String.valueOf(decision.isEnabled()));
        MDC.put("atGrayReason", decision.getReason());
        if (decision.getBucket() >= 0)
            MDC.put("atGrayBucket", String.valueOf(decision.getBucket()));
    }

    private static void clearGrayMdc() {
        MDC.remove("atGrayScene");
        MDC.remove("atGrayEnabled");
        MDC.remove("atGrayReason");
        MDC.remove("atGrayBucket");
    }
}

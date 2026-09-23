package io.github.easyat.spring;

import io.github.easyat.annotation.EasyAtTransactional;
import io.github.easyat.core.*;
import org.aspectj.lang.*;
import org.aspectj.lang.annotation.*;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;

import java.lang.reflect.Method;

/**
 * Runs as the OUTER aspect (high precedence) so the Spring {@code @Transactional} interceptor
 * opens the local transaction inside the easyAt global transaction. Business DML and the undo
 * log therefore share the same local database transaction.
 *
 * <p>After the local commit/rollback it drives any registered branches through the
 * {@link BranchCoordinator}, so cross-service commit/rollback is coordinated from here.
 */
@Aspect
@Order(100)
public final class EasyAtAspect {
    private final AtTransactionManager manager;
    private final BranchCoordinator coordinator;
    private final EasyAtMetrics metrics;

    public EasyAtAspect(AtTransactionManager m) {
        this(m, null, null);
    }

    public EasyAtAspect(AtTransactionManager m, BranchCoordinator c, EasyAtMetrics metrics) {
        manager = m;
        coordinator = c;
        this.metrics = metrics;
    }

    @Around("@annotation(io.github.easyat.annotation.EasyAtTransactional)")
    public Object around(ProceedingJoinPoint p) throws Throwable {
        // 若当前线程已处于 AT 事务（嵌套/传播），直接放行，避免重复开事务
        if (AtContext.active()) return p.proceed();
        Method m = ((MethodSignature) p.getSignature()).getMethod();
        EasyAtTransactional a = m.getAnnotation(EasyAtTransactional.class);
        String name = a.name().isEmpty() ? m.getDeclaringClass().getSimpleName() + "." + m.getName() : a.name();
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
        }
    }
}

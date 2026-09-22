package io.github.easyat.spring;
import io.github.easyat.annotation.EasyAtTransactional; import io.github.easyat.core.*; import org.aspectj.lang.*; import org.aspectj.lang.annotation.*; import org.aspectj.lang.reflect.MethodSignature; import org.springframework.core.annotation.Order; import java.lang.reflect.Method;
/**
 * Runs as the OUTER aspect (high precedence) so the Spring {@code @Transactional} interceptor
 * opens the local transaction inside the easyAt global transaction. Business DML and the undo
 * log therefore share the same local database transaction.
 *
 * <p>After the local commit/rollback it drives any registered branches through the
 * {@link BranchCoordinator}, so cross-service commit/rollback is coordinated from here.
 */
@Aspect @Order(100) public final class EasyAtAspect {
  private final AtTransactionManager manager; private final BranchCoordinator coordinator; private final EasyAtMetrics metrics;
  public EasyAtAspect(AtTransactionManager m){this(m,null,null);}
  public EasyAtAspect(AtTransactionManager m,BranchCoordinator c,EasyAtMetrics metrics){manager=m;coordinator=c;this.metrics=metrics;}
  @Around("@annotation(io.github.easyat.annotation.EasyAtTransactional)") public Object around(ProceedingJoinPoint p)throws Throwable{
      if(AtContext.active())return p.proceed();
      Method m=((MethodSignature)p.getSignature()).getMethod();
      EasyAtTransactional a=m.getAnnotation(EasyAtTransactional.class);
      String name=a.name().isEmpty()?m.getDeclaringClass().getSimpleName()+"."+m.getName():a.name();
      AtTransaction tx=manager.begin(name,a.timeout());
      try{
          Object result=p.proceed();
          manager.commit(tx.getXid());
          if(coordinator!=null)coordinator.commit(tx.getXid());
          if(metrics!=null)metrics.recordCommit();
          return result;
      }catch(Throwable e){
          try{manager.rollback(tx.getXid());}catch(Throwable rollback){e.addSuppressed(rollback);}
          try{if(coordinator!=null)coordinator.rollback(tx.getXid());}catch(Throwable rollback){e.addSuppressed(rollback);}
          if(metrics!=null)metrics.recordRollback();
          throw e;
      }finally{AtContext.clear();}
  }
}

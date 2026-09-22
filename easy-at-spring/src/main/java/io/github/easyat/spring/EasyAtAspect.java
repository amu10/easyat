package io.github.easyat.spring;
import io.github.easyat.annotation.EasyAtTransactional; import io.github.easyat.core.*; import org.aspectj.lang.*; import org.aspectj.lang.annotation.*; import org.aspectj.lang.reflect.MethodSignature; import java.lang.reflect.Method;
@Aspect public final class EasyAtAspect {
  private final AtTransactionManager manager; public EasyAtAspect(AtTransactionManager m){manager=m;}
  @Around("@annotation(io.github.easyat.annotation.EasyAtTransactional)") public Object around(ProceedingJoinPoint p)throws Throwable{if(AtContext.active())return p.proceed();Method m=((MethodSignature)p.getSignature()).getMethod();EasyAtTransactional a=m.getAnnotation(EasyAtTransactional.class);String name=a.name().isEmpty()?m.getDeclaringClass().getSimpleName()+"."+m.getName():a.name();AtTransaction tx=manager.begin(name,a.timeout());try{Object result=p.proceed();manager.commit(tx.getXid());return result;}catch(Throwable e){try{manager.rollback(tx.getXid());}catch(Throwable rollback){e.addSuppressed(rollback);}throw e;}finally{AtContext.clear();}}
}

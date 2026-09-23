package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * JDBC DataSource 代理，是 AT 事务「拦截点」。
 *
 * <p>它用 JDK 动态代理包装了 {@link Connection} 与 {@link PreparedStatement}，在受支持的单行
 * DML 真正执行<b>之前</b>，先完成三件 AT 前置动作，再由 {@link SqlUndoLogGenerator} 生成并
 * 持久化 undo log（见 {@link AtContext#active()} 与 {@link AtContext#undoing()} 两个开关）：
 *
 * <ol>
 *   <li>校验是否存在 Spring 本地事务（当 {@code requireLocalTransaction=true} 时）；</li>
 *   <li>{@code capture}：解析 SQL → 查 before image → 抢全局行锁 → 写 undo log；</li>
 *   <li>执行原始 DML 后 {@code after}：回写 after image（成功）或 {@code abort}（失败丢弃 undo）。</li>
 * </ol>
 *
 * <p>注意：代理只拦截带占位符的 {@code prepareStatement(String)} 路径；
 * 框架自身的 undo SQL 通过 {@link AtContext#undoing()} 标记被显式放行，不重复代理。
 */
public final class AtDataSource implements DataSource {
    private final String resourceId; private final DataSource delegate; private final SqlUndoLogGenerator generator; private final LocalTransactionBridge bridge; private final boolean requireLocalTransaction;
    public AtDataSource(String resourceId, DataSource delegate, AtTransactionManager manager, GlobalLockManager locks) {
        this(resourceId,delegate,manager,locks,LocalTransactionBridge.NOOP,false,BranchRegistrar.NOOP);
    }
    public AtDataSource(String resourceId, DataSource delegate, AtTransactionManager manager, GlobalLockManager locks, LocalTransactionBridge bridge, boolean requireLocalTransaction) {
        this(resourceId,delegate,manager,locks,bridge,requireLocalTransaction,BranchRegistrar.NOOP);
    }
    public AtDataSource(String resourceId, DataSource delegate, AtTransactionManager manager, GlobalLockManager locks, LocalTransactionBridge bridge, boolean requireLocalTransaction, BranchRegistrar registrar) {
        this.resourceId=resourceId;this.delegate=delegate;this.bridge=bridge;this.requireLocalTransaction=requireLocalTransaction;
        this.generator=new SqlUndoLogGenerator(resourceId,manager,locks,bridge,registrar);
    }
    public AtDataSource(String resourceId,DataSource delegate,java.util.function.Supplier<AtTransactionManager> manager,java.util.function.Supplier<GlobalLockManager> locks,LocalTransactionBridge bridge,boolean requireLocalTransaction,BranchRegistrar registrar){
        this.resourceId=resourceId;this.delegate=delegate;this.bridge=bridge;this.requireLocalTransaction=requireLocalTransaction;
        this.generator=new SqlUndoLogGenerator(resourceId,manager,locks,bridge,new GenericAtSqlDialect(),registrar);
    }
    public DataSource getDelegate(){return delegate;} public String getResourceId(){return resourceId;}
    public Connection getConnection() throws SQLException{return proxy(delegate.getConnection());}
    public Connection getConnection(String user,String password)throws SQLException{return proxy(delegate.getConnection(user,password));}
    private Connection proxy(Connection target){return (Connection)Proxy.newProxyInstance(target.getClass().getClassLoader(),new Class[]{Connection.class},new ConnectionHandler(target));}
    private final class ConnectionHandler implements InvocationHandler {
        private final Connection target; ConnectionHandler(Connection target){this.target=target;}
        public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
            if(method.getName().equals("prepareStatement")&&args!=null&&args.length>0&&args[0] instanceof String)return statement((PreparedStatement)call(target,method,args),(String)args[0]);
            return call(target,method,args);
        }
    }
    private PreparedStatement statement(PreparedStatement target,String sql){return (PreparedStatement)Proxy.newProxyInstance(target.getClass().getClassLoader(),new Class[]{PreparedStatement.class},new StatementHandler(target,sql));}
    private final class StatementHandler implements InvocationHandler {
        private final PreparedStatement target; private final String sql; private final Map<Integer,Object> params=new HashMap<Integer,Object>();
        StatementHandler(PreparedStatement target,String sql){this.target=target;this.sql=sql;}
        public Object invoke(Object proxy,Method method,Object[] args)throws Throwable{
            String name=method.getName();
            // 记录 setXxx 传入的占位符参数，供 SQL 生成器拼装 undo 时定位主键与列值
            if(name.startsWith("set")&&args!=null&&args.length>=2&&args[0] instanceof Integer){params.put((Integer)args[0],args[1]);return call(target,method,args);}
            if(name.equals("clearParameters")){params.clear();return call(target,method,args);}
            // 仅当「处于 AT 事务中」且「当前不是在执行框架自己的 undo SQL」时才介入
            if((name.equals("executeUpdate")||name.equals("execute")||name.equals("executeLargeUpdate"))&&AtContext.active()&&!AtContext.undoing()){
                if(requireLocalTransaction&&!bridge.isActive())throw new AtException("easyAt requires a Spring local transaction on resource '"+resourceId+"'; annotate the method with @Transactional");
                Connection connection=target.getConnection();
                // capture：解析 SQL、查 before image、抢全局锁、写 undo log
                SqlUndoLogGenerator.Capture capture=generator.capture(connection,sql,params);
                try { Object result=call(target,method,args); generator.after(connection,capture); return result; }
                catch(Throwable failure){try{generator.abort(connection,capture);}catch(Throwable cleanup){failure.addSuppressed(cleanup);}throw failure;}
            }
            return call(target,method,args);
        }
    }
    private static Object call(Object target,Method method,Object[] args)throws Throwable{try{return method.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
    public PrintWriter getLogWriter()throws SQLException{return delegate.getLogWriter();} public void setLogWriter(PrintWriter p)throws SQLException{delegate.setLogWriter(p);} public void setLoginTimeout(int s)throws SQLException{delegate.setLoginTimeout(s);} public int getLoginTimeout()throws SQLException{return delegate.getLoginTimeout();} public Logger getParentLogger()throws SQLFeatureNotSupportedException{return delegate.getParentLogger();} public <T>T unwrap(Class<T> t)throws SQLException{return t.isInstance(this)?t.cast(this):delegate.unwrap(t);} public boolean isWrapperFor(Class<?> t)throws SQLException{return t.isInstance(this)||delegate.isWrapperFor(t);}
}

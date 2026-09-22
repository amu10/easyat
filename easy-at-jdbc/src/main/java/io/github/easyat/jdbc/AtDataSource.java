package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

/** JDBC DataSource proxy that writes undo logs before executing supported DML. */
public final class AtDataSource implements DataSource {
    private final String resourceId; private final DataSource delegate; private final SqlUndoLogGenerator generator;
    public AtDataSource(String resourceId, DataSource delegate, AtTransactionManager manager, GlobalLockManager locks) {
        this.resourceId=resourceId;this.delegate=delegate;this.generator=new SqlUndoLogGenerator(resourceId,manager,locks);
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
            if(name.startsWith("set")&&args!=null&&args.length>=2&&args[0] instanceof Integer){params.put((Integer)args[0],args[1]);return call(target,method,args);}
            if(name.equals("clearParameters")){params.clear();return call(target,method,args);}
            if((name.equals("executeUpdate")||name.equals("execute")||name.equals("executeLargeUpdate"))&&AtContext.active()&&!AtContext.undoing()){
                Connection connection=target.getConnection();
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

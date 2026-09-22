package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;
import java.sql.*;
import java.util.*;

/** Generates undo for deliberately conservative, single-row SQL forms parsed by JSqlParser. */
final class SqlUndoLogGenerator {
    private final String resourceId; private final AtTransactionManager manager; private final GlobalLockManager locks;
    SqlUndoLogGenerator(String resourceId,AtTransactionManager manager,GlobalLockManager locks){this.resourceId=resourceId;this.manager=manager;this.locks=locks;}
    Capture capture(Connection c,String sql,Map<Integer,Object> parameters)throws SQLException{
        String xid=AtContext.xid();if(xid==null||AtContext.undoing())return null;Statement statement=parse(sql);
        if(statement instanceof Update)return update(c,xid,(Update)statement,parameters);
        if(statement instanceof Delete)return delete(c,xid,(Delete)statement,parameters);
        if(statement instanceof Insert)return insert(c,xid,(Insert)statement,parameters);
        throw unsupported(sql);
    }
    private Capture update(Connection c,String xid,Update u,Map<Integer,Object> p)throws SQLException{
        reject(u.getWithItemsList()!=null||u.getFromItem()!=null||notEmpty(u.getJoins())||u.getLimit()!=null||notEmpty(u.getOrderByElements()),u.toString());
        Table parsed=u.getTable();String table=tableName(parsed);if(internal(table))return null;List<String> columns=new ArrayList<String>();
        for(UpdateSet set:u.getUpdateSets()){reject(set.getColumns().size()!=1||set.getValues().size()!=1||!(set.getValue(0) instanceof JdbcParameter),u.toString());columns.add(set.getColumn(0).toString());}
        Where where=where(u.getWhere(),u.toString());Object key=require(p,columns.size()+1);assertPrimaryKey(c,parsed,where.column);lock(table,key,xid);
        RowImage before=select(c,table,columns,where.column,key);if(before==null)throw new AtException("UPDATE target does not exist: "+table+"."+key);
        StringBuilder undo=new StringBuilder("UPDATE ").append(table).append(" SET ");Object[] values=new Object[columns.size()+1];
        for(int i=0;i<columns.size();i++){if(i>0)undo.append(',');undo.append(columns.get(i)).append("=?");values[i]=column(before,unquote(columns.get(i)));}undo.append(" WHERE ").append(where.column).append("=?");values[values.length-1]=key;
        UndoRecord r=record(xid,table,where.column,key,undo.toString(),values,before);manager.append(c,r);return new Capture(r,table,where.column,key);
    }
    private Capture delete(Connection c,String xid,Delete d,Map<Integer,Object> p)throws SQLException{
        reject(d.getWithItemsList()!=null||notEmpty(d.getTables())||notEmpty(d.getUsingList())||notEmpty(d.getJoins())||d.getLimit()!=null||notEmpty(d.getOrderByElements()),d.toString());
        Table parsed=d.getTable();String table=tableName(parsed);if(internal(table))return null;Where where=where(d.getWhere(),d.toString());Object key=require(p,1);assertPrimaryKey(c,parsed,where.column);lock(table,key,xid);
        RowImage before=selectAll(c,table,where.column,key);if(before==null)throw new AtException("DELETE target does not exist: "+table+"."+key);
        List<String> cols=new ArrayList<String>(before.getColumns().keySet());StringBuilder undo=new StringBuilder("INSERT INTO ").append(table).append(" (").append(join(cols)).append(") VALUES (");for(int i=0;i<cols.size();i++){if(i>0)undo.append(',');undo.append('?');}undo.append(')');Object[] vals=new Object[cols.size()];for(int i=0;i<cols.size();i++)vals[i]=before.getColumns().get(cols.get(i));
        UndoRecord r=record(xid,table,where.column,key,undo.toString(),vals,before);manager.append(c,r);return new Capture(r,table,where.column,key);
    }
    private Capture insert(Connection c,String xid,Insert in,Map<Integer,Object> p)throws SQLException{
        reject(in.getWithItemsList()!=null||in.getColumns()==null||in.getValues()==null||in.getConflictAction()!=null||notEmpty(in.getDuplicateUpdateSets())||notEmpty(in.getSetUpdateSets()),in.toString());
        Table parsed=in.getTable();String table=tableName(parsed);if(internal(table))return null;List<String> columns=new ArrayList<String>();for(Column column:in.getColumns())columns.add(column.toString());
        List<?> values=in.getValues().getExpressions();reject(columns.size()!=values.size(),in.toString());for(Object value:values)reject(!(value instanceof JdbcParameter),in.toString());
        String pk=findPrimaryKey(c,parsed);int pkIndex=indexOf(columns,pk);if(pkIndex<0)throw new AtException("INSERT must explicitly include primary key "+pk+" for AT undo");Object key=require(p,pkIndex+1);lock(table,key,xid);
        UndoRecord r=record(xid,table,columns.get(pkIndex),key,"DELETE FROM "+table+" WHERE "+columns.get(pkIndex)+"=?",new Object[]{key},null);manager.append(c,r);return new Capture(r,table,columns.get(pkIndex),key);
    }
    void after(Connection c,Capture capture)throws SQLException{if(capture==null)return;RowImage image=selectAll(c,capture.table,capture.pk,capture.key);if(image==null&&capture.record.getBeforeImage()==null)throw new AtException("INSERT did not create expected row: "+capture.table+"."+capture.key);manager.updateUndo(c,capture.record.getXid(),capture.record.getId(),image);}
    void abort(Connection c,Capture capture){if(capture!=null)manager.discardUndo(c,capture.record.getXid(),capture.record.getId());}
    private UndoRecord record(String xid,String table,String pk,Object key,String sql,Object[] values,RowImage before){UndoRecord r=new UndoRecord(UUID.randomUUID().toString(),xid,resourceId,table,pk,key,sql,values);r.setBeforeImage(before);return r;}
    private void lock(String table,Object key,String xid){if(locks!=null)locks.acquire(resourceId,table,String.valueOf(key),xid);}
    private static Statement parse(String sql){try{return CCJSqlParserUtil.parse(sql);}catch(JSQLParserException e){throw new UnsupportedAtSqlException("Unsupported AT SQL: "+sql,e);}}
    private static Where where(Expression expression,String sql){if(!(expression instanceof EqualsTo))throw unsupported(sql);EqualsTo equal=(EqualsTo)expression;if(!(equal.getLeftExpression() instanceof Column)||!(equal.getRightExpression() instanceof JdbcParameter))throw unsupported(sql);return new Where(((Column)equal.getLeftExpression()).toString());}
    private static String findPrimaryKey(Connection c,Table table)throws SQLException{DatabaseMetaData md=c.getMetaData();String schema=identifier(table.getSchemaName()),name=identifier(table.getName());for(String candidate:new String[]{name,name.toUpperCase(Locale.ROOT),name.toLowerCase(Locale.ROOT)}){try(ResultSet rs=md.getPrimaryKeys(null,schema,candidate)){if(rs.next()){String pk=rs.getString("COLUMN_NAME");if(rs.next())throw new AtException("Composite primary keys are not supported: "+table);return pk;}}}throw new AtException("Table must have a primary key: "+table);}
    private static void assertPrimaryKey(Connection c,Table table,String column)throws SQLException{String actual=findPrimaryKey(c,table);if(!unquote(column).equalsIgnoreCase(actual))throw new AtException("WHERE column must be the primary key: "+table+"."+column);}
    private static String tableName(Table table){if(table==null||table.getAlias()!=null)throw new AtException("Aliased or missing table is not supported for AT SQL");return table.getFullyQualifiedName();}
    private static boolean internal(String table){String normalized=unquote(table.substring(table.lastIndexOf('.')+1));return normalized.toLowerCase(Locale.ROOT).startsWith("easy_at_");}
    private static int indexOf(List<String> columns,String name){for(int i=0;i<columns.size();i++)if(unquote(columns.get(i)).equalsIgnoreCase(name))return i;return -1;}
    private static String identifier(String value){return value==null?null:unquote(value);}
    private static String unquote(String value){if(value==null||value.length()<2)return value;char first=value.charAt(0),last=value.charAt(value.length()-1);return (first=='`'&&last=='`')||(first=='"'&&last=='"')||(first=='['&&last==']')?value.substring(1,value.length()-1):value;}
    private static Object require(Map<Integer,Object> p,int i){if(!p.containsKey(i))throw new AtException("Missing JDBC parameter "+i);return p.get(i);}
    private static String join(List<String> values){StringBuilder b=new StringBuilder();for(String v:values){if(b.length()>0)b.append(',');b.append(v);}return b.toString();}
    private static Object column(RowImage image,String name){for(Map.Entry<String,Object> entry:image.getColumns().entrySet())if(entry.getKey().equalsIgnoreCase(name))return entry.getValue();throw new AtException("Before image does not contain column: "+name);}
    private static RowImage select(Connection c,String table,List<String> cols,String pk,Object key)throws SQLException{return query(c,"SELECT "+join(cols)+" FROM "+table+" WHERE "+pk+"=?",key);}
    private static RowImage selectAll(Connection c,String table,String pk,Object key)throws SQLException{return query(c,"SELECT * FROM "+table+" WHERE "+pk+"=?",key);}
    private static RowImage query(Connection c,String sql,Object key)throws SQLException{try(PreparedStatement s=c.prepareStatement(sql)){s.setObject(1,key);try(ResultSet r=s.executeQuery()){return r.next()?image(r):null;}}}
    private static RowImage image(ResultSet r)throws SQLException{ResultSetMetaData md=r.getMetaData();Map<String,Object> values=new LinkedHashMap<String,Object>();for(int i=1;i<=md.getColumnCount();i++)values.put(md.getColumnLabel(i),r.getObject(i));return new RowImage(values);}
    private static boolean notEmpty(Collection<?> value){return value!=null&&!value.isEmpty();}
    private static void reject(boolean condition,String sql){if(condition)throw unsupported(sql);}
    private static UnsupportedAtSqlException unsupported(String sql){return new UnsupportedAtSqlException("Unsupported AT SQL; only single-row INSERT/UPDATE/DELETE by primary key with '?' values are allowed: "+sql);}
    private static final class Where{final String column;Where(String column){this.column=column;}}
    static final class Capture{final UndoRecord record;final String table,pk;final Object key;Capture(UndoRecord record,String table,String pk,Object key){this.record=record;this.table=table;this.pk=pk;this.key=key;}}
}

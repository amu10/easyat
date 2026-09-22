package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;

/** Executes compensating SQL and refuses to overwrite data changed after this AT branch. */
public final class JdbcUndoExecutor implements UndoExecutor {
  private final Map<String,DataSource> resources;
  public JdbcUndoExecutor(Map<String,DataSource> resources){this.resources=new HashMap<String,DataSource>(resources);}

  @Override public void rollback(UndoRecord record)throws Exception{
    DataSource ds=resources.get(record.getResourceId());
    if(ds==null)throw new AtException("Unknown resource: "+record.getResourceId());
    AtContext.beginUndo();
    try(Connection c=ds.getConnection()){
      assertNoDirtyWrite(c,record);
      try(PreparedStatement p=c.prepareStatement(record.getRollbackSql())){
        Object[] values=record.getParameters();
        for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);
        if(p.executeUpdate()!=1)throw new AtException("Undo affected an unexpected number of rows: "+record.getId());
      }
    }finally{AtContext.endUndo();}
  }

  private void assertNoDirtyWrite(Connection c,UndoRecord record)throws SQLException {
    String sql="SELECT * FROM "+record.getTableName()+" WHERE "+record.getPrimaryKeyColumn()+"=?";
    try(PreparedStatement p=c.prepareStatement(sql)){
      p.setObject(1,record.getPrimaryKeyValue());
      try(ResultSet rs=p.executeQuery()){
        boolean exists=rs.next();
        RowImage expected=record.getAfterImage();
        if(expected==null){
          if(exists)throw new DirtyWriteException("Dirty write detected for deleted row: "+record.getTableName()+"/"+record.getPrimaryKeyValue());
          return;
        }
        if(!exists)throw new DirtyWriteException("Dirty write detected: row is missing: "+record.getTableName()+"/"+record.getPrimaryKeyValue());
        ResultSetMetaData meta=rs.getMetaData();
        Map<String,Object> actual=new TreeMap<String,Object>(String.CASE_INSENSITIVE_ORDER);
        for(int i=1;i<=meta.getColumnCount();i++)actual.put(meta.getColumnLabel(i),rs.getObject(i));
        for(Map.Entry<String,Object> entry:expected.getColumns().entrySet()){
          if(!actual.containsKey(entry.getKey())||!sameValue(actual.get(entry.getKey()),entry.getValue()))
            throw new DirtyWriteException("Dirty write detected for "+record.getTableName()+"/"+record.getPrimaryKeyValue()+", column="+entry.getKey());
        }
      }
    }
  }

  private boolean sameValue(Object left,Object right){
    if(left instanceof Number&&right instanceof Number)return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()))==0;
    if(left instanceof byte[]&&right instanceof byte[])return Arrays.equals((byte[])left,(byte[])right);
    return Objects.equals(left,right);
  }
}

package io.github.easyat.core;
import java.io.Serializable;
public final class UndoRecord implements Serializable {
  private static final long serialVersionUID=1L;
  private final String id,xid,resourceId,tableName,primaryKeyColumn; private final Object primaryKeyValue; private final String rollbackSql; private final Object[] parameters; private RowImage beforeImage; private RowImage afterImage; private boolean rolledBack;
  public UndoRecord(String id,String xid,String resourceId,String tableName,String pk,Object pkValue,String sql,Object[] parameters){this.id=id;this.xid=xid;this.resourceId=resourceId;this.tableName=tableName;this.primaryKeyColumn=pk;this.primaryKeyValue=pkValue;this.rollbackSql=sql;this.parameters=parameters==null?new Object[0]:parameters.clone();}
  public String getId(){return id;} public String getXid(){return xid;} public String getResourceId(){return resourceId;} public String getTableName(){return tableName;} public String getPrimaryKeyColumn(){return primaryKeyColumn;} public Object getPrimaryKeyValue(){return primaryKeyValue;} public String getRollbackSql(){return rollbackSql;} public Object[] getParameters(){return parameters.clone();} public boolean isRolledBack(){return rolledBack;} public void markRolledBack(){rolledBack=true;}
  public RowImage getBeforeImage(){return beforeImage;} public void setBeforeImage(RowImage image){beforeImage=image;} public RowImage getAfterImage(){return afterImage;} public void setAfterImage(RowImage image){afterImage=image;}
}

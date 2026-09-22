package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionBoundUndoTest {
  @AfterEach void clear(){AtContext.clear();AtContext.endUndo();}

  @Test void undoLogRollsBackWithTheBusinessConnection() throws Exception {
    JdbcDataSource raw=dataSource("connectionBoundRollback");createSchema(raw);
    JdbcAtRepository repository=new JdbcAtRepository(raw);
    AtTransactionManager manager=new AtTransactionManager(repository,new JdbcUndoExecutor(Collections.<String,DataSource>singletonMap("dataSource",raw)),new JdbcGlobalLockManager(raw,10000),3);
    AtDataSource dataSource=new AtDataSource("dataSource",raw,manager,new JdbcGlobalLockManager(raw,10000));
    AtTransaction tx=manager.begin("local-rollback",10000);
    try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement("UPDATE account SET balance=? WHERE id=?")){
      c.setAutoCommit(false);p.setInt(1,40);p.setLong(2,1L);p.executeUpdate();c.rollback();
    }
    assertEquals(100,balance(raw));
    assertTrue(repository.find(tx.getXid()).get().getUndoRecords().isEmpty(),"undo log must roll back with business DML");
    manager.rollback(tx.getXid());
  }

  @Test void committedLocalTransactionPersistsUndoForGlobalRollback() throws Exception {
    JdbcDataSource raw=dataSource("connectionBoundCommit");createSchema(raw);
    JdbcAtRepository repository=new JdbcAtRepository(raw);
    JdbcGlobalLockManager locks=new JdbcGlobalLockManager(raw,10000);
    AtTransactionManager manager=new AtTransactionManager(repository,new JdbcUndoExecutor(Collections.<String,DataSource>singletonMap("dataSource",raw)),locks,3);
    AtDataSource dataSource=new AtDataSource("dataSource",raw,manager,locks);
    AtTransaction tx=manager.begin("local-commit",10000);
    try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement("UPDATE account SET balance=? WHERE id=?")){
      c.setAutoCommit(false);p.setInt(1,40);p.setLong(2,1L);p.executeUpdate();c.commit();
    }
    assertEquals(1,repository.find(tx.getXid()).get().getUndoRecords().size());
    manager.rollback(tx.getXid());
    assertEquals(100,balance(raw));
  }

  private JdbcDataSource dataSource(String name){JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+name+";MODE=MySQL;DB_CLOSE_DELAY=-1");return ds;}
  private void createSchema(DataSource ds)throws SQLException {try(Connection c=ds.getConnection();Statement s=c.createStatement()){
    s.execute("CREATE TABLE account (id BIGINT PRIMARY KEY, balance INT)");s.execute("INSERT INTO account(id,balance) VALUES(1,100)");
    s.execute("CREATE TABLE easy_at_global (xid VARCHAR(128) PRIMARY KEY,name VARCHAR(255),status VARCHAR(32),timeout_at TIMESTAMP,retry_count INT,next_retry_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP)");
    s.execute("CREATE TABLE easy_at_undo_log (undo_id VARCHAR(128) PRIMARY KEY,xid VARCHAR(128),resource_id VARCHAR(255),table_name VARCHAR(255),pk_name VARCHAR(255),pk_value BLOB,rollback_sql VARCHAR(1000),rollback_params BLOB,before_image BLOB,after_image BLOB,status VARCHAR(32),created_at TIMESTAMP,updated_at TIMESTAMP)");
    s.execute("CREATE TABLE easy_at_lock (resource_id VARCHAR(255),table_name VARCHAR(255),pk_value VARCHAR(512),xid VARCHAR(128),lease_until TIMESTAMP,created_at TIMESTAMP,PRIMARY KEY(resource_id,table_name,pk_value))");
  }}
  private int balance(DataSource ds)throws SQLException {try(Connection c=ds.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT balance FROM account WHERE id=1")){r.next();return r.getInt(1);}}
}

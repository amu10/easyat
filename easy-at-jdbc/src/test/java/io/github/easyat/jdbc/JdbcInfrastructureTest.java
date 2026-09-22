package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class JdbcInfrastructureTest {
  @Test void persistsTransactionsAndEnforcesRowLocks() throws Exception {
    JdbcDataSource ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:infrastructure;MODE=MySQL;DB_CLOSE_DELAY=-1");
    try(Connection c=ds.getConnection();Statement s=c.createStatement()){
      s.execute("CREATE TABLE easy_at_global (xid VARCHAR(128) PRIMARY KEY,name VARCHAR(255),status VARCHAR(32),timeout_at TIMESTAMP,retry_count INT,next_retry_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP)");
      s.execute("CREATE TABLE easy_at_undo_log (undo_id VARCHAR(128) PRIMARY KEY,xid VARCHAR(128),resource_id VARCHAR(255),table_name VARCHAR(255),pk_name VARCHAR(255),pk_value BLOB,rollback_sql VARCHAR(1000),rollback_params BLOB,before_image BLOB,after_image BLOB,status VARCHAR(32),created_at TIMESTAMP,updated_at TIMESTAMP)");
      s.execute("CREATE TABLE easy_at_lock (resource_id VARCHAR(255),table_name VARCHAR(255),pk_value VARCHAR(512),xid VARCHAR(128),lease_until TIMESTAMP,created_at TIMESTAMP,PRIMARY KEY(resource_id,table_name,pk_value))");
    }
    JdbcAtRepository repository=new JdbcAtRepository(ds);
    AtTransaction tx=new AtTransaction("x-1","order",System.currentTimeMillis(),System.currentTimeMillis()+1000);
    repository.create(tx);tx.setStatus(AtStatus.COMMITTING);repository.save(tx);
    assertEquals(AtStatus.COMMITTING,repository.find("x-1").get().getStatus());
    JdbcGlobalLockManager locks=new JdbcGlobalLockManager(ds,10000);
    locks.acquire("db","account","1","x-1");
    assertThrows(AtException.class,()->locks.acquire("db","account","1","x-2"));
    locks.releaseByXid("x-1");locks.acquire("db","account","1","x-2");
  }
}

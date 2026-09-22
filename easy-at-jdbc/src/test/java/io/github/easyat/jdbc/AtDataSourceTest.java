package io.github.easyat.jdbc;

import io.github.easyat.core.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AtDataSourceTest {
    @AfterEach void clear(){AtContext.clear();AtContext.endUndo();}
    @Test void capturesImagesAndRollsBackAnUpdate() throws Exception {
        JdbcDataSource raw=new JdbcDataSource();raw.setURL("jdbc:h2:mem:at;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(Connection c=raw.getConnection();Statement s=c.createStatement()){s.execute("CREATE TABLE account (id BIGINT PRIMARY KEY, balance INT)");s.execute("INSERT INTO account(id,balance) VALUES(1,100)");}
        MemoryRepo repo=new MemoryRepo();MemoryLocks locks=new MemoryLocks();Map<String,DataSource> sources=Collections.<String,DataSource>singletonMap("dataSource",raw);
        AtTransactionManager manager=new AtTransactionManager(repo,new JdbcUndoExecutor(sources),locks,3);
        AtDataSource dataSource=new AtDataSource("dataSource",raw,manager,locks);
        AtTransaction tx=manager.begin("decrease",10000);
        try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("UPDATE account SET balance=? WHERE id=?")){s.setInt(1,40);s.setLong(2,1L);assertEquals(1,s.executeUpdate());}
        AtTransaction persisted=repo.find(tx.getXid()).get();assertEquals(1,persisted.getUndoRecords().size());assertEquals(100,persisted.getUndoRecords().get(0).getBeforeImage().getColumns().get("BALANCE"));assertEquals(40,persisted.getUndoRecords().get(0).getAfterImage().getColumns().get("BALANCE"));assertTrue(locks.locked);
        manager.rollback(tx.getXid());
        try(Connection c=raw.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT balance FROM account WHERE id=1")){r.next();assertEquals(100,r.getInt(1));}
        assertFalse(locks.locked);
    }
    @Test void rejectsRollbackWhenTheAfterImageWasChanged() throws Exception {
        JdbcDataSource raw=new JdbcDataSource();raw.setURL("jdbc:h2:mem:dirty;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(Connection c=raw.getConnection();Statement s=c.createStatement()){s.execute("CREATE TABLE account (id BIGINT PRIMARY KEY, balance INT)");s.execute("INSERT INTO account(id,balance) VALUES(1,100)");}
        MemoryRepo repo=new MemoryRepo();MemoryLocks locks=new MemoryLocks();Map<String,DataSource> sources=Collections.<String,DataSource>singletonMap("dataSource",raw);
        AtTransactionManager manager=new AtTransactionManager(repo,new JdbcUndoExecutor(sources),locks,3);
        AtDataSource dataSource=new AtDataSource("dataSource",raw,manager,locks);
        AtTransaction tx=manager.begin("dirty",10000);
        try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("UPDATE account SET balance=? WHERE id=?")){s.setInt(1,40);s.setLong(2,1L);s.executeUpdate();}
        try(Connection c=raw.getConnection();Statement s=c.createStatement()){s.executeUpdate("UPDATE account SET balance=30 WHERE id=1");}
        assertThrows(AtException.class,()->manager.rollback(tx.getXid()));
        try(Connection c=raw.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT balance FROM account WHERE id=1")){r.next();assertEquals(30,r.getInt(1));}
    }
    @Test void usesDatabaseMetadataForInsertPrimaryKeyAndRollsBack() throws Exception {
        JdbcDataSource raw=new JdbcDataSource();raw.setURL("jdbc:h2:mem:insertPk;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(Connection c=raw.getConnection();Statement s=c.createStatement()){s.execute("CREATE TABLE account (account_key BIGINT PRIMARY KEY, balance INT)");}
        MemoryRepo repo=new MemoryRepo();MemoryLocks locks=new MemoryLocks();AtTransactionManager manager=new AtTransactionManager(repo,new JdbcUndoExecutor(Collections.<String,DataSource>singletonMap("dataSource",raw)),locks,3);
        AtDataSource dataSource=new AtDataSource("dataSource",raw,manager,locks);AtTransaction tx=manager.begin("insert",10000);
        try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("INSERT INTO account (balance, account_key) VALUES (?, ?)")){s.setInt(1,25);s.setLong(2,7L);assertEquals(1,s.executeUpdate());}
        manager.rollback(tx.getXid());
        try(Connection c=raw.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM account")){r.next();assertEquals(0,r.getInt(1));}
    }
    @Test void rejectsUnsafePredicateBeforeExecutingDml() throws Exception {
        JdbcDataSource raw=new JdbcDataSource();raw.setURL("jdbc:h2:mem:unsafeSql;MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(Connection c=raw.getConnection();Statement s=c.createStatement()){s.execute("CREATE TABLE account (id BIGINT PRIMARY KEY, balance INT)");s.execute("INSERT INTO account VALUES(1,100)");}
        MemoryRepo repo=new MemoryRepo();MemoryLocks locks=new MemoryLocks();AtTransactionManager manager=new AtTransactionManager(repo,r->{},locks,3);AtDataSource dataSource=new AtDataSource("dataSource",raw,manager,locks);manager.begin("unsafe",10000);
        try(Connection c=dataSource.getConnection();PreparedStatement s=c.prepareStatement("UPDATE account SET balance=? WHERE id=? OR balance=?")){s.setInt(1,0);s.setLong(2,1L);s.setInt(3,100);assertThrows(UnsupportedAtSqlException.class,s::executeUpdate);}
        try(Connection c=raw.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT balance FROM account WHERE id=1")){r.next();assertEquals(100,r.getInt(1));}
    }
    static final class MemoryRepo implements AtRepository {final Map<String,AtTransaction> data=new HashMap<String,AtTransaction>();public void create(AtTransaction t){data.put(t.getXid(),t);}public Optional<AtTransaction> find(String x){return Optional.ofNullable(data.get(x));}public void save(AtTransaction t){data.put(t.getXid(),t);}public List<AtTransaction> recoverable(long n,int l){return Collections.emptyList();}}
    static final class MemoryLocks implements GlobalLockManager {boolean locked;public void acquire(String r,String t,String k,String x){locked=true;}public void releaseByXid(String x){locked=false;}}
}

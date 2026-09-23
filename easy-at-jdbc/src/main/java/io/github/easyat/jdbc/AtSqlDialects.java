package io.github.easyat.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

/** Resolves the active dialect from a DataSource's JDBC metadata. */
public final class AtSqlDialects {
    private AtSqlDialects(){}
    public static AtSqlDialect detect(DataSource dataSource){
        try(Connection c=dataSource.getConnection()){
            DatabaseMetaData md=c.getMetaData();
            String product=md==null?null:md.getDatabaseProductName();
            return detect(product);
        }catch(SQLException e){return new GenericAtSqlDialect();}
    }
    public static AtSqlDialect detect(String productName){
        if(productName==null)return new GenericAtSqlDialect();
        String p=productName.toLowerCase(java.util.Locale.ROOT);
        if(p.contains("mysql"))return new MysqlAtSqlDialect();
        if(p.contains("postgre"))return new PostgresAtSqlDialect();
        return new GenericAtSqlDialect();
    }
}

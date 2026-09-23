package io.github.easyat.jdbc;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** PostgreSQL 14+ dialect: double-quote quoting and the common reserved-word set. */
public final class PostgresAtSqlDialect implements AtSqlDialect {
    private static final Set<String> RESERVED=new HashSet<String>(Arrays.asList(
        "SELECT","FROM","WHERE","UPDATE","DELETE","INSERT","INTO","SET","VALUES","ORDER","GROUP","BY","AND","OR",
        "TABLE","INDEX","KEY","PRIMARY","FOREIGN","REFERENCES","NOT","NULL","DEFAULT","UNIQUE","JOIN","ON","AS",
        "LIMIT","OFFSET","DUAL","READ","WRITE","LOCK","CASE","WHEN","THEN","ELSE","END","CAST","USER","SHOW"));
    @Override public String productName(){return "PostgreSQL";}
    @Override public String quoteIdentifier(String id){
        if(id==null)return null;
        if(id.length()>=2&&id.charAt(0)=='"'&&id.charAt(id.length()-1)=='"')return id;
        return "\""+id.replace("\"","\"\"")+"\"";
    }
    @Override public boolean isReservedWord(String id){return id!=null&&RESERVED.contains(id.toUpperCase(Locale.ROOT));}
}

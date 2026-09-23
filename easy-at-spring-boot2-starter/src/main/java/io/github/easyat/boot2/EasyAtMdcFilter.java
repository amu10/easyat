package io.github.easyat.boot2;
import io.github.easyat.core.AtContext;
import org.slf4j.MDC;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
/** Copies the active AT context into the logging MDC for trace correlation. */
public final class EasyAtMdcFilter implements Filter {
    public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain)throws IOException,ServletException{
        try{
            String xid=AtContext.xid();
            if(xid!=null)MDC.put("easyAtXid",xid);
            chain.doFilter(request,response);
        }finally{MDC.remove("easyAtXid");MDC.remove("easyAtResourceId");}
    }
}

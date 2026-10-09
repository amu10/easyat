package io.github.easyat.boot3;

import io.github.easyat.core.AtContext;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.slf4j.MDC;

/** Copies the active AT context into the logging MDC for trace correlation. */
public final class EasyAtMdcFilter implements Filter {
    /**
     * 把当前活跃 AT 上下文的 XID 写入日志 MDC（key 为 {@code easyAtXid}），便于跨服务链路追踪； 请求处理结束后（finally 中）清理
     * MDC，避免线程池复用导致的上下文污染。
     *
     * @param request 入站请求
     * @param response 出站响应
     * @param chain 过滤器链
     * @throws IOException 透传
     * @throws ServletException 透传
     */
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        try {
            String xid = AtContext.xid();
            if (xid != null) MDC.put("easyAtXid", xid);
            chain.doFilter(request, response);
        } finally {
            MDC.remove("easyAtXid");
            MDC.remove("easyAtResourceId");
        }
    }
}

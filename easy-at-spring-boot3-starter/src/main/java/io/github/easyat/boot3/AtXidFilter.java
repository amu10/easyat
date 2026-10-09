package io.github.easyat.boot3;

import io.github.easyat.core.*;
import io.github.easyat.spring.EasyAtTransportSecurity;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;

/**
 * HTTP 过滤器：在跨服务调用场景下，通过 HTTP 请求头把 AT 全局事务 XID 传播到本应用。
 *
 * <p>当入站请求携带 XID 请求头、且本地当前线程尚未绑定任何事务时，本过滤器会调用
 * {@link AtTransactionManager#join(String)} 把该 XID 绑定到当前线程的 AT 上下文，
 * 使本次请求内的数据库操作自动加入同一个全局事务；请求处理完毕后（无论成功还是异常）
 * 统一清理，避免线程池复用导致的 XID「串号」。
 *
 * <p>若注入了 {@link EasyAtTransportSecurity} 且其 HMAC 签名已配置，则对所有「需要绑定 XID」
 * 的入站请求做签名校验，校验不通过直接返回 403，防止伪造 XID 注入引发的越权事务操作。
 */
public final class AtXidFilter implements Filter {
    /** AT 事务管理器：负责把上游传来的 XID 加入（join）或清理当前线程的事务上下文。 */
    private final AtTransactionManager manager;
    /** 跨服务传输安全组件（HMAC 签名校验），可为 null——未配置签名时不鉴权。 */
    private final EasyAtTransportSecurity security;

    /** 仅注入事务管理器，不启用签名鉴权（security 置为 null）。 */
    public AtXidFilter(AtTransactionManager m) {
        this(m, null);
    }

    /** 同时注入事务管理器与传输安全组件；security 为 null 表示不做签名校验。 */
    public AtXidFilter(AtTransactionManager m, EasyAtTransportSecurity security) {
        manager = m;
        this.security = security;
    }

    /**
     * 每个 HTTP 请求的处理入口：解析 XID 头、按需做签名鉴权、绑定/清理事务上下文。
     *
     * @param request  入站请求（强转为 {@link HttpServletRequest} 以读取 XID 头）
     * @param response 出站响应（鉴权失败时写 403）
     * @param chain    过滤器链，放行后续处理
     * @throws IOException      读写异常时透传
     * @throws ServletException 过滤器链处理异常时透传
     */
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        // 从约定请求头取出上游传来的全局事务 XID。
        String xid = req.getHeader(AtTransportHeaders.XID);
        // 仅在「携带 XID 且本地尚未绑定事务」时才接管，避免覆盖本就处于事务中的请求。
        boolean bound = xid != null && !AtContext.active();
        // 配置了 HMAC 才需要做鉴权：把全部请求头取出交给安全组件验签。
        if (bound && security != null && security.signer().isConfigured()) {
            Map<String, String> headers = new HashMap<String, String>();
            Enumeration<String> names = req.getHeaderNames();
            while (names != null && names.hasMoreElements()) {
                String n = names.nextElement();
                headers.put(n, req.getHeader(n));
            }
            // 签名不合法，拒绝请求并结束，不再向下传递。
            if (!security.authorized(headers)) {
                ((HttpServletResponse) response)
                        .sendError(HttpServletResponse.SC_FORBIDDEN, "invalid easyAt signature");
                return;
            }
        }
        try {
            // 绑定上游 XID 到当前线程，下游 SQL 自动参与同一全局事务。
            if (bound) manager.join(xid);
            chain.doFilter(request, response);
        } finally {
            // 无论成功失败都清理，防止线程池复用导致 XID 串号。
            if (bound) AtContext.clear();
        }
    }
}

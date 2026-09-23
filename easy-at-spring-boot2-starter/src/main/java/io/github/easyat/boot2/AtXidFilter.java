package io.github.easyat.boot2;

import io.github.easyat.core.*;
import io.github.easyat.spring.EasyAtTransportSecurity;
import java.io.IOException;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;

public final class AtXidFilter implements Filter {
    private final AtTransactionManager manager;
    private final EasyAtTransportSecurity security;

    public AtXidFilter(AtTransactionManager m) {
        this(m, null);
    }

    public AtXidFilter(AtTransactionManager m, EasyAtTransportSecurity security) {
        manager = m;
        this.security = security;
    }

    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        String xid = req.getHeader(AtTransportHeaders.XID);
        boolean bound = xid != null && !AtContext.active();
        if (bound && security != null && security.signer().isConfigured()) {
            Map<String, String> headers = new HashMap<String, String>();
            Enumeration<String> names = req.getHeaderNames();
            while (names != null && names.hasMoreElements()) {
                String n = names.nextElement();
                headers.put(n, req.getHeader(n));
            }
            if (!security.authorized(headers)) {
                ((HttpServletResponse) response)
                        .sendError(HttpServletResponse.SC_FORBIDDEN, "invalid easyAt signature");
                return;
            }
        }
        try {
            if (bound) manager.join(xid);
            chain.doFilter(request, response);
        } finally {
            if (bound) AtContext.clear();
        }
    }
}

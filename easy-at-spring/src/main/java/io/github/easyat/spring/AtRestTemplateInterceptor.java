package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Propagates the AT context on outgoing HTTP calls (XID + signed deadline/source). */
public final class AtRestTemplateInterceptor implements ClientHttpRequestInterceptor {
    public static final String XID_HEADER = "X-EasyAt-Xid";
    private final HmacSigner signer;
    private final String appName;

    public AtRestTemplateInterceptor() {
        this(null, "");
    }

    public AtRestTemplateInterceptor(HmacSigner signer, String appName) {
        this.signer = signer;
        this.appName = appName;
    }

    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String xid = AtContext.xid();
        if (xid != null) {
            request.getHeaders().set(XID_HEADER, xid);
            long deadline = System.currentTimeMillis() + 30000L;
            request.getHeaders().set(AtTransportHeaders.DEADLINE, Long.toString(deadline));
            request.getHeaders().set(AtTransportHeaders.SOURCE, appName);
            if (signer != null && signer.isConfigured())
                request.getHeaders()
                        .set(AtTransportHeaders.SIGNATURE, signer.sign(xid, deadline, appName));
        }
        return execution.execute(request, body);
    }
}

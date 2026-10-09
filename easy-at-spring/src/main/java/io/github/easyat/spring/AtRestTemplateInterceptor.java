package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Propagates the AT context on outgoing HTTP calls (XID + signed deadline/source). */
public final class AtRestTemplateInterceptor implements ClientHttpRequestInterceptor {
    /** 出站 HTTP 请求携带全局事务 XID 的自定义头名。 */
    public static final String XID_HEADER = "X-EasyAt-Xid";

    /** 用于给请求头签名的 HMAC 签名器（未配置时跳过签名）。 */
    private final HmacSigner signer;

    /** 本服务名，写入 SOURCE 头以便对端识别调用来源。 */
    private final String appName;

    /** 构造一个不带签名的拦截器（dev 模式）。 */
    public AtRestTemplateInterceptor() {
        this(null, "");
    }

    /**
     * 构造带签名能力的拦截器。
     *
     * @param signer HMAC 签名器，可为 null
     * @param appName 本服务名，用于 SOURCE 头
     */
    public AtRestTemplateInterceptor(HmacSigner signer, String appName) {
        this.signer = signer;
        this.appName = appName;
    }

    /**
     * 在出站请求中传播 AT 上下文：若当前线程存在 XID，则在请求头写入 XID、 截止时间（30s 内有效）、来源服务名，并在配置了签名器时附带 HMAC 签名。
     *
     * @param request 待发送的 HTTP 请求
     * @param body 请求体
     * @param execution 请求执行链
     * @return 服务端响应
     * @throws IOException 底层 IO 异常
     */
    @Override
    public ClientHttpResponse intercept(
            HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String xid = AtContext.xid();
        if (xid != null) {
            request.getHeaders().set(XID_HEADER, xid);
            // 截止时间 = 当前时间 + 30s，对端据此判断该上下文是否已过期
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

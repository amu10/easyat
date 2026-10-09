package io.github.easyat.spring;

import feign.RequestInterceptor;
import feign.RequestTemplate;
import io.github.easyat.core.*;

/** OpenFeign propagation of the AT context: XID plus signed deadline/source headers. */
public final class EasyAtFeignInterceptor implements RequestInterceptor {
    /** 用于给请求头签名的 HMAC 签名器（未配置时跳过签名）。 */
    private final HmacSigner signer;

    /** 本服务名，写入 SOURCE 头以便对端识别调用来源。 */
    private final String appName;

    /**
     * 构造 Feign 拦截器。
     *
     * @param signer HMAC 签名器，可为 null
     * @param appName 本服务名，用于 SOURCE 头
     */
    public EasyAtFeignInterceptor(HmacSigner signer, String appName) {
        this.signer = signer;
        this.appName = appName;
    }

    /**
     * 在 Feign 出站请求中传播 AT 上下文：若当前线程存在 XID，则写入 XID、 截止时间（30s）、来源服务名，并在配置签名器时附带 HMAC 签名。
     *
     * @param template 待发送的 Feign 请求模板
     */
    @Override
    public void apply(RequestTemplate template) {
        String xid = AtContext.xid();
        if (xid == null) return;
        template.header(AtTransportHeaders.XID, xid);
        // 截止时间 = 当前时间 + 30s，对端据此判断该上下文是否已过期
        long deadline = System.currentTimeMillis() + 30000L;
        template.header(AtTransportHeaders.DEADLINE, Long.toString(deadline));
        template.header(AtTransportHeaders.SOURCE, appName);
        if (signer != null && signer.isConfigured())
            template.header(AtTransportHeaders.SIGNATURE, signer.sign(xid, deadline, appName));
    }
}

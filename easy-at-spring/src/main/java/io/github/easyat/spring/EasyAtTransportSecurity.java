package io.github.easyat.spring;

import io.github.easyat.core.*;
import java.util.Map;

/**
 * Verifies cross-service headers (HMAC signature, deadline, source). Works on a plain header map so
 * it is usable from both Boot 2 (javax) and Boot 3 (jakarta) controllers. Allows traffic when no
 * secret is configured (dev mode); in {@code production=true} a secret is mandatory and
 * unauthenticated traffic is rejected by the caller.
 */
public final class EasyAtTransportSecurity {
    /** HMAC 签名器，未配置密钥时视为 dev 模式。 */
    private final HmacSigner signer;

    /** 是否为生产模式；生产模式要求必须配置密钥，否则拒绝未鉴权流量。 */
    private final boolean production;

    /**
     * 构造传输安全校验器。
     *
     * @param signer HMAC 签名器
     * @param production 是否生产模式
     */
    public EasyAtTransportSecurity(HmacSigner signer, boolean production) {
        this.signer = signer;
        this.production = production;
    }

    /**
     * 校验一组跨服务请求头是否合法（HMAC 签名 + 截止时间 + 来源）。 未配置密钥时：dev 模式放行、生产模式拒绝。校验失败（缺头、签名不匹配、截止时间非法）返回 false。
     *
     * @param headers 请求头映射
     * @return 是否通过鉴权
     */
    public boolean authorized(Map<String, String> headers) {
        if (!signer.isConfigured()) {
            // dev mode without a shared secret: allow, but never trust it for production decisions
            return !production;
        }
        String xid = headers.get(AtTransportHeaders.XID);
        String deadline = headers.get(AtTransportHeaders.DEADLINE);
        String source = headers.get(AtTransportHeaders.SOURCE);
        String signature = headers.get(AtTransportHeaders.SIGNATURE);
        if (xid == null || deadline == null || source == null || signature == null) return false;
        try {
            return signer.verify(xid, Long.parseLong(deadline), source, signature);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 暴露内部签名器，供调用方构造带签名的请求头。 */
    public HmacSigner signer() {
        return signer;
    }
}

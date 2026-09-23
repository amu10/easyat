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
    private final HmacSigner signer;
    private final boolean production;

    public EasyAtTransportSecurity(HmacSigner signer, boolean production) {
        this.signer = signer;
        this.production = production;
    }

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

    public HmacSigner signer() {
        return signer;
    }
}

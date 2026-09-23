package io.github.easyat.spring;

import io.github.easyat.core.*;
import feign.RequestInterceptor;
import feign.RequestTemplate;

/** OpenFeign propagation of the AT context: XID plus signed deadline/source headers. */
public final class EasyAtFeignInterceptor implements RequestInterceptor {
    private final HmacSigner signer; private final String appName;
    public EasyAtFeignInterceptor(HmacSigner signer,String appName){this.signer=signer;this.appName=appName;}
    @Override public void apply(RequestTemplate template){
        String xid=AtContext.xid();
        if(xid==null)return;
        template.header(AtTransportHeaders.XID,xid);
        long deadline=System.currentTimeMillis()+30000L;
        template.header(AtTransportHeaders.DEADLINE,Long.toString(deadline));
        template.header(AtTransportHeaders.SOURCE,appName);
        if(signer!=null&&signer.isConfigured())template.header(AtTransportHeaders.SIGNATURE,signer.sign(xid,deadline,appName));
    }
}

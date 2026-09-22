package io.github.easyat.spring;

import io.github.easyat.core.AtContext;
import java.io.IOException;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

public final class AtRestTemplateInterceptor implements ClientHttpRequestInterceptor {
    public static final String XID_HEADER="X-EasyAt-Xid";
    @Override public ClientHttpResponse intercept(HttpRequest request,byte[] body,ClientHttpRequestExecution execution)throws IOException{
        String xid=AtContext.xid(); if(xid!=null)request.getHeaders().set(XID_HEADER,xid); return execution.execute(request,body);
    }
}

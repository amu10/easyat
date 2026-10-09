package io.github.easyat.boot3;

import io.github.easyat.spring.AtRestTemplateInterceptor;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.web.client.RestTemplate;

/** Auto-registers the AT propagation interceptor on every {@link RestTemplate} built by the app. */
public final class EasyAtRestTemplateCustomizer implements RestTemplateCustomizer {
    /** 跨服务 XID 传播拦截器：统一挂到每个 {@link RestTemplate} 上。 */
    private final AtRestTemplateInterceptor interceptor;

    /** 注入 XID 传播拦截器。 */
    public EasyAtRestTemplateCustomizer(AtRestTemplateInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    /**
     * {@inheritDoc}：把 XID 传播拦截器加入 RestTemplate 的拦截器链，
     * 使每次出站请求自动携带全局事务 XID 头与 HMAC 签名。
     *
     * @param restTemplate 被定制的 RestTemplate
     */
    @Override
    public void customize(RestTemplate restTemplate) {
        restTemplate.getInterceptors().add(interceptor);
    }
}

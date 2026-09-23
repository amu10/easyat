package io.github.easyat.boot2;

import io.github.easyat.spring.AtRestTemplateInterceptor;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.web.client.RestTemplate;

/** Auto-registers the AT propagation interceptor on every {@link RestTemplate} built by the app. */
public final class EasyAtRestTemplateCustomizer implements RestTemplateCustomizer {
    private final AtRestTemplateInterceptor interceptor;

    public EasyAtRestTemplateCustomizer(AtRestTemplateInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void customize(RestTemplate restTemplate) {
        restTemplate.getInterceptors().add(interceptor);
    }
}

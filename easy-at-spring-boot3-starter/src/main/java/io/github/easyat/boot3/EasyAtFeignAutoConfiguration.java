package io.github.easyat.boot3;

import io.github.easyat.core.HmacSigner;
import io.github.easyat.spring.EasyAtFeignInterceptor;
import io.github.easyat.spring.EasyAtProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after=EasyAtAutoConfiguration.class)
@ConditionalOnClass(name="feign.RequestInterceptor")
public class EasyAtFeignAutoConfiguration {
    @Bean @ConditionalOnMissingBean EasyAtFeignInterceptor easyAtFeignInterceptor(HmacSigner signer,EasyAtProperties props){return new EasyAtFeignInterceptor(signer,props.getApplicationName());}
}

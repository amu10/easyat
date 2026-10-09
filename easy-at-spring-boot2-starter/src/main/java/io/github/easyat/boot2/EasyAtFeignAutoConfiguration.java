package io.github.easyat.boot2;

import io.github.easyat.core.HmacSigner;
import io.github.easyat.spring.EasyAtFeignInterceptor;
import io.github.easyat.spring.EasyAtProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Feign 自动配置：仅当类路径存在 {@code feign.RequestInterceptor} 时生效， 为 Feign 客户端注册 XID 传播拦截器，使 Feign
 * 出站调用自动带上全局事务 XID 与 HMAC 签名。
 *
 * <p>{@code proxyBeanMethods = false} 表明该配置类不代理 @Bean 方法（本类也没有 @Bean 互相调用）， 仅此一项纯属启动期优化，不影响功能。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "feign.RequestInterceptor")
public class EasyAtFeignAutoConfiguration {
    /**
     * Feign 拦截器：在出站请求中注入 XID 头并完成 HMAC 签名，实现跨服务事务上下文传播。
     *
     * @param signer HMAC 签名器
     * @param props 配置属性（取应用名用于标识来源）
     * @return Feign 请求拦截器
     */
    @Bean
    @ConditionalOnMissingBean
    EasyAtFeignInterceptor easyAtFeignInterceptor(HmacSigner signer, EasyAtProperties props) {
        return new EasyAtFeignInterceptor(signer, props.getApplicationName());
    }
}

package io.github.easyat.boot2;

import io.github.easyat.core.*;
import io.github.easyat.spring.EasyAtProperties;
import io.github.easyat.storage.redis.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(
        name = {
            "redis.clients.jedis.JedisPool",
            "io.github.easyat.storage.redis.RedisAtRepository"
        })
public class EasyAtRedisAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression(
            "'${easy-at.storage.type:file}' == 'redis' || '${easy-at.lock.type:file}' == 'redis'")
    JedisPool easyAtJedisPool(EasyAtProperties props) {
        EasyAtProperties.Redis r = props.getRedis();
        JedisPoolConfig c = new JedisPoolConfig();
        c.setMaxTotal(r.getMaxTotal());
        return new JedisPool(
                c,
                r.getHost(),
                r.getPort(),
                r.getTimeoutMillis(),
                r.getPassword(),
                r.getDatabase());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easy-at.storage", name = "type", havingValue = "redis")
    AtRepository redisAtRepository(JedisPool pool, UndoDataCodec codec) {
        return new RedisAtRepository(pool, codec, "easy-at");
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easy-at.lock", name = "type", havingValue = "redis")
    GlobalLockManager redisAtLockManager(JedisPool pool, EasyAtProperties props) {
        return new RedisGlobalLockManager(
                pool,
                props.getLock().getLease().toMillis(),
                props.getLock().getWaitTimeout().toMillis());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easy-at.storage", name = "type", havingValue = "redis")
    BranchRepository redisBranchRepository(JedisPool pool) {
        return new RedisBranchRepository(pool);
    }
}

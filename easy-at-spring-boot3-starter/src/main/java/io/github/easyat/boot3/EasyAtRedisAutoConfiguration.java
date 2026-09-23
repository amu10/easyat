package io.github.easyat.boot3;

import io.github.easyat.core.*;
import io.github.easyat.spring.EasyAtProperties;
import io.github.easyat.storage.redis.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

@AutoConfiguration(after=EasyAtAutoConfiguration.class)
@ConditionalOnClass(name={"redis.clients.jedis.JedisPool","io.github.easyat.storage.redis.RedisAtRepository"})
@ConditionalOnProperty(prefix="easy-at.storage",name="type",havingValue="redis")
public class EasyAtRedisAutoConfiguration {
    @Bean @ConditionalOnMissingBean JedisPool easyAtJedisPool(EasyAtProperties props){EasyAtProperties.Storage.Redis r=props.getStorage().getRedis();JedisPoolConfig c=new JedisPoolConfig();c.setMaxTotal(r.getMaxTotal());return new JedisPool(c,r.getHost(),r.getPort(),r.getTimeoutMillis(),r.getPassword(),r.getDatabase());}
    @Bean @ConditionalOnMissingBean AtRepository redisAtRepository(JedisPool pool,UndoDataCodec codec){return new RedisAtRepository(pool,codec,"easy-at");}
    @Bean @ConditionalOnMissingBean GlobalLockManager redisAtLockManager(JedisPool pool,EasyAtProperties props){return new RedisGlobalLockManager(pool,props.getLock().getLease().toMillis(),props.getLock().getWaitTimeout().toMillis());}
    @Bean @ConditionalOnMissingBean BranchRepository redisBranchRepository(JedisPool pool){return new RedisBranchRepository(pool);}
}

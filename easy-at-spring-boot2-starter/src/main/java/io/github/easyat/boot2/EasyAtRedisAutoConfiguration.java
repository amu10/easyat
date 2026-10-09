package io.github.easyat.boot2;

import io.github.easyat.core.*;
import io.github.easyat.spring.EasyAtProperties;
import io.github.easyat.storage.redis.*;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
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

    /** storage=redis（全 Redis）或 hybrid（全局状态在 Redis、undo 在业务库）都需要连接池。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression(
            "'${easy-at.storage.type:file}' == 'redis' || '${easy-at.storage.type:file}' == 'hybrid'"
                    + " || '${easy-at.lock.type:file}' == 'redis'")
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
    @ConditionalOnExpression(
            "'${easy-at.storage.type:file}' == 'redis' || '${easy-at.storage.type:file}' == 'hybrid'")
    AtRepository redisAtRepository(JedisPool pool, UndoDataCodec codec, EasyAtProperties props) {
        return new RedisAtRepository(
                pool, codec, "easy-at", Math.max(1L, props.getRedis().getTtl().getSeconds()));
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
    @ConditionalOnExpression(
            "'${easy-at.storage.type:file}' == 'redis' || '${easy-at.storage.type:file}' == 'hybrid'")
    BranchRepository redisBranchRepository(JedisPool pool) {
        return new RedisBranchRepository(pool);
    }

    /**
     * Redis 侧的清理调度。混合存储模式下被删掉的 xid 会通过 {@code UndoRepository#deleteByXid}
     * 级联清理业务库的 {@code easy_at_undo_log}，两边合起来才是一次完整回收。
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(CleanupScheduler.class)
    @ConditionalOnExpression(
            "'${easy-at.storage.type:file}' == 'redis' || '${easy-at.storage.type:file}' == 'hybrid'")
    @ConditionalOnProperty(prefix = "easy-at.cleanup", name = "enabled", havingValue = "true")
    CleanupScheduler easyAtRedisCleanupScheduler(
            AtRepository repository,
            JedisPool pool,
            EasyAtProperties props,
            ObjectProvider<UndoRepository> undoRepository) {
        RedisCleanup cleaner =
                repository instanceof RedisAtRepository
                        ? ((RedisAtRepository) repository).cleanup()
                        : new RedisCleanup(
                                pool,
                                "easy-at",
                                Math.max(1L, props.getRedis().getTtl().getSeconds()));
        EasyAtProperties.Cleanup cleanup = props.getCleanup();
        UndoRepository undoStore = undoRepository.getIfAvailable();
        Consumer<List<String>> cascade =
                undoStore == null
                        ? null
                        : xids -> {
                            for (String xid : xids) undoStore.deleteByXid(xid);
                        };
        CleanupScheduler scheduler =
                new CleanupScheduler(
                        () -> {
                            long now = System.currentTimeMillis();
                            return cleaner.cleanup(
                                            now,
                                            now - cleanup.getCommittedRetention().toMillis(),
                                            now - cleanup.getRolledBackRetention().toMillis(),
                                            cleanup.getBatchSize())
                                    .getRemovedXids();
                        },
                        cascade,
                        cleanup.getInterval().toMillis());
        scheduler.start();
        return scheduler;
    }
}

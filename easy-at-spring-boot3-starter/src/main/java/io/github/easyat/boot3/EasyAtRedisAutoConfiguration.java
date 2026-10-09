package io.github.easyat.boot3;

import io.github.easyat.core.*;
import io.github.easyat.spring.EasyAtProperties;
import io.github.easyat.storage.redis.*;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Bean;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Redis 自动配置：仅当类路径存在 {@code JedisPool} 与 {@code RedisAtRepository} 时生效， 提供 Redis 存储仓库、全局锁、分支仓库以及
 * Redis 侧清理调度， 支撑 {@code storage=redis}（全 Redis）与 {@code storage=hybrid}（全局状态在 Redis、undo
 * 在业务库）两种模式。
 *
 * <p>{@code @AutoConfiguration(after = EasyAtAutoConfiguration.class)} 保证在主自动配置之后加载， 从而复用已装配好的
 * {@link UndoDataCodec} 等 Bean。
 */
@AutoConfiguration(after = EasyAtAutoConfiguration.class)
@ConditionalOnClass(
        name = {
            "redis.clients.jedis.JedisPool",
            "io.github.easyat.storage.redis.RedisAtRepository"
        })
public class EasyAtRedisAutoConfiguration {

    /** storage=redis（全 Redis）或 hybrid（全局状态在 Redis、undo 在业务库）都需要连接池。 */
    /**
     * Jedis 连接池：供 Redis 存储仓库、全局锁、分支仓库复用。 storage=redis / hybrid 或 lock=redis 三种情况任一命中即需要连接池。
     *
     * @param props easy-at 配置（取 Redis 连接参数）
     * @return Jedis 连接池
     */
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

    /**
     * Redis 全局事务仓库：全局事务/分支/锁全部存 Redis（storage=redis 或 hybrid 时启用）。 设最小 1s 的 TTL，保证即使进程崩溃未清理，Redis
     * 侧记录也能到期自动回收。
     *
     * @param pool Jedis 连接池
     * @param codec undo 数据编解码器
     * @param props easy-at 配置（取 Redis TTL）
     * @return Redis 存储仓库
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression(
            "'${easy-at.storage.type:file}' == 'redis' || '${easy-at.storage.type:file}' == 'hybrid'")
    AtRepository redisAtRepository(JedisPool pool, UndoDataCodec codec, EasyAtProperties props) {
        return new RedisAtRepository(
                pool, codec, "easy-at", Math.max(1L, props.getRedis().getTtl().getSeconds()));
    }

    /**
     * Redis 全局锁管理器（lock.type=redis 时启用）：分布式全局锁记录存 Redis。
     *
     * @param pool Jedis 连接池
     * @param props easy-at 配置（取锁租约与等待超时）
     * @return Redis 全局锁管理器
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easy-at.lock", name = "type", havingValue = "redis")
    GlobalLockManager redisAtLockManager(JedisPool pool, EasyAtProperties props) {
        return new RedisGlobalLockManager(
                pool,
                props.getLock().getLease().toMillis(),
                props.getLock().getWaitTimeout().toMillis());
    }

    /**
     * Redis 分支仓库（storage=redis 或 hybrid 时启用）：分支事务记录存 Redis。
     *
     * @param pool Jedis 连接池
     * @return Redis 分支仓库
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression(
            "'${easy-at.storage.type:file}' == 'redis' || '${easy-at.storage.type:file}' == 'hybrid'")
    BranchRepository redisBranchRepository(JedisPool pool) {
        return new RedisBranchRepository(pool);
    }

    /**
     * Redis 侧的清理调度。
     *
     * <p>混合存储模式下被删掉的 xid 会通过 {@code UndoRepository#deleteByXid} 级联清理业务库里的 {@code
     * easy_at_undo_log}——两边各清一半，合起来才是一次完整回收。
     *
     * <p>纯 Redis 模式没有 {@code UndoRepository}，级联为空，undo 由 {@code RedisCleanup} 自己删。
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
                        // 优先复用仓库自带的 cleanup，避免重复构造连接；否则用池手动建一个。
                        ? ((RedisAtRepository) repository).cleanup()
                        : new RedisCleanup(
                                pool,
                                "easy-at",
                                Math.max(1L, props.getRedis().getTtl().getSeconds()));
        EasyAtProperties.Cleanup cleanup = props.getCleanup();
        UndoRepository undoStore = undoRepository.getIfAvailable();
        // 混合模式挂了 undo 仓库时，每批删掉的 xid 要级联清业务库 undo 日志；纯 Redis 模式为 null。
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

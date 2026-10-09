package io.github.easyat.dbtests;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.core.*;
import io.github.easyat.storage.redis.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.*;

/**
 * 在<b>真实 Redis</b> 上验证事务存储与全局锁。
 *
 * <p>Redis 路径此前只有一个自动配置冒烟用例（{@code EasyAtRedisAutoConfigurationTest}）——{@link
 * RedisAtRepository} 的状态 CAS Lua、{@link RedisGlobalLockManager} 的加锁/续租 Lua 脚本在真实 Redis 上
 * <b>一次都没跑过</b>。Lua 脚本的行为（返回值类型、Hash/Set 结构、过期语义）只能在真实例上确认。
 */
class RedisRealStorageIT {

    @Test
    void globalTransactionRoundTrip() throws Exception {
        try (RealDatabaseSupport.RedisTarget target = RealDatabaseSupport.redis()) {
            JedisPool pool = pool(target);
            try (Jedis probe = pool.getResource()) {
                probe.flushDB();
            }
            RedisAtRepository repository = new RedisAtRepository(pool);
            RedisGlobalLockManager locks = new RedisGlobalLockManager(pool, 30000L, 0L);
            CountingUndoExecutor undo = new CountingUndoExecutor();
            AtTransactionManager manager =
                    new AtTransactionManager(repository, undo, locks, 3, "it-node-1", 30000L);

            AtTransaction tx = manager.begin("redis-it", 60000L);
            manager.append(undo(tx.getXid(), "u-1"));
            assertEquals(1, repository.find(tx.getXid()).get().getUndoRecords().size());

            manager.rollback(tx.getXid());
            assertEquals(AtStatus.ROLLED_BACK, repository.find(tx.getXid()).get().getStatus());
            assertEquals(1, undo.count, "补偿必须执行一次");
            pool.close();
        }
    }

    @Test
    void statusTransitionsUseCompareAndSet() throws Exception {
        try (RealDatabaseSupport.RedisTarget target = RealDatabaseSupport.redis()) {
            JedisPool pool = pool(target);
            try (Jedis probe = pool.getResource()) {
                probe.flushDB();
            }
            RedisAtRepository repository = new RedisAtRepository(pool);
            AtTransaction tx = new AtTransaction("cas-xid", "cas", System.currentTimeMillis(), System.currentTimeMillis() + 60000);
            repository.create(tx);

            assertTrue(
                    repository.transition(tx.getXid(), AtStatus.ACTIVE, 0, AtStatus.ROLLING_BACK),
                    "第一次 CAS 必须成功");
            assertFalse(
                    repository.transition(tx.getXid(), AtStatus.ACTIVE, 0, AtStatus.ROLLING_BACK),
                    "版本号已推进，同样的 CAS 必须失败——这是多实例不重复推进的根基");
            pool.close();
        }
    }

    @Test
    void globalLockIsMutuallyExclusive() throws Exception {
        try (RealDatabaseSupport.RedisTarget target = RealDatabaseSupport.redis()) {
            JedisPool pool = pool(target);
            try (Jedis probe = pool.getResource()) {
                probe.flushDB();
            }
            RedisGlobalLockManager locks = new RedisGlobalLockManager(pool, 30000L, 0L);
            locks.acquire("dataSource", "inventory", "1001", "xid-a", 0L);
            assertThrows(
                    GlobalLockConflictException.class,
                    () -> locks.acquire("dataSource", "inventory", "1001", "xid-b", 0L),
                    "真实 Redis 上同一行的全局锁必须互斥");

            locks.releaseByXid("xid-a");
            locks.acquire("dataSource", "inventory", "1001", "xid-b", 0L);
            locks.close();
            pool.close();
        }
    }

    private static JedisPool pool(RealDatabaseSupport.RedisTarget target) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(8);
        config.setMaxWaitMillis(TimeUnit.SECONDS.toMillis(10));
        // 始终把 db 编号传进去：用例会 FLUSHDB，指向共享 Redis 时必须靠 db 隔离，避免清掉业务缓存。
        String password =
                target.password() == null || target.password().isEmpty()
                        ? null
                        : target.password();
        return new JedisPool(
                config, target.host(), target.port(), 5000, (String) password, target.database());
    }

    private static UndoRecord undo(String xid, String id) {
        return new UndoRecord(
                id,
                xid,
                "dataSource",
                "t_account",
                "id",
                1L,
                "UPDATE t_account SET balance=? WHERE id=?",
                new Object[] {100, 1L});
    }

    private static final class CountingUndoExecutor implements UndoExecutor {
        int count;

        @Override
        public void rollback(UndoRecord record) {
            count++;
        }
    }
}

package io.github.easyat.dbtests;

import static org.junit.jupiter.api.Assertions.*;

import io.github.easyat.core.*;
import io.github.easyat.storage.redis.*;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Redis 存储的回收验证（真实 Redis）。
 *
 * <p>此前 Redis 存储<b>没有任何回收手段</b>：终态事务的 global hash、undo 实体、分支记录、状态集合成员
 * 全都不会被删除，也没有 TTL——键只增不减（{@code PRODUCTION_GAPS.md} §18.4）。这里验证三件事：
 *
 * <ol>
 *   <li>终态事务能被完整删除（含它引用的 undo 实体，不留孤儿）
 *   <li>重写 undo 时旧的实体不再泄漏
 *   <li>活跃事务不会被清理，且键上有兜底 TTL
 * </ol>
 */
class RedisCleanupIT {

    private static final String PREFIX = "easy-at";

    @Test
    void terminalTransactionIsCompletelyRemoved() throws Exception {
        try (RealDatabaseSupport.RedisTarget target = RealDatabaseSupport.redis();
                JedisPool pool = pool(target)) {
            flush(pool);
            RedisAtRepository repository = new RedisAtRepository(pool);
            RedisCleanup cleanup = repository.cleanup();

            AtTransaction tx =
                    new AtTransaction(
                            "cleanup-xid",
                            "cleanup",
                            System.currentTimeMillis(),
                            System.currentTimeMillis() + 60000L);
            repository.create(tx);
            tx.addUndo(undo(tx.getXid(), "u-1"));
            repository.save(tx);
            assertTrue(
                    repository.transition(tx.getXid(), AtStatus.ACTIVE, 0, AtStatus.ROLLING_BACK));
            assertTrue(
                    repository.transition(
                            tx.getXid(), AtStatus.ROLLING_BACK, 1, AtStatus.ROLLED_BACK));
            assertTrue(exists(pool, PREFIX + ":global:cleanup-xid"), "清理前事务必须存在");
            assertTrue(exists(pool, PREFIX + ":undo:cleanup-xid:u-1"), "清理前 undo 实体必须存在");

            age(pool, tx.getXid(), System.currentTimeMillis() - TimeUnit.DAYS.toMillis(40));

            long now = System.currentTimeMillis();
            List<String> removed =
                    cleanup.cleanup(
                                    now,
                                    now - TimeUnit.DAYS.toMillis(7),
                                    now - TimeUnit.DAYS.toMillis(30),
                                    100)
                            .getRemovedXids();

            assertTrue(removed.contains(tx.getXid()), "超过保留期的 ROLLED_BACK 必须被清理");
            assertFalse(exists(pool, PREFIX + ":global:cleanup-xid"), "global hash 必须消失");
            assertFalse(exists(pool, PREFIX + ":undo:cleanup-xid"), "undo 索引必须消失");
            assertFalse(exists(pool, PREFIX + ":undo:cleanup-xid:u-1"), "undo 实体必须消失");
            assertFalse(
                    memberOf(pool, PREFIX + ":global:status:ROLLED_BACK", tx.getXid()),
                    "状态集合里的成员也必须移除");
        }
    }

    @Test
    void rewritingUndoDoesNotLeakOldEntities() throws Exception {
        try (RealDatabaseSupport.RedisTarget target = RealDatabaseSupport.redis();
                JedisPool pool = pool(target)) {
            flush(pool);
            RedisAtRepository repository = new RedisAtRepository(pool);

            AtTransaction tx =
                    new AtTransaction(
                            "rewrite-xid",
                            "rewrite",
                            System.currentTimeMillis(),
                            System.currentTimeMillis() + 60000L);
            repository.create(tx);
            tx.addUndo(undo(tx.getXid(), "old-1"));
            tx.addUndo(undo(tx.getXid(), "old-2"));
            repository.save(tx);
            assertTrue(exists(pool, PREFIX + ":undo:rewrite-xid:old-1"));

            // 模拟"补偿完一条、再重写"：old-2 消失后不应留下孤儿实体
            tx.removeUndo("old-2");
            repository.save(tx);

            assertTrue(exists(pool, PREFIX + ":undo:rewrite-xid:old-1"));
            assertFalse(
                    exists(pool, PREFIX + ":undo:rewrite-xid:old-2"),
                    "save() 是整体重写：被删掉的 undo 实体必须连键一起消失，否则既占内存又扫不到");
        }
    }

    @Test
    void activeTransactionIsKeptAndCarriesATtl() throws Exception {
        try (RealDatabaseSupport.RedisTarget target = RealDatabaseSupport.redis();
                JedisPool pool = pool(target)) {
            flush(pool);
            RedisAtRepository repository = new RedisAtRepository(pool);
            RedisCleanup cleanup = repository.cleanup();

            AtTransaction tx =
                    new AtTransaction(
                            "active-xid",
                            "active",
                            System.currentTimeMillis(),
                            System.currentTimeMillis() + 60000L);
            repository.create(tx);
            tx.addUndo(undo(tx.getXid(), "u-1"));
            repository.save(tx);

            assertTrue(
                    ttl(pool, PREFIX + ":global:active-xid") > 0,
                    "活跃事务的键必须带兜底 TTL，清理器停摆时才不会永久泄漏");
            assertTrue(ttl(pool, PREFIX + ":undo:active-xid:u-1") > 0);

            long now = System.currentTimeMillis();
            List<String> removed =
                    cleanup.cleanup(
                                    now,
                                    now - TimeUnit.DAYS.toMillis(7),
                                    now - TimeUnit.DAYS.toMillis(30),
                                    100)
                            .getRemovedXids();
            assertFalse(removed.contains(tx.getXid()), "ACTIVE 事务绝不能被清理");
            assertTrue(exists(pool, PREFIX + ":global:active-xid"));

            cleanup.touch(tx.getXid(), System.currentTimeMillis());
            assertTrue(
                    ttl(pool, PREFIX + ":global:active-xid") > 0,
                    "保活后 TTL 必须仍然是正数（活跃事务永不到期）");
        }
    }

    private static void age(JedisPool pool, String xid, long moment) {
        try (Jedis j = pool.getResource()) {
            j.hset(PREFIX + ":global:" + xid, "updated_at", String.valueOf(moment));
            j.zadd(PREFIX + ":cleanup:index", (double) moment, xid);
        }
    }

    private static boolean exists(JedisPool pool, String key) {
        try (Jedis j = pool.getResource()) {
            return j.exists(key);
        }
    }

    private static boolean memberOf(JedisPool pool, String key, String member) {
        try (Jedis j = pool.getResource()) {
            return j.sismember(key, member);
        }
    }

    private static long ttl(JedisPool pool, String key) {
        try (Jedis j = pool.getResource()) {
            return j.ttl(key);
        }
    }

    private static void flush(JedisPool pool) {
        try (Jedis j = pool.getResource()) {
            j.flushDB();
        }
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

    private static JedisPool pool(RealDatabaseSupport.RedisTarget target) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(8);
        config.setMaxWaitMillis(TimeUnit.SECONDS.toMillis(10));
        String password =
                target.password() == null || target.password().isEmpty()
                        ? null
                        : target.password();
        return new JedisPool(
                config, target.host(), target.port(), 5000, (String) password, target.database());
    }
}
